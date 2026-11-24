import { useCallback, useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { api, ApiError, newKey } from "./api";
import { formatMoney, parseMoney } from "./money";
import { safeRedirectUrl } from "./Checkout";
import { BusinessAccounting, CustomersAndLoyalty, FloorPlanView, InventoryAndStock, InvoicesAndQuotes, PayrollAndEmployees, PosRegister } from "./MerchantTools";

interface MerchantView { id: string; tradingName: string; kind: string; status: string; merchantCode: string; role: string }
interface Balances { pending: number; available: number; reserved: number }
interface Line {
  reference: string; line: number; method?: string | null; commission?: number; processingFee?: number; kind: string; description: string | null; amount: number; fee: number; net: number;
  refunded: number; disputed: number; pending: number; settled: number; status: string; paidAt: string | null; collector: string;
}
interface Payout { reference: string; amount: number; status: string; clientReference: string; createdAt: string; failureReason: string | null }
interface Destination { id: string; type: string; network: string; accountNumber: string; accountName: string; verification: string; status: string; verifiedName: string | null; coolingOffUntil: string | null; oobVerificationStatus: string | null }

export function destinationReady(d: Pick<Destination, "status" | "verification" | "coolingOffUntil" | "oobVerificationStatus">, now = Date.now()): boolean {
  if (d.status !== "active" || d.verification !== "verified" || (d.oobVerificationStatus != null && d.oobVerificationStatus !== "confirmed")) return false;
  if (d.coolingOffUntil != null) { const t = Date.parse(d.coolingOffUntil); if (!Number.isFinite(t) || t > now) return false; }
  return true;
}
export function merchantStatusClass(status: string): string {
  const s = status.toLowerCase();
  if (["active","paid","settled","succeeded","completed","verified"].includes(s)) return "active";
  if (["failed","rejected","returned","cancelled","expired","suspended","refunded"].includes(s)) return "failed";
  return "pending";
}
function merchantDate(value: string | null): string {
  if (!value || !Number.isFinite(Date.parse(value))) return "—";
  return new Intl.DateTimeFormat("en-GH", { dateStyle:"medium", timeStyle:"short", timeZone:"Africa/Accra" }).format(new Date(value));
}
export function summarizePaidLines(lines: Pick<Line,"paidAt"|"amount"|"fee"|"net"|"refunded"|"disputed">[]) {
  return lines.filter(l => l.paidAt && Number.isFinite(Date.parse(l.paidAt))).reduce((a,l) => ({count:a.count+1,gross:a.gross+l.amount,fee:a.fee+l.fee,net:a.net+l.net,refunded:a.refunded+l.refunded,disputed:a.disputed+l.disputed}),{count:0,gross:0,fee:0,net:0,refunded:0,disputed:0});
}
export function safeMerchantCsv(value: unknown): string {
  if (typeof value === "number" && Number.isSafeInteger(value)) return String(value);
  const raw = value == null ? "" : String(value);
  const safe = /^(?:[\t\r\n]|\s*[=+\-@])/.test(raw) ? "'" + raw : raw;
  return '"' + safe.replace(/"/g, '""') + '"';
}
function exportRows(name:string, columns:string[], rows:Record<string,unknown>[]) {
  const text = [columns.map(safeMerchantCsv).join(","), ...rows.map(r => columns.map(k => safeMerchantCsv(r[k])).join(","))].join("\r\n");
  const url = URL.createObjectURL(new Blob([text],{type:"text/csv;charset=utf-8;"}));
  const a = document.createElement("a"); a.href=url; a.download=name+".csv"; document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
}
function useMerchantRows<T>(id:string,path:string) {
  const [data,setData]=useState<T[] | null>(null); const [error,setError]=useState<string|null>(null); const [loading,setLoading]=useState(true); const seq=useRef(0);
  const load=useCallback(async()=>{const n=++seq.current; setLoading(true); setError(null); setData(null); try {const result=await api<T[]>(`/v1/merchants/${id}/${path}`); if(!Array.isArray(result)) throw new Error("Invalid response"); if(n===seq.current)setData(result);}catch(e){if(n===seq.current)setError(e instanceof Error?e.message:"Could not load records.");}finally{if(n===seq.current)setLoading(false);}},[id,path]);
  useEffect(()=>{void load();return()=>{seq.current++;};},[load]); return {data,error,loading,load};
}

type MerchantAction = {kind:"refund";key:string;reference:string;line:number;amount:number;reason:string} | {kind:"withdraw";key:string;amount:number;destinationId:string} | {kind:"collect";key:string;amount:number;description:string;expiresInMinutes:number};
export function decodeMerchantAction(raw:string|null,kind:MerchantAction["kind"]):MerchantAction|null {
  if(raw===null)return null; const v=JSON.parse(raw) as MerchantAction;
  if(!v || v.kind!==kind || typeof v.key!=="string" || !v.key || v.key.length>200 || !Number.isSafeInteger(v.amount) || v.amount<=0)throw new Error("Invalid saved action");
  if(v.kind==="refund" && (!/^[\\w-]+$/.test(v.reference) || !Number.isSafeInteger(v.line) || v.line<0 || typeof v.reason!=="string" || !v.reason.trim() || v.reason.length>500))throw new Error("Invalid saved refund");
  if(v.kind==="withdraw" && (typeof v.destinationId!=="string" || !/^[0-9a-f-]{36}$/i.test(v.destinationId)))throw new Error("Invalid saved withdrawal");
  if(v.kind==="collect" && (typeof v.description!=="string" || v.description.length>500 || !Number.isInteger(v.expiresInMinutes) || v.expiresInMinutes<1 || v.expiresInMinutes>10080))throw new Error("Invalid saved checkout");
  return v;
}
function useMerchantAction(id:string,kind:MerchantAction["kind"]) {
  const storageKey=`elepay.merchant.action.${id}.${kind}`;
  const [state,setState]=useState<{pending:MerchantAction|null;blocked:boolean}>(()=>{try{return {pending:decodeMerchantAction(sessionStorage.getItem(storageKey),kind),blocked:false};}catch{return {pending:null,blocked:true};}});
  const [busy,setBusy]=useState(false); const [message,setMessage]=useState<string|null>(null); const lock=useRef(false);
  function clear(){try{sessionStorage.removeItem(storageKey);setState({pending:null,blocked:false});return true;}catch{setState(s=>({...s,blocked:true}));setMessage("Recovery storage could not be cleared. Contact support.");return false;}}
  async function run<T>(action:MerchantAction, send:(a:MerchantAction)=>Promise<T>, done:(r:T)=>void) {
    if(lock.current || state.blocked || (state.pending && state.pending.key!==action.key))return;
    lock.current=true;setBusy(true);setMessage(null);
    try {try{sessionStorage.setItem(storageKey,JSON.stringify(action));}catch{setMessage("Recovery storage unavailable. No new request was submitted.");return;}
      setState({pending:action,blocked:false}); const response=await send(action); done(response); clear();
    }catch(e){setMessage("Request outcome requires review. "+(e instanceof ApiError?e.message:e instanceof Error?e.message:"Connection unavailable."));}finally{lock.current=false;setBusy(false);}
  }
  return {...state,busy,message,run,clear};
}

const TABS = [
  "Overview", "POS Register", "Orders & Receipts", "Inventory & Stock", "Restaurant & KDS",
  "Invoices & Quotes", "Payroll & Employees", "Customers & Loyalty", "Business Accounting",
  "Transactions", "Settlements", "Refunds", "Disputes", "Payouts", "Collect"
] as const;

/** Merchant BackOffice & Business Management Dashboard */
export function MerchantDashboard() {
  const [merchants,setMerchants]=useState<MerchantView[]>([]);
  const [current,setCurrent]=useState<string|null>(null);
  const [tab,setTab]=useState<(typeof TABS)[number]>("Overview");
  const [error,setError]=useState<string|null>(null); const [loading,setLoading]=useState(true); const seq=useRef(0);
  const load=useCallback(async()=>{const n=++seq.current;setLoading(true);setError(null);try{const rows=await api<MerchantView[]>("/v1/merchants");if(!Array.isArray(rows))throw new Error("Invalid merchant response");if(n===seq.current){setMerchants(rows);setCurrent(old=>rows.some(m=>m.id===old)?old:rows[0]?.id??null);}}catch(e){if(n===seq.current){setMerchants([]);setCurrent(null);setError(e instanceof Error?e.message:"Merchant list unavailable.");}}finally{if(n===seq.current)setLoading(false);}},[]);
  useEffect(()=>{void load();return()=>{seq.current++;};},[load]);
  const merchant=merchants.find(m=>m.id===current);
  const finance=Boolean(merchant && ["owner","admin","finance"].includes(merchant.role));
  const collect=Boolean(merchant && ["owner","admin","finance","cashier"].includes(merchant.role) && merchant.status==="active");
  const financeTabs=["Settlements","Refunds","Disputes","Payouts","Business Accounting","Payroll & Employees"];
  const groups=[{label:"Payments",tabs:["Overview","Transactions","Orders & Receipts","Collect","Settlements","Refunds","Disputes","Payouts"]},{label:"Business tools",tabs:["POS Register","Inventory & Stock","Restaurant & KDS","Invoices & Quotes","Payroll & Employees","Customers & Loyalty","Business Accounting"]}];
  return <main className="merchant-workspace">
    <div className="page-title"><div><p className="eyebrow">ELEPAY BUSINESS</p><h1>Merchant dashboard</h1><p className="muted">Manage payment activity, collections and settlement.</p></div><button className="secondary" disabled={loading} onClick={()=>void load()}>{loading?"Refreshing…":"Refresh businesses"}</button></div>
    {error && <p role="alert" className="notice warn">{error}</p>}
    {loading&&!merchant&&<p role="status">Loading your businesses…</p>}
    {!loading&&!error&&!merchant&&<div className="panel"><h2>No linked business</h2><p>Contact ELEPAY support to complete merchant onboarding or request access from your business owner.</p></div>}
    {merchant&&<><section className="panel merchant-context"><div><p className="eyebrow">CURRENT BUSINESS</p><h2>{merchant.tradingName}</h2><p className="small muted">Merchant code {merchant.merchantCode} · Role: {merchant.role}</p><span className={"tag "+merchantStatusClass(merchant.status)}>{merchant.status}</span></div>{merchants.length>1&&<label>Switch business<select value={current??""} onChange={e=>{setCurrent(e.target.value);setTab("Overview");}}>{merchants.map(m=><option key={m.id} value={m.id}>{m.tradingName} ({m.role})</option>)}</select></label>}</section>
    <div className="merchant-shell"><aside className="panel merchant-sidebar" aria-label="Merchant sections">{groups.map(g=><nav key={g.label} aria-label={g.label}><p className="eyebrow">{g.label}</p>{g.tabs.filter(t=>!financeTabs.includes(t)||finance).filter(t=>t!=="Collect"||collect).map(t=><button key={t} aria-pressed={tab===t} className={tab===t?"active":""} onClick={()=>setTab(t as typeof tab)}>{t}</button>)}</nav>)}</aside>
    <div className="merchant-content" key={merchant.id+":"+tab}>
      {tab==="Overview"&&<Overview id={merchant.id} finance={finance} onNavigate={setTab} />}
      {tab==="POS Register"&&<PosRegister merchant={merchant}/>}
      {tab==="Orders & Receipts"&&<OrdersAndReceipts merchantId={merchant.id}/>}
      {tab==="Inventory & Stock"&&<InventoryAndStock merchantId={merchant.id}/>}
      {tab==="Restaurant & KDS"&&<RestaurantAndKds merchantId={merchant.id}/>}
      {tab==="Invoices & Quotes"&&<InvoicesAndQuotes merchantId={merchant.id}/>}
      {tab==="Payroll & Employees"&&finance&&<PayrollAndEmployees merchantId={merchant.id}/>}
      {tab==="Customers & Loyalty"&&<CustomersAndLoyalty merchantId={merchant.id}/>}
      {tab==="Business Accounting"&&finance&&<BusinessAccounting merchantId={merchant.id}/>}
      {tab==="Transactions"&&<Transactions id={merchant.id} canRefund={finance&&merchant.status==="active"}/>}
      {tab==="Settlements"&&finance&&<Rows id={merchant.id} path="settlements" columns={["reference","amount","lines","createdAt"]}/>}
      {tab==="Refunds"&&finance&&<Rows id={merchant.id} path="refunds" columns={["reference","checkout","line","amount","status","reason","created_at"]}/>}
      {tab==="Disputes"&&finance&&<Rows id={merchant.id} path="disputes" columns={["checkout","line","amount","reason","status"]}/>}
      {tab==="Payouts"&&finance&&<Payouts id={merchant.id} enabled={merchant.status==="active"}/>}
      {tab==="Collect"&&collect&&<Collect merchant={merchant}/>}
    </div></div></>}
  </main>;
}

// -------------------------------------------------------------------------------------------------
// 2. Orders & Receipts View
// -------------------------------------------------------------------------------------------------
function OrdersAndReceipts({ merchantId }: { merchantId: string }) {
  const [filterStatus, setFilterStatus] = useState("all");
  const [searchRef, setSearchRef] = useState("");
  const [selectedReceipt, setSelectedReceipt] = useState<Line | null>(null);
  const {data,error,loading,load}=useMerchantRows<Line>(merchantId,"transactions?limit=500");
  const orders=data??[];

  const filtered = orders.filter((o) => {
    const matchStatus = filterStatus === "all" || o.status.toLowerCase() === filterStatus.toLowerCase();
    const matchRef = o.reference.toLowerCase().includes(searchRef.toLowerCase()) ||
                     (o.description && o.description.toLowerCase().includes(searchRef.toLowerCase()));
    return matchStatus && matchRef;
  });

  return (
    <div>
      <div className="page-title"><div><h2>Allocation summaries</h2><p className="muted small">Latest 500 allocation records, not a certified payment receipt.</p></div><button className="secondary" disabled={loading} onClick={()=>void load()}>Refresh</button></div>
      {error&&<p role="alert" className="error">{error}</p>}
      <div className="flex-between">
        <div className="flex-row">
          <label className="choice">Filter:
            <select value={filterStatus} onChange={(e) => setFilterStatus(e.target.value)} style={{ width: "auto" }}>
              <option value="all">All Orders</option>
              <option value="settled">Settled</option>
              <option value="pending">Pending</option>
              <option value="refunded">Refunded</option>
            </select>
          </label>
          <input
            placeholder="Search receipt ref or item…"
            value={searchRef}
            onChange={(e) => setSearchRef(e.target.value)}
            style={{ width: "240px" }}
          />
        </div>
      </div>

      <table className="grid" style={{ marginTop: "1rem" }}>
        <thead>
          <tr>
            <th>Receipt Ref</th>
            <th>Description</th>
            <th>Collector</th>
            <th className="num">Gross</th>
            <th className="num">Fee</th>
            <th className="num">Net</th>
            <th>Status</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {filtered.map((o) => (
            <tr key={o.reference + o.line}>
              <td className="mono">{o.reference}</td>
              <td>{o.kind} {o.description || "Order item"}</td>
              <td>{o.collector}</td>
              <td className="num">{formatMoney(o.amount)}</td>
              <td className="num">{formatMoney(o.fee)}</td>
              <td className="num">{formatMoney(o.net)}</td>
              <td><span className={`tag ${merchantStatusClass(o.status)}`}>{o.status}</span></td>
              <td>
                <button className="small secondary" onClick={() => setSelectedReceipt(o)}>View allocation</button>
              </td>
            </tr>
          ))}
          {filtered.length === 0 && (
            <tr><td colSpan={8} className="muted">{loading?"Loading…":error?"Records unavailable.":"No orders match the current filter."}</td></tr>
          )}
        </tbody>
      </table>

      {selectedReceipt && (
        <div className="modal-backdrop">
          <div className="modal-content" style={{ maxWidth: "450px" }}>
            <div className="flex-between">
              <h3>Allocation details</h3>
              <button className="small secondary" onClick={() => setSelectedReceipt(null)}>✕</button>
            </div>
            <p className="mono small">Reference: {selectedReceipt.reference}</p>
            <table className="lines">
              <tbody>
                <tr><td>Item Description</td><td className="num">{selectedReceipt.description || "Sales Item"}</td></tr>
                <tr><td>Gross Amount</td><td className="num">{formatMoney(selectedReceipt.amount)}</td></tr>
                <tr><td>ELEPAY fee</td><td className="num">{formatMoney(selectedReceipt.fee)}</td></tr>
                <tr className="total"><td>Merchant Net Receivable</td><td className="num">{formatMoney(selectedReceipt.net)}</td></tr>
              </tbody>
            </table>
            <div style={{ marginTop: "1rem" }}>
              <button onClick={()=>exportRows("elepay_allocation_"+selectedReceipt.reference,["reference","line","kind","description","amount","fee","net","refunded","disputed","status","paidAt"],[selectedReceipt as unknown as Record<string,unknown>])}>Export allocation CSV</button>
              <button className="secondary" onClick={() => setSelectedReceipt(null)}>Close</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// 4. Restaurant Floor & KDS View
// -------------------------------------------------------------------------------------------------

function RestaurantAndKds({ merchantId }: { merchantId: string }) {
  const [subTab, setSubTab] = useState<"floor" | "kds">("floor");

  return (
    <div>
      <div className="flex-row" style={{ marginBottom: "1rem" }}>
        <button className={subTab === "floor" ? "" : "secondary"} onClick={() => setSubTab("floor")}>
          🍽️ Table Floor Plan
        </button>
        <button className={subTab === "kds" ? "" : "secondary"} onClick={() => setSubTab("kds")}>
          👨‍🍳 Kitchen Display System (KDS)
        </button>
      </div>

      {subTab === "floor" && <FloorPlanView merchantId={merchantId} />}

      {subTab === "kds" && <KitchenSetup merchantId={merchantId} />}
    </div>
  );
}

interface KitchenStation {
  id: string; name: string; isDefault: boolean; output: "SCREEN" | "PRINTER" | "BOTH";
  printerAddress: string | null; paperWidth: number; lastSeenAt: string | null;
}

interface KitchenDevice { id: string; label: string; prefix: string; createdAt: string; revokedAt: string | null }

/** Kitchen stations and printers. The live kitchen screen itself runs full-screen at /kitchen on a tablet. */
function KitchenSetup({ merchantId }: { merchantId: string }) {
  const [stations, setStations] = useState<KitchenStation[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState({ name: "", output: "SCREEN" as KitchenStation["output"], printerAddress: "", paperWidth: 42, isDefault: false });
  const [devices, setDevices] = useState<KitchenDevice[]>([]);
  const [pairing, setPairing] = useState<{ code: string; merchantCode: string; expiresAt: string } | null>(null);
  const [pairStation, setPairStation] = useState("");
  const loadDevices = () => api<KitchenDevice[]>(`/v1/restaurant/kds/devices/merchant/${merchantId}`).then(setDevices).catch(() => setDevices([]));
  useEffect(() => { void loadDevices(); }, [merchantId]);

  async function newPairingCode() {
    setError(null);
    try {
      setPairing(await api("/v1/restaurant/kds/devices/pairing-codes", { body: { merchantId, stationId: pairStation || null } }));
    } catch (err) { setError(String((err as Error).message ?? err)); }
  }

  async function revokeDevice(id: string) {
    try { await api(`/v1/restaurant/kds/devices/${id}/revoke?merchantId=${merchantId}`, { method: "POST" }); await loadDevices(); }
    catch (err) { setError(String((err as Error).message ?? err)); }
  }
  const load = () => api<KitchenStation[]>(`/v1/restaurant/kds/stations/merchant/${merchantId}`).then(setStations)
    .catch((e) => setError(String(e.message ?? e)));
  useEffect(() => { void load(); }, [merchantId]);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      await api("/v1/restaurant/kds/stations", { body: { merchantId, name: form.name, output: form.output, isDefault: form.isDefault,
        printerAddress: form.output === "SCREEN" && !form.printerAddress ? null : form.printerAddress, paperWidth: form.paperWidth } });
      setForm({ ...form, name: "", printerAddress: "" });
      await load();
    } catch (err) { setError(String((err as Error).message ?? err)); }
  }

  async function remove(id: string) {
    try { await api(`/v1/restaurant/kds/stations/${id}/deactivate`, { method: "POST" }); await load(); }
    catch (err) { setError(String((err as Error).message ?? err)); }
  }

  const screenState = (s: KitchenStation) => {
    if (s.output === "PRINTER") return "Printer only";
    if (!s.lastSeenAt) return "Screen not connected yet";
    const mins = Math.round((Date.now() - Date.parse(s.lastSeenAt)) / 60000);
    return mins < 3 ? "Screen online" : `Screen last seen ${mins} min ago`;
  };

  return (
    <div>
      <div className="panel">
        <h3>Kitchen screen</h3>
        <p>Open <a href="/kitchen" target="_blank" rel="noreferrer"><strong>{window.location.origin}/kitchen</strong></a> on a tablet or POS screen in the kitchen and choose its station.
          It keeps working when the internet drops and sends updates when it returns. Add it to the home screen to run full-screen.</p>
      </div>
      {error && <p className="error">{error}</p>}
      <div className="panel">
        <h3>Kitchen devices</h3>
        <p className="muted small">Pair a tablet or the kitchen printer phone once; it keeps working after restarts without anyone signing in.
          A paired device can only use the kitchen display.</p>
        <div className="flex-row">
          <select value={pairStation} onChange={(e) => setPairStation(e.target.value)} style={{ maxWidth: "16rem" }}>
            <option value="">Station: choose on the device</option>
            {stations.map((s) => <option key={s.id} value={s.id}>Station: {s.name}</option>)}
          </select>
          <button onClick={() => void newPairingCode()}>Pair a kitchen device</button>
        </div>
        {pairing && (
          <div className="kitchen-pair-codes">
            <p>On the device, open <strong>{window.location.origin}/kitchen</strong> (or the ELEPAY Kitchen app) and enter:</p>
            <dl className="confirm">
              <dt>Merchant code</dt><dd className="big-code">{pairing.merchantCode}</dd>
              <dt>Pairing code</dt><dd className="big-code">{pairing.code.slice(0, 3)} {pairing.code.slice(3)}</dd>
            </dl>
            <p className="muted small">Works once, until {new Date(pairing.expiresAt).toLocaleTimeString("en-GH", { hour: "2-digit", minute: "2-digit" })}.</p>
          </div>
        )}
        <table className="grid">
          <tbody>
            {devices.map((d) => (
              <tr key={d.id}>
                <td>{d.label}</td>
                <td className="small muted">paired {new Date(d.createdAt).toLocaleDateString("en-GH")}</td>
                <td>{d.revokedAt ? <span className="tag failed">removed</span>
                  : <button className="secondary small" onClick={() => void revokeDevice(d.id)}>Remove</button>}</td>
              </tr>
            ))}
            {devices.length === 0 && <tr><td className="muted small">No paired devices yet.</td></tr>}
          </tbody>
        </table>
      </div>
      <h3>Stations</h3>
      <table className="grid">
        <thead><tr><th>Station</th><th>Output</th><th>Printer</th><th>Status</th><th /></tr></thead>
        <tbody>
          {stations.map((s) => (
            <tr key={s.id}>
              <td>{s.name}{s.isDefault && <span className="tag info" style={{ marginLeft: ".4rem" }}>default</span>}</td>
              <td>{s.output === "BOTH" ? "Screen + printer" : s.output === "PRINTER" ? "Printer" : "Screen"}</td>
              <td className="small">{s.printerAddress ?? "—"}{s.printerAddress && ` · ${s.paperWidth} cols`}</td>
              <td className="small">{screenState(s)}</td>
              <td><button className="secondary small" onClick={() => void remove(s.id)}>Remove</button></td>
            </tr>
          ))}
          {stations.length === 0 && <tr><td colSpan={5} className="muted">No stations yet. Without stations every order shows on every kitchen screen.</td></tr>}
        </tbody>
      </table>
      <form onSubmit={add} className="panel" style={{ marginTop: "1rem" }}>
        <h3>Add a station</h3>
        <label>Name<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Kitchen, Grill, Drinks…" required /></label>
        <label>Shows orders on
          <select value={form.output} onChange={(e) => setForm({ ...form, output: e.target.value as KitchenStation["output"] })}>
            <option value="SCREEN">Screen</option><option value="BOTH">Screen and printer</option><option value="PRINTER">Printer only</option>
          </select>
        </label>
        <label>Printer address {form.output === "SCREEN" && <span className="muted small">(optional: prints automatically if the screen goes off)</span>}
          <input value={form.printerAddress} onChange={(e) => setForm({ ...form, printerAddress: e.target.value })}
            placeholder="tcp://192.168.1.50:9100 or bt://AA:BB:CC:DD:EE:FF" required={form.output !== "SCREEN"} />
        </label>
        <label>Paper
          <select value={form.paperWidth} onChange={(e) => setForm({ ...form, paperWidth: Number(e.target.value) })}>
            <option value={32}>58mm (32 characters)</option><option value={42}>80mm (42 characters)</option><option value={48}>80mm wide (48 characters)</option>
          </select>
        </label>
        <label className="choice"><input type="checkbox" style={{ width: "auto" }} checked={form.isDefault}
          onChange={(e) => setForm({ ...form, isDefault: e.target.checked })} /> Default station for items without a station</label>
        <button type="submit">Add station</button>
      </form>
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// 8. Business Accounting (P&L and Balance Sheet)
// -------------------------------------------------------------------------------------------------
// -------------------------------------------------------------------------------------------------
// Existing Merchant Views (Overview, Transactions, Rows, Payouts, Collect)
// -------------------------------------------------------------------------------------------------

function Overview({id,finance,onNavigate}:{id:string;finance:boolean;onNavigate:(tab:typeof TABS[number])=>void}) {
  const {data,error,loading,load}=useMerchantRows<Line>(id,"transactions?limit=500");
  const [b,setB]=useState<Balances|null>(null);const [balanceError,setBalanceError]=useState<string|null>(null);const [updated,setUpdated]=useState<string|null>(null);const [balanceBusy,setBalanceBusy]=useState(false);const seq=useRef(0);
  const reload=useCallback(async()=>{if(!finance)return;const n=++seq.current;setB(null);setUpdated(null);setBalanceError(null);setBalanceBusy(true);try{const v=await api<Balances>("/v1/merchants/"+id+"/balances");if(!v||![v.available,v.pending,v.reserved].every(Number.isSafeInteger))throw new Error("Invalid balances");if(n===seq.current){setB(v);setUpdated(new Date().toISOString());}}catch(e){if(n===seq.current)setBalanceError(e instanceof Error?e.message:"Balances unavailable.");}finally{if(n===seq.current)setBalanceBusy(false);}},[id,finance]);
  useEffect(()=>{void reload();return()=>{seq.current++;};},[reload]);
  const metrics=data?summarizePaidLines(data):null;
  return <><div className="page-title"><div><h2>Financial overview</h2><p className="muted small">Server-reported financial data · GHS</p></div><button className="secondary" disabled={loading||balanceBusy} onClick={()=>{void load();void reload();}}>Refresh overview</button></div>
  {finance&&<>{balanceError&&<p className="error" role="alert">{balanceError}</p>}<div className="cards merchant-balances" aria-busy={balanceBusy}>{([["Available balance","available"],["Pending settlement","pending"],["Reserved funds","reserved"]] as const).map(([label,k])=><div className={"card "+(k==="available"?"balance-primary":"")} key={k}><p className="muted small">{label}</p><div className="big">{b?formatMoney(b[k]):balanceBusy?"Loading…":"Unavailable"}</div></div>)}</div><p className="small muted">{updated?"Balances checked "+merchantDate(updated)+" GMT":"Balances not verified"}</p></>}
  <section className="panel"><h2>Recent paid allocation activity</h2><p className="muted small">Calculated only from paid lines among the latest 500 allocation records. Not a complete revenue statement or a settlement balance.</p>{error&&<p className="error" role="alert">{error}</p>}
  <div className="cards merchant-metrics">{([["Gross paid",metrics?.gross],["ELEPAY fees",metrics?.fee],["Original merchant net",metrics?.net],["Refunded",metrics?.refunded],["Disputed",metrics?.disputed]] as const).map(([label,v])=><div className="card" key={label}><p className="small muted">{label}</p><strong>{v===undefined?"—":formatMoney(v)}</strong></div>)}</div><p className="small muted">{metrics?metrics.count+" paid allocations in "+(data?.length??0)+" returned records":loading?"Loading activity…":"Activity unavailable"}</p><button onClick={()=>onNavigate("Transactions")}>View transactions</button>{finance&&<button className="secondary" onClick={()=>onNavigate("Settlements")}>View settlements</button>}</section></>;
}

export function validateRefundInput(amountInput:string,reason:string,maxPesewas:number):{pesewas:number}|{error:string}{
 const pesewas=parseMoney(amountInput);
 if(pesewas===null||pesewas<=0||!Number.isSafeInteger(pesewas))return {error:"Enter a valid refund amount, e.g. 10.00."};
 if(pesewas>maxPesewas)return {error:"Refund amount exceeds available balance ("+formatMoney(maxPesewas)+")."};
 if(!reason.trim())return {error:"Enter a refund reason."};
 if(reason.trim().length>500)return {error:"Refund reason must be at most 500 characters."};
 return {pesewas};
}

function Transactions({id,canRefund}:{id:string;canRefund:boolean}){
 const {data,error,loading,load}=useMerchantRows<Line>(id,"transactions?limit=500");
 const [query,setQuery]=useState("");const [status,setStatus]=useState("all");const [page,setPage]=useState(1);
 const [target,setTarget]=useState<Line|null>(null);const [amount,setAmount]=useState("");const [reason,setReason]=useState("");const [review,setReview]=useState<MerchantAction|null>(null);const [message,setMessage]=useState<string|null>(null);
 const action=useMerchantAction(id,"refund");
 const rows=data??[];const filtered=rows.filter(l=>(status==="all"||l.status===status)&&[l.reference,l.description??"",l.collector,l.kind].some(v=>v.toLowerCase().includes(query.trim().toLowerCase())));
 const pages=Math.max(1,Math.ceil(filtered.length/20));const p=Math.min(page,pages);
 function send(a:MerchantAction){if(a.kind!=="refund")throw new Error("Wrong action");return api<{reference:string;status:string}>(`/v1/merchants/${id}/checkouts/${encodeURIComponent(a.reference)}/allocations/${a.line}/refunds`,{idempotencyKey:a.key,body:{amount:a.amount,reason:a.reason}});}
 function submit(a:MerchantAction){void action.run(a,send,r=>{if(!r||typeof r.reference!=="string"||typeof r.status!=="string")throw new Error("Unverified refund response");setMessage("Refund reference "+r.reference+" · Status: "+r.status+". Check the refunds tab for updates.");setTarget(null);setReview(null);void load();});}
 function prepare(e:React.FormEvent){e.preventDefault();if(!target||!canRefund)return;const v=validateRefundInput(amount,reason,Math.max(0,target.amount-target.refunded-target.disputed));if("error" in v){setMessage(v.error);return;}setMessage(null);setReview({kind:"refund",key:newKey(),reference:target.reference,line:target.line,amount:v.pesewas,reason:reason.trim()});}
 return <section className="panel"><div className="page-title"><div><h2>Transactions</h2><p className="muted small">Latest 500 merchant allocations · amounts in GHS</p></div><button className="secondary" disabled={loading} onClick={()=>void load()}>Refresh</button></div>
 {error&&<p role="alert" className="error">{error}</p>}{message&&<p role="status" className="notice">{message}</p>}{action.message&&<p role="alert" className="notice warn">{action.message}</p>}
 {action.blocked&&<p className="notice warn">Refund recovery storage is unavailable. Contact support before issuing another refund.</p>}
 {action.pending&&<div className="notice warn"><strong>Original refund requires review</strong><p>{action.pending.kind==="refund"?action.pending.reference:""} · {formatMoney(action.pending.amount)}</p><p>Retrying uses the original idempotency key and request.</p><button disabled={action.busy||action.blocked||!canRefund} onClick={()=>action.pending&&submit(action.pending)}>Retry original refund</button></div>}
 <div className="wallet-filter"><label>Search<input value={query} onChange={e=>{setQuery(e.target.value);setPage(1);}} placeholder="Reference, description or collector"/></label><label>Status<select value={status} onChange={e=>{setStatus(e.target.value);setPage(1);}}><option value="all">All</option>{[...new Set(rows.map(l=>l.status))].sort().map(v=><option key={v}>{v}</option>)}</select></label><button className="secondary" disabled={!filtered.length||loading} onClick={()=>exportRows("elepay_recent_allocations",["reference","line","kind","description","collector","method","amount","fee","commission","processingFee","net","refunded","disputed","pending","settled","status","paidAt"],filtered as unknown as Record<string,unknown>[])}>Export filtered CSV</button></div>
 <div className="table-scroll" role="region" aria-label="Merchant transactions" tabIndex={0}><table className="grid"><thead><tr><th>Reference / line</th><th>Collector</th><th>Paid (GMT)</th><th>Method</th><th className="num">Gross</th><th className="num">Fee</th><th className="num">Net</th><th className="num">Refunded</th><th>Status</th><th>Action</th></tr></thead><tbody>{filtered.slice((p-1)*20,p*20).map(l=><tr key={l.reference+":"+l.line}><td className="mono small">{l.reference} · {l.line}<div className="muted">{l.description??l.kind}</div></td><td>{l.collector}</td><td>{merchantDate(l.paidAt)}</td><td>{l.method??"—"}</td><td className="num">{formatMoney(l.amount)}</td><td className="num">{formatMoney(l.fee)}</td><td className="num">{formatMoney(l.net)}</td><td className="num">{formatMoney(l.refunded)}</td><td><span className={"tag "+merchantStatusClass(l.status)}>{l.status}</span></td><td>{canRefund&&l.paidAt&&l.refunded+l.disputed<l.amount&&<button className="small" disabled={action.busy||action.blocked||Boolean(action.pending)} onClick={()=>{setTarget(l);setAmount("");setReason("");setReview(null);setMessage(null);}}>Refund</button>}</td></tr>)}{!filtered.length&&<tr><td colSpan={10}>{loading?"Loading…":error?"Records unavailable.":"No matching allocations."}</td></tr>}</tbody></table></div>
 <div className="wallet-pagination"><span>{filtered.length} records · Page {p} of {pages}</span><button className="secondary small" disabled={p<=1} onClick={()=>setPage(p-1)}>Previous</button><button className="secondary small" disabled={p>=pages} onClick={()=>setPage(p+1)}>Next</button></div>
 {target&&!action.pending&&<div className="transfer-review"><h3>Refund allocation {target.reference} · {target.line}</h3><p>Maximum refundable based on current records: {formatMoney(Math.max(0,target.amount-target.refunded-target.disputed))}. Server validation is authoritative.</p>{review?<><p>Refund {formatMoney(review.amount)} to the original payment destination, subject to backend approval.</p><p>{review.kind==="refund"?review.reason:""}</p><button disabled={action.busy} onClick={()=>submit(review)}>Confirm refund</button><button className="secondary" onClick={()=>setReview(null)}>Back</button></>:<form onSubmit={prepare}><label>Amount (GH₵)<input inputMode="decimal" required value={amount} onChange={e=>setAmount(e.target.value)}/></label><label>Reason<input required maxLength={500} value={reason} onChange={e=>setReason(e.target.value)}/></label><button type="submit">Review refund</button><button type="button" className="secondary" onClick={()=>setTarget(null)}>Cancel</button></form>}</div>}
 </section>;
}

function Rows({id,path,columns}:{id:string;path:string;columns:string[]}){
 const {data,error,loading,load}=useMerchantRows<Record<string,unknown>>(id,path);const [query,setQuery]=useState("");const rows=(data??[]).filter(r=>columns.some(c=>String(r[c]??"").toLowerCase().includes(query.trim().toLowerCase())));
 return <section className="panel"><div className="page-title"><div><h2>{path[0].toUpperCase()+path.slice(1)}</h2><p className="muted small">Records returned by ELEPAY; settlement and payout are separate lifecycle events.</p></div><button className="secondary" disabled={loading} onClick={()=>void load()}>Refresh</button></div>{error&&<p className="error" role="alert">{error}</p>}<div className="wallet-filter"><label>Search records<input value={query} onChange={e=>setQuery(e.target.value)}/></label><button className="secondary" disabled={!rows.length||loading} onClick={()=>exportRows("elepay_"+path,columns,rows)}>Export CSV</button></div><div className="table-scroll" tabIndex={0} role="region" aria-label={path}><table className="grid"><thead><tr>{columns.map(c=><th key={c}>{c.replace(/_/g," ").replace(/([a-z])([A-Z])/g,"$1 $2")}</th>)}</tr></thead><tbody>{rows.map((r,i)=><tr key={String(r.reference??r.id??i)+":"+i}>{columns.map(c=><td key={c} className={c==="amount"?"num":""}>{c==="amount"?typeof r[c]==="number"&&Number.isSafeInteger(r[c])?formatMoney(r[c] as number):"—":c==="status"?<span className={"tag "+merchantStatusClass(String(r[c]??""))}>{String(r[c]??"—")}</span>:["createdAt","created_at"].includes(c)?merchantDate(typeof r[c]==="string"?r[c]:null):String(r[c]??"—")}</td>)}</tr>)}{!rows.length&&<tr><td colSpan={columns.length}>{loading?"Loading…":error?"Records unavailable.":"No matching records."}</td></tr>}</tbody></table></div></section>;
}

function Payouts({id,enabled}:{id:string;enabled:boolean}){
 const payouts=useMerchantRows<Payout>(id,"payouts?limit=100");const destinations=useMerchantRows<Destination>(id,"settlement-destinations");
 const [amount,setAmount]=useState("");const [destination,setDestination]=useState("");const [review,setReview]=useState<MerchantAction|null>(null);const [message,setMessage]=useState<string|null>(null);
 const action=useMerchantAction(id,"withdraw");const d=destinations.data?.find(x=>x.id===destination);
 const recovered=action.pending&&payouts.data?.find(p=>p.clientReference==="withdrawal-"+action.pending?.key);
 function prepare(e:React.FormEvent){e.preventDefault();const n=parseMoney(amount);if(!d||!destinationReady(d)||!n||n<=0||!Number.isSafeInteger(n)){setMessage("Choose an eligible destination and valid positive amount.");return;}setMessage(null);setReview({kind:"withdraw",key:newKey(),amount:n,destinationId:d.id});}
 function submit(a:MerchantAction){if(a.kind!=="withdraw"||!enabled)return;void action.run(a,async v=>{if(v.kind!=="withdraw")throw new Error("Wrong action");return api<{reference?:string;statusCounts?:Record<string,number>;code?:string;detail?:string;items?:Payout[]}>(`/v1/merchants/${id}/withdrawals`,{idempotencyKey:v.key,body:{amount:v.amount,destinationId:v.destinationId}});},r=>{if(r?.code==="pending_approval"){setMessage("Pending independent operations approval. "+(r.detail??"Do not resubmit this withdrawal."));}else if(r?.reference&&r.statusCounts){setMessage("Withdrawal batch "+r.reference+" · "+Object.entries(r.statusCounts).map(([k,n])=>k+": "+n).join(", ")+". This is not proof of settlement.");}else throw new Error("Unverified withdrawal response");setReview(null);setAmount("");void payouts.load();});}
 return <><section className="panel"><div className="page-title"><h2>Withdraw to your business account</h2><button className="secondary" disabled={destinations.loading} onClick={()=>void destinations.load()}>Refresh destinations</button></div><p className="muted small">Only verified active destinations with completed security checks can be selected. The backend revalidates available funds and approval requirements.</p>{destinations.error&&<p className="error" role="alert">{destinations.error}</p>}{message&&<p className="notice" role="status">{message}</p>}{action.message&&<p className="notice warn" role="alert">{action.message}</p>}
 {action.blocked&&<p className="notice warn">Recovery storage unavailable. Contact support before creating a withdrawal.</p>}
 {action.pending?<div className="notice warn"><strong>Original withdrawal requires review</strong><p>{formatMoney(action.pending.amount)} · Request key <span className="mono">{action.pending.key}</span></p><p>Do not resubmit: an independent approval may already exist. Refresh payout history or contact operations with the request key.</p>{recovered&&<><p>Matching payout {recovered.reference} · {recovered.status}</p><button disabled={action.busy} onClick={()=>{action.clear();setReview(null);}}>Acknowledge matching payout</button></>}</div>:review&&review.kind==="withdraw"?<div className="transfer-review"><h3>Confirm withdrawal</h3><p>{formatMoney(review.amount)} to {d?.verifiedName??d?.accountName} · ending {d?.accountNumber.slice(-4)}</p><p>Large withdrawals may require independent approval. A request is not a completed payment.</p><button disabled={action.busy||!enabled||!d||!destinationReady(d)} onClick={()=>submit(review)}>Confirm withdrawal</button><button className="secondary" onClick={()=>setReview(null)}>Back</button></div>:<form onSubmit={prepare}><fieldset disabled={action.busy||action.blocked||!enabled||destinations.loading}><legend>Withdrawal details</legend><label>Destination<select required value={destination} onChange={e=>setDestination(e.target.value)}><option value="">Select eligible destination</option>{(destinations.data??[]).map(v=><option key={v.id} value={v.id} disabled={!destinationReady(v)}>{v.type} {v.network} · ending {v.accountNumber.slice(-4)} · {v.verifiedName??v.accountName} ({destinationReady(v)?"eligible":"unavailable"})</option>)}</select></label><label>Amount (GH₵)<input required inputMode="decimal" value={amount} onChange={e=>setAmount(e.target.value)} placeholder="50.00"/></label><button type="submit" disabled={!d||!destinationReady(d)}>Review withdrawal</button></fieldset></form>}</section>
 <section className="panel"><div className="page-title"><h2>Recent payout history</h2><button className="secondary" disabled={payouts.loading} onClick={()=>void payouts.load()}>Refresh payouts</button></div>{payouts.error&&<p className="error" role="alert">{payouts.error}</p>}<div className="table-scroll" tabIndex={0} role="region" aria-label="Payout history"><table className="grid"><thead><tr><th>Reference</th><th>Client reference</th><th>Created (GMT)</th><th className="num">Amount</th><th>Status</th><th>Reason</th></tr></thead><tbody>{(payouts.data??[]).map(p=><tr key={p.reference}><td>{p.reference}</td><td className="mono small">{p.clientReference}</td><td>{merchantDate(p.createdAt)}</td><td className="num">{formatMoney(p.amount)}</td><td><span className={"tag "+merchantStatusClass(p.status)}>{p.status}</span></td><td>{p.failureReason??"—"}</td></tr>)}{!payouts.data?.length&&<tr><td colSpan={6}>{payouts.loading?"Loading…":payouts.error?"Payouts unavailable.":"No recent payouts."}</td></tr>}</tbody></table></div></section></>;
}

function Collect({merchant}:{merchant:MerchantView}){
 const [amount,setAmount]=useState("");const [description,setDescription]=useState("");const [expiry,setExpiry]=useState(60);const [link,setLink]=useState<{url:string;reference:string;expiresAt:string}|null>(null);const [qr,setQr]=useState<string|null>(null);const [qrError,setQrError]=useState(false);const [msg,setMsg]=useState<string|null>(null);const action=useMerchantAction(merchant.id,"collect");
 useEffect(()=>{let active=true;api<{payload:string}>(`/v1/merchants/${merchant.id}/qr`).then(r=>QRCode.toDataURL(r.payload,{margin:2,width:220})).then(r=>{if(active)setQr(r);}).catch(()=>{if(active)setQrError(true);});return()=>{active=false;};},[merchant.id]);
 function submit(a:MerchantAction){void action.run(a,async v=>{if(v.kind!=="collect")throw new Error("Wrong action");return api<{payUrl:string;reference:string;expiresAt:string;status:string}>(`/v1/merchants/${merchant.id}/checkouts`,{idempotencyKey:v.key,body:{amount:v.amount,description:v.description,expiresInMinutes:v.expiresInMinutes}});},r=>{const url=safeRedirectUrl(r?.payUrl);if(!url||typeof r.reference!=="string"||!r.reference)throw new Error("Invalid checkout response");setLink({url,reference:r.reference,expiresAt:r.expiresAt});setMsg("Checkout created. Payment has not been received.");});}
 function create(e:React.FormEvent){e.preventDefault();const n=parseMoney(amount);if(!n||!Number.isSafeInteger(n)||n<=0||!Number.isInteger(expiry)||expiry<1||expiry>10080){setMsg("Enter a valid amount and link expiry.");return;}setMsg(null);setLink(null);submit({kind:"collect",key:newKey(),amount:n,description:description.trim(),expiresInMinutes:expiry});}
 return <div className="merchant-collect"><section className="panel"><h2>Request a payment</h2><p className="muted">Create a fixed-amount checkout. The customer approves separately.</p>{action.message&&<p className="notice warn" role="alert">{action.message}</p>}{action.blocked&&<p className="notice warn">Checkout recovery storage unavailable.</p>}{action.pending?<div className="notice warn"><p>Original request: {formatMoney(action.pending.amount)}</p><button disabled={action.busy||action.blocked} onClick={()=>action.pending&&submit(action.pending)}>Recover original checkout</button></div>:<form onSubmit={create}><fieldset disabled={action.busy||action.blocked}><legend>Payment details</legend><label>Amount (GH₵)<input required inputMode="decimal" value={amount} onChange={e=>setAmount(e.target.value)}/></label><label>Description<input maxLength={500} value={description} onChange={e=>setDescription(e.target.value)}/></label><label>Expires in<select value={expiry} onChange={e=>setExpiry(Number(e.target.value))}><option value={30}>30 minutes</option><option value={60}>1 hour</option><option value={1440}>24 hours</option><option value={10080}>7 days</option></select></label><button type="submit">Create payment link</button></fieldset></form>}
 {msg&&<p className="notice" role="status">{msg}</p>}{link&&<div className="receipt-detail"><p className="mono small">{link.reference}</p><p>Expires {merchantDate(link.expiresAt)} GMT</p><a href={link.url} target="_blank" rel="noreferrer">Open payment link</a><button className="secondary small" onClick={()=>void navigator.clipboard.writeText(link.url).then(()=>setMsg("Payment link copied.")).catch(()=>setMsg("Could not copy link. Open it to copy the URL."))}>Copy link</button></div>}</section><section className="panel"><h2>Merchant QR</h2><p>Code <strong>{merchant.merchantCode}</strong></p>{qr?<img src={qr} alt={"Merchant QR for "+merchant.merchantCode} width={220} height={220}/>:<p role="status">{qrError?"QR unavailable":"Generating merchant QR…"}</p>}<p className="small muted">Use the QR format provided by ELEPAY. Confirm payment status in your dashboard before releasing goods.</p></section></div>;
}
