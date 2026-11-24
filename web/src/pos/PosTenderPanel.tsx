import { useCallback, useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { api, newKey } from "../api";
import { formatMoney, normalizeGhanaPhone, parseMoney } from "../money";
import { safeRedirectUrl } from "../Checkout";
import { decodePosTender, isFeatureNotEnabled, isPaidPosOrder, MOMO_NETWORKS, MomoNetwork, normalizeTenderResult, PosOrder, PosTenderRecovery, TenderInitiation, TenderMode, remainingPosBalance } from "./posHelpers";

interface Props {
  merchantId: string;
  terminalId: string | null;
  totalPesewas: number;
  order: PosOrder | null;
  ensureOrder: () => Promise<PosOrder>;
  onOrderPaid: (order: PosOrder) => Promise<void>;
  onModuleDisabled: () => void;
  onClearCart?: () => void;
  hasOrder: boolean;
}
const timeout = () => AbortSignal.timeout(20000);

export function PosTenderPanel({ merchantId, terminalId, totalPesewas, order, ensureOrder, onOrderPaid, onModuleDisabled, onClearCart, hasOrder }: Props) {
  const [mode, setMode] = useState<TenderMode>("CASH");
  const [cashInput, setCashInput] = useState("");
  const [phone, setPhone] = useState("");
  const [network, setNetwork] = useState<MomoNetwork>("mtn");
  const storageKey = "elepay.pos.tender." + merchantId;
  const [recovery,setRecovery] = useState<{attempt:PosTenderRecovery|null;blocked:boolean}>(()=>{try{return {attempt:decodePosTender(sessionStorage.getItem(storageKey)),blocked:false};}catch{return {attempt:null,blocked:true};}});
  const [error,setError] = useState<string|null>(null);
  const [status,setStatus] = useState<string|null>(null);
  const [busy,setBusy] = useState(false);
  const [qr,setQr] = useState<string|null>(null);
  const lock=useRef(false);
  const current=useRef(recovery.attempt);
  const alive=useRef(true);
  const callbacks=useRef({onOrderPaid,onModuleDisabled});
  callbacks.current={onOrderPaid,onModuleDisabled};
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
  const save=useCallback((a:PosTenderRecovery)=>{
    sessionStorage.setItem(storageKey,JSON.stringify(a));current.current=a;
    if(alive.current)setRecovery({attempt:a,blocked:false});
  },[storageKey]);
  const clear=useCallback(()=>{
    sessionStorage.removeItem(storageKey);current.current=null;
    if(alive.current)setRecovery({attempt:null,blocked:false});
  },[storageKey]);
  const finish=useCallback(async (ord:PosOrder)=>{
    if(!isPaidPosOrder(ord))throw new Error("Order is not fully paid. No receipt was issued.");
    // Parent persists the order until receipt generation succeeds.
    clear();
    await callbacks.current.onOrderPaid(ord);
  },[clear]);
  const verify=useCallback(async (a:PosTenderRecovery, allowConfirm:boolean)=>{
    const ord=await api<PosOrder>("/v1/pos/orders/"+a.orderId,{signal:timeout()});
    if(ord.id!==a.orderId)throw new Error("Order identity mismatch");
    if(isPaidPosOrder(ord)){await finish(ord);return;}
    if(!a.checkoutRef){if(alive.current)setStatus(ord.status+" · no checkout reference yet");return;}
    const st=await api<{reference:string;status:string;railStatus:string|null;message?:string;redirectUrl?:string}>(`/v1/public/checkouts/${encodeURIComponent(a.checkoutRef)}/status`,{anonymous:true,signal:timeout()});
    if(st.reference!==a.checkoutRef)throw new Error("Payment reference mismatch");
    if(alive.current)setStatus(st.status+(st.railStatus?" / "+st.railStatus:""));
    if(st.status==="paid"&&allowConfirm){
      const updated=await api<PosOrder>(`/v1/pos/orders/${a.orderId}/digital-checkout/confirm`,{method:"POST",signal:timeout()});
      if(updated.id!==a.orderId)throw new Error("Order identity mismatch");
      if(isPaidPosOrder(updated)){await finish(updated);return;}
      throw new Error("Payment received but POS order is not fully paid. Check the order before accepting another tender.");
    }
  },[finish]);
  const check=useCallback(async()=>{
    const a=current.current;if(!a||lock.current)return;
    lock.current=true;if(alive.current)setBusy(true);
    try{await verify(a,true);if(alive.current)setError(null);}
    catch(e){if(isFeatureNotEnabled(e))callbacks.current.onModuleDisabled();else if(alive.current)setError(e instanceof Error?e.message:"Status not verified. Do not accept another payment.");}
    finally{lock.current=false;if(alive.current)setBusy(false);}
  },[verify]);
  useEffect(()=>{
    if(!recovery.attempt)return;
    const timer=setInterval(()=>{if(document.visibilityState==="visible")void check();},5000);
    const resume=()=>{if(document.visibilityState==="visible")void check();};
    window.addEventListener("online",resume);document.addEventListener("visibilitychange",resume);
    return()=>{clearInterval(timer);window.removeEventListener("online",resume);document.removeEventListener("visibilitychange",resume);};
  },[recovery.attempt,check]);
  useEffect(()=>{
    let active=true;setQr(null);
    const payload=recovery.attempt?.qrPayload;
    if(payload)void QRCode.toDataURL(payload,{margin:2,width:220}).then(v=>{if(active)setQr(v);}).catch(()=>{if(active)setError("Could not render the provider QR. Check status before retrying.");});
    return()=>{active=false;};
  },[recovery.attempt?.qrPayload]);

  async function execute(retry?:PosTenderRecovery){
    if(lock.current||recovery.blocked||(!retry&&current.current))return;
    lock.current=true;setBusy(true);setError(null);
    try{
      if(retry&&retry.checkoutRef){await verify(retry,true);return;}
      const ord=retry?await api<PosOrder>("/v1/pos/orders/"+retry.orderId,{signal:timeout()}):await ensureOrder();
      if(retry&&ord.id!==retry.orderId)throw new Error("Order identity mismatch");
      if(isPaidPosOrder(ord)){await finish(ord);return;}
      const remaining=remainingPosBalance(ord);
      if(!retry&&remaining!==totalPesewas)throw new Error("The server total changed. Review the updated outstanding amount before confirming again.");
      const tender=retry?.tenderType??(mode==="NFC_CARD"?"CARD":mode);
      const cash=parseMoney(cashInput);
      const normalized=normalizeGhanaPhone(phone);
      if(!retry&&tender==="CASH"&&(cash===null||cash<remaining||!Number.isSafeInteger(cash)))throw new Error("Enter actual cash received of at least "+formatMoney(remaining));
      if(!retry&&tender==="MOMO"&&!normalized)throw new Error("Enter a valid Ghana mobile money number.");
      if(!retry&&remaining<=0)throw new Error("No payable balance.");
      const a:PosTenderRecovery=retry??{orderId:ord.id,key:newKey(),tenderType:tender,amountPesewas:tender==="CASH"?cash!:remaining,...(tender==="MOMO"?{phone:normalized!,network}:{}),checkoutRef:null};
      if(retry&&tender!=="CASH"&&remaining!==a.amountPesewas)throw new Error("Original amount differs from outstanding balance. Contact support.");
      save(a);
      if(a.tenderType==="CASH"){
        const paid=await api<PosOrder>(`/v1/pos/orders/${a.orderId}/payments`,{idempotencyKey:a.key,body:{tenderType:"CASH",amountPesewas:a.amountPesewas,reference:null},signal:timeout()});
        if(paid.id!==a.orderId)throw new Error("Order identity mismatch");
        if(!isPaidPosOrder(paid))throw new Error("Cash response is not fully paid. Verify the original order.");
        await finish(paid);return;
      }
      const res=await api<TenderInitiation>(`/v1/pos/orders/${a.orderId}/tenders`,{idempotencyKey:a.key,body:{tenderType:a.tenderType,amountPesewas:a.amountPesewas,...(a.tenderType==="MOMO"?{phone:a.phone,network:a.network}:{})},signal:timeout()});
      if(!res||res.orderId!==a.orderId||res.amountPesewas!==a.amountPesewas)throw new Error("Tender response does not match the approved order.");
      const n=normalizeTenderResult(res);
      if(!n.checkoutRef)throw new Error("Checkout reference unavailable; recover the original tender.");
      const updated={...a,checkoutRef:n.checkoutRef,qrPayload:a.tenderType==="GHQR"?n.qrPayload??undefined:undefined,payUrl:safeRedirectUrl(n.payUrl)??undefined};
      save(updated);setStatus(n.paymentStatus??"Awaiting confirmation");await verify(updated,true);
    }catch(e){
      if(isFeatureNotEnabled(e)){onModuleDisabled();return;}
      setError((current.current?"Original payment requires verification. Do not start another tender. ":"")+(e instanceof Error?e.message:"Request failed."));
    }finally{lock.current=false;setBusy(false);}
  }

  const pending=recovery.attempt;
  const received=parseMoney(cashInput);
  return <section className="pos-tender" aria-busy={busy}>
    <p className="eyebrow">PAYMENT</p><h3>Collect {formatMoney(totalPesewas)}</h3>
    {error&&<p className="notice warn" role="alert">{error}</p>}
    {recovery.blocked?<p className="notice warn">Saved recovery data is unavailable. Contact support before accepting another payment.</p>:pending?<div className="notice" role="status">
      <h3>Original payment requires confirmation</h3><p>Order <span className="mono">{pending.orderId}</span> · {pending.tenderType} · {formatMoney(pending.amountPesewas)}</p>
      {pending.checkoutRef&&<p className="mono">{pending.checkoutRef}</p>}{status&&<p>Status: {status}</p>}
      <p>Do not collect cash or start another payment until this order is resolved. Status checks resume automatically while this screen is visible.</p>
      {qr&&<img src={qr} alt="Provider-returned GhQR payment code" width={220} height={220}/>}
      {pending.payUrl&&<p><a href={safeRedirectUrl(pending.payUrl)??undefined} target="_blank" rel="noreferrer">Open payment checkout</a></p>}
      <button disabled={busy} onClick={()=>void check()}>Check order status</button>
      {!pending.checkoutRef&&<button className="secondary" disabled={busy} onClick={()=>void execute(pending)}>Recover original request</button>}
      <p className="small muted">If the provider reports failure, contact operations to resolve the linked checkout before starting another tender.</p>
    </div>:order&&isPaidPosOrder(order)?<button disabled={busy} onClick={()=>void execute()}>Recover paid receipt</button>:<>
      <div className="pos-tender-modes" aria-label="Tender type">{([["CASH","Cash"],["MOMO","MoMo"],["GHQR","GhQR"],["NFC_CARD","Card / NFC"]] as const).map(([v,label])=><button key={v} disabled={busy} aria-pressed={mode===v} className={mode===v?"":"secondary"} onClick={()=>{setMode(v);setError(null);}}>{label}</button>)}</div>
      {mode==="CASH"&&<><label>Cash received (GH₵)<input inputMode="decimal" disabled={busy} value={cashInput} onChange={e=>setCashInput(e.target.value)}/></label><button className="secondary small" disabled={busy} onClick={()=>setCashInput((totalPesewas/100).toFixed(2))}>Exact cash</button><p>Change: <strong>{received!==null&&received>=totalPesewas?formatMoney(received-totalPesewas):"—"}</strong></p><button disabled={busy||!received||received<totalPesewas} onClick={()=>void execute()}>Confirm cash received</button></>}
      {mode==="MOMO"&&<><label>Network<select disabled={busy} value={network} onChange={e=>setNetwork(e.target.value as MomoNetwork)}>{MOMO_NETWORKS.map(n=><option key={n.value} value={n.value}>{n.label}</option>)}</select></label><label>Customer phone<input type="tel" disabled={busy} value={phone} onChange={e=>setPhone(e.target.value)} placeholder="024 123 4567"/></label><p className="small muted">Customer approves on their phone. Never ask for their MoMo PIN.</p><button disabled={busy||!normalizeGhanaPhone(phone)} onClick={()=>void execute()}>Request MoMo payment</button></>}
      {mode==="GHQR"&&<><p className="small muted">Request a dynamic provider QR. No generic link is presented as GhQR.</p><button disabled={busy} onClick={()=>void execute()}>Request GhQR payment</button></>}
      {mode==="NFC_CARD"&&<><p className="small muted">Use the provider-hosted card approval flow. Contactless card capture requires an approved, provisioned terminal and native reader integration. {terminalId?"Terminal selected.":"No terminal selected."} The browser cannot collect raw EMV data or act as a certified reader.</p><button disabled={busy} onClick={()=>void execute()}>Open card payment</button></>}
      {!hasOrder&&onClearCart&&<button className="secondary" disabled={busy} onClick={onClearCart}>Clear cart</button>}
    </>}
  </section>;
}
