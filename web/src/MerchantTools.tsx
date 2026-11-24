import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError, newKey } from "./api";
import { formatMoney, parseMoney } from "./money";
import {
  buildPosOrderPayload,
  computeCartTotals,
  decodeSavedPosOrder,
  exactBarcodeMatch,
  isPaidPosOrder,
  remainingPosBalance,
  SavedPosOrder,
  DiningTable as PosDiningTable,
  EvenSplitResult,
  formatKdsStatusLabel,
  HeldCart,
  isFeatureNotEnabled,
  KdsTicketSummary,
  ORDER_TYPES,
  OrderType,
  parseHeldCartPayload,
  PosCartLine,
  PosOrder,
  PosReceipt,
  PosTerminal,
  SeatSplitResult,
} from "./pos/posHelpers";
import { PosTerminalBar } from "./pos/PosTerminalBar";
import { PosTenderPanel } from "./pos/PosTenderPanel";

/*
 * Merchant tools backed by the ELEPAY server. Nothing here is sample data or kept only in the browser:
 * every list is loaded from the API and every action is saved there, or the screen shows the error.
 */

interface MerchantRef { id: string; tradingName: string }

function message(e: unknown): string {
  if (e instanceof ApiError) return e.message || `Request failed (${e.status})`;
  return e instanceof Error ? e.message : String(e);
}

function useLoad<T>(path: string | null, initial: T): [T, () => Promise<void>, string | null, boolean] {
  const [data, setData] = useState<T>(initial);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const load = useCallback(async () => {
    if (!path) return;
    setLoading(true);
    try { setData(await api<T>(path)); setError(null); } catch (e) { setError(message(e)); } finally { setLoading(false); }
  }, [path]);
  useEffect(() => {
    if (!path) { setLoading(false); return; }
    let cancelled = false;
    setLoading(true);
    api<T>(path)
      .then((res) => { if (!cancelled) { setData(res); setError(null); } })
      .catch((e) => { if (!cancelled) setError(message(e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [path]);
  return [data, load, error, loading];
}

function money(input: string): number | null { return parseMoney(input); }

// =============================================================================================== Inventory

export interface CatalogRow {
  variantId: string; productId: string; productName: string; categoryId: string | null; categoryName: string | null;
  sku: string; barcode: string | null; variantName: string | null; costPricePesewas: number; retailPricePesewas: number;
  trackInventory: boolean; onHand: number; reorderPoint: number;
}
interface Category { id: string; name: string }
interface Warehouse { id: string; code: string; name: string }

function itemLabel(r: CatalogRow) {
  return r.variantName && r.variantName !== "Standard" ? `${r.productName} (${r.variantName})` : r.productName;
}

/** The merchant's stock location; created once as "Main store" if the merchant has none yet. */
async function mainWarehouse(merchantId: string): Promise<Warehouse> {
  const list = await api<Warehouse[]>(`/v1/inventory/warehouses/merchant/${merchantId}`);
  if (list.length) return list[0];
  return api<Warehouse>("/v1/inventory/warehouses", { body: { merchantId, code: "MAIN", name: "Main store", address: null } });
}

export function InventoryAndStock({ merchantId }: { merchantId: string }) {
  const [rows, reload, loadError, loading] = useLoad<CatalogRow[]>(`/v1/inventory/catalog/merchant/${merchantId}`, []);
  const [categories, reloadCategories] = useLoad<Category[]>(`/v1/inventory/categories/merchant/${merchantId}`, []);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [adding, setAdding] = useState(false);
  const [form, setForm] = useState({ name: "", category: "", sku: "", barcode: "", cost: "", price: "", stock: "", reorder: "" });
  const [adjust, setAdjust] = useState<{ row: CatalogRow; qty: string; reason: "RECEIVED" | "DAMAGED" | "LOST" | "ADJUSTMENT" } | null>(null);
  const [busy, setBusy] = useState(false);

  const shown = rows.filter((r) => `${itemLabel(r)} ${r.sku} ${r.barcode ?? ""} ${r.categoryName ?? ""}`.toLowerCase().includes(search.toLowerCase()));
  const low = rows.filter((r) => r.trackInventory && Number(r.onHand) <= Number(r.reorderPoint));

  async function addProduct(e: React.FormEvent) {
    e.preventDefault(); setError(null); setNotice(null);
    const price = money(form.price), cost = form.cost ? money(form.cost) : 0;
    const stock = form.stock ? Number(form.stock) : 0, reorder = form.reorder ? Number(form.reorder) : 0;
    if (!price) { setError("Enter a selling price like 25.00"); return; }
    if (cost === null) { setError("Enter a cost price like 12.50, or leave it empty"); return; }
    if (!Number.isFinite(stock) || stock < 0 || !Number.isFinite(reorder) || reorder < 0) { setError("Stock and reorder level must be zero or more"); return; }
    setBusy(true);
    try {
      let categoryId: string | null = null;
      const catName = form.category.trim();
      if (catName) {
        const existing = categories.find((c) => c.name.toLowerCase() === catName.toLowerCase());
        categoryId = existing ? existing.id : (await api<Category>("/v1/inventory/categories", { body: { merchantId, name: catName } })).id;
      }
      const product = await api<{ id: string }>("/v1/inventory/products", { body: { merchantId, categoryId, name: form.name.trim(), description: null, brand: null } });
      const sku = form.sku.trim() || `SKU-${newKey().slice(0, 8).toUpperCase()}`;
      const variant = await api<{ id: string }>("/v1/inventory/variants", { body: { merchantId, productId: product.id, sku,
        barcode: form.barcode.trim() || null, variantName: "Standard", attributesJson: "{}", costPricePesewas: cost, retailPricePesewas: price, trackInventory: true } });
      const wh = await mainWarehouse(merchantId);
      if (stock > 0) await api("/v1/inventory/stock/adjust", { body: { warehouseId: wh.id, variantId: variant.id, quantityChange: stock,
        movementType: "RECEIVED", referenceId: null, notes: "Opening stock" } });
      await api("/v1/inventory/stock/reorder-point", { body: { warehouseId: wh.id, variantId: variant.id, reorderPoint: reorder } });
      setForm({ name: "", category: "", sku: "", barcode: "", cost: "", price: "", stock: "", reorder: "" });
      setAdding(false); setNotice(`${form.name.trim()} added.`);
      await Promise.all([reload(), reloadCategories()]);
    } catch (err) { setError(message(err)); } finally { setBusy(false); }
  }

  async function saveAdjustment(e: React.FormEvent) {
    e.preventDefault(); if (!adjust) return; setError(null);
    const qty = Number(adjust.qty);
    if (!Number.isFinite(qty) || qty <= 0) { setError("Enter a quantity greater than zero"); return; }
    const change = adjust.reason === "RECEIVED" ? qty : -qty;
    setBusy(true);
    try {
      const wh = await mainWarehouse(merchantId);
      await api("/v1/inventory/stock/adjust", { body: { warehouseId: wh.id, variantId: adjust.row.variantId,
        quantityChange: change, movementType: adjust.reason, referenceId: null, notes: null } });
      setNotice(`Stock updated for ${itemLabel(adjust.row)}.`); setAdjust(null); await reload();
    } catch (err) { setError(message(err)); } finally { setBusy(false); }
  }

  return (
    <div>
      <div className="flex-between">
        <h3>Inventory &amp; stock</h3>
        <button onClick={() => { setAdding(!adding); setError(null); }}>{adding ? "Close" : "Add product"}</button>
      </div>
      {loadError && <p className="error">{loadError}</p>}
      {error && <p className="error">{error}</p>}
      {notice && <p className="notice ok">{notice}</p>}
      {low.length > 0 && <p className="notice warn">Running low: {low.map((r) => `${itemLabel(r)} (${Number(r.onHand)} left)`).join(", ")}</p>}
      {adding && (
        <form className="panel" onSubmit={addProduct}>
          <div className="grid-2">
            <label>Product name<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required maxLength={128} /></label>
            <label>Category<input list="inv-categories" value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })} placeholder="e.g. Drinks" /></label>
            <label>Selling price (GH₵)<input inputMode="decimal" value={form.price} onChange={(e) => setForm({ ...form, price: e.target.value })} required /></label>
            <label>Cost price (GH₵)<input inputMode="decimal" value={form.cost} onChange={(e) => setForm({ ...form, cost: e.target.value })} /></label>
            <label>Opening stock<input inputMode="decimal" value={form.stock} onChange={(e) => setForm({ ...form, stock: e.target.value })} placeholder="0" /></label>
            <label>Warn me when stock is at or below<input inputMode="decimal" value={form.reorder} onChange={(e) => setForm({ ...form, reorder: e.target.value })} placeholder="0" /></label>
            <label>SKU (optional)<input value={form.sku} onChange={(e) => setForm({ ...form, sku: e.target.value })} maxLength={64} /></label>
            <label>Barcode (optional)<input value={form.barcode} onChange={(e) => setForm({ ...form, barcode: e.target.value })} maxLength={64} /></label>
          </div>
          <datalist id="inv-categories">{categories.map((c) => <option key={c.id} value={c.name} />)}</datalist>
          <button type="submit" disabled={busy}>{busy ? "Saving…" : "Save product"}</button>
        </form>
      )}
      <input placeholder="Search by name, SKU, barcode or category" value={search} onChange={(e) => setSearch(e.target.value)} style={{ margin: ".8rem 0" }} />
      {loading ? <p className="muted">Loading…</p> : rows.length === 0 ? <p className="muted">No products yet. Add your first product to start tracking stock and selling at the register.</p> : (
        <table className="grid">
          <thead><tr><th>Product</th><th>Category</th><th>SKU</th><th>Price</th><th>Cost</th><th>In stock</th><th /></tr></thead>
          <tbody>
            {shown.map((r) => (
              <tr key={r.variantId}>
                <td>{itemLabel(r)}</td>
                <td className="small">{r.categoryName ?? "—"}</td>
                <td className="small mono">{r.sku}</td>
                <td>{formatMoney(r.retailPricePesewas)}</td>
                <td className="small">{formatMoney(r.costPricePesewas)}</td>
                <td>{Number(r.onHand)}{r.trackInventory && Number(r.onHand) <= Number(r.reorderPoint) && <span className="tag warn" style={{ marginLeft: ".4rem" }}>low</span>}</td>
                <td><button className="secondary small" onClick={() => setAdjust({ row: r, qty: "", reason: "RECEIVED" })}>Update stock</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {adjust && (
        <form className="panel" onSubmit={saveAdjustment} style={{ marginTop: "1rem" }}>
          <h4>Update stock: {itemLabel(adjust.row)} (now {Number(adjust.row.onHand)})</h4>
          <label>What happened
            <select value={adjust.reason} onChange={(e) => setAdjust({ ...adjust, reason: e.target.value as typeof adjust.reason })}>
              <option value="RECEIVED">Received new stock (adds)</option>
              <option value="DAMAGED">Damaged (removes)</option>
              <option value="LOST">Lost or stolen (removes)</option>
              <option value="ADJUSTMENT">Count correction (removes)</option>
            </select>
          </label>
          <label>Quantity<input inputMode="decimal" value={adjust.qty} onChange={(e) => setAdjust({ ...adjust, qty: e.target.value })} required /></label>
          <button type="submit" disabled={busy}>Save</button>
          <button type="button" className="secondary" onClick={() => setAdjust(null)}>Cancel</button>
        </form>
      )}
    </div>
  );
}

// =============================================================================================== POS register

const TAX_KEY = (merchantId: string) => `elepay.pos.taxPercent.${merchantId}`;

export function PosRegister({ merchant }: { merchant: MerchantRef }) {
  const [catalog, reloadCatalog, loadError] = useLoad<CatalogRow[]>(`/v1/inventory/catalog/merchant/${merchant.id}`, []);
  const [tables] = useLoad<PosDiningTable[]>(`/v1/restaurant/tables/merchant/${merchant.id}`, []);
  const [moduleDisabled, setModuleDisabled] = useState(false);

  const [terminal, setTerminal] = useState<PosTerminal | null>(null);
  const [cashier, setCashier] = useState<{ id: string; badgeNumber: string; name?: string } | null>(null);

  const [search, setSearch] = useState("");
  const [cart, setCart] = useState<PosCartLine[]>([]);
  const [orderType, setOrderType] = useState<OrderType>("RETAIL");
  const [tableId, setTableId] = useState<string>("");
  const [discount, setDiscount] = useState("");
  const [taxPercent, setTaxPercent] = useState(() => { try { return localStorage.getItem(TAX_KEY(merchant.id)) ?? "0"; } catch { return "0"; } });

  const [order, setOrder] = useState<PosOrder | null>(null);
  const orderStorage = "elepay.pos.order." + merchant.id;
  const [savedOrder,setSavedOrder] = useState<{draft:SavedPosOrder|null;blocked:boolean}>(()=>{try{return {draft:decodeSavedPosOrder(sessionStorage.getItem(orderStorage),merchant.id),blocked:false};}catch{return {draft:null,blocked:true};}});
  const draftRef = useRef(savedOrder.draft);
  const creatingOrder = useRef<Promise<PosOrder>|null>(null);
  const [orderKey, setOrderKey] = useState<string>(() => newKey());
  const [receipt, setReceipt] = useState<{ receipt: PosReceipt; order: PosOrder } | null>(null);

  const [heldCarts, setHeldCarts] = useState<HeldCart[]>([]);
  const [holdCustomer, setHoldCustomer] = useState("");
  const [splitCount, setSplitCount] = useState("2");
  const [evenSplit, setEvenSplit] = useState<EvenSplitResult | null>(null);
  const [seatSplit, setSeatSplit] = useState<SeatSplitResult | null>(null);
  const [kdsTicket, setKdsTicket] = useState<KdsTicketSummary | null>(null);

  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const discountP = discount.trim() ? money(discount) : 0;
  const taxRate = Number(taxPercent);
  const taxValid = Number.isFinite(taxRate) && taxRate >= 0 && taxRate <= 100;
  const { subtotal, effectiveDiscount, tax, total } = computeCartTotals(cart, discountP, taxRate);
  const shown = catalog.filter((r) => `${itemLabel(r)} ${r.sku} ${r.barcode ?? ""}`.toLowerCase().includes(search.toLowerCase()));

  useEffect(() => { try { localStorage.setItem(TAX_KEY(merchant.id), taxPercent); } catch { /* ignore */ } }, [taxPercent, merchant.id]);

  const loadHeldCarts = useCallback(async (tid: string | null) => {
    if (!tid || moduleDisabled) {
      setHeldCarts([]);
      return;
    }
    try {
      setHeldCarts(await api<HeldCart[]>(`/v1/pos/carts/held/${tid}`));
    } catch (e) {
      if (isFeatureNotEnabled(e)) setModuleDisabled(true);
    }
  }, [moduleDisabled]);

  useEffect(() => {
    void loadHeldCarts(terminal?.id ?? null);
  }, [terminal?.id, loadHeldCarts]);

  const cartLocked = Boolean(order || savedOrder.draft || savedOrder.blocked || busy);
  const payableTotal = order ? remainingPosBalance(order) : total;
  const add = (row: CatalogRow) => { if(draftRef.current||savedOrder.blocked||order||busy)return; if(!Number.isSafeInteger(row.retailPricePesewas)||row.retailPricePesewas<0)return; if(row.trackInventory && Number(row.onHand)<=(cart.find(x=>x.variantId===row.variantId)?.qty??0)){setError("Insufficient available stock.");return;} setError(null);setCart((c) => c.some((x) => x.variantId === row.variantId)
    ? c.map((x) => x.variantId === row.variantId ? { ...x, qty: x.qty + 1 } : x)
    : [...c, { variantId: row.variantId, label: itemLabel(row), unitPricePesewas: row.retailPricePesewas, qty: 1, seatNumber: null }]); };

  const setQty = (id: string, qty: number) => { if(draftRef.current||order||busy||savedOrder.blocked||!Number.isSafeInteger(qty))return; const row=catalog.find(r=>r.variantId===id);if(row?.trackInventory&&qty>Number(row.onHand)){setError("Insufficient available stock.");return;}setCart((c) => qty <= 0
    ? c.filter((x) => x.variantId !== id)
    : c.map((x) => x.variantId === id ? { ...x, qty } : x)); };

  const setSeat = (id: string, seatNumber: number | null) => setCart((c) =>
    c.map((x) => x.variantId === id ? { ...x, seatNumber } : x));

  function persistOrder(draft:SavedPosOrder){
    sessionStorage.setItem(orderStorage,JSON.stringify(draft));draftRef.current=draft;setSavedOrder({draft,blocked:false});
  }
  function ensureOrder():Promise<PosOrder>{
    if(order)return Promise.resolve(order);
    if(creatingOrder.current)return creatingOrder.current;
    const task=(async()=>{
      if(savedOrder.blocked)throw new Error("Saved order recovery is unavailable.");
      let draft=draftRef.current;
      if(!draft){
        if(discountP===null||!taxValid||!cart.length||!Number.isSafeInteger(total)||total<=0)throw new Error("Review valid cart amounts before creating an order.");
        if(!terminal||terminal.status!=="ACTIVE"||!cashier)throw new Error("Select an active paired terminal and authenticate a cashier.");
        if(cart.some(l=>!Number.isSafeInteger(l.qty)||l.qty<=0||!Number.isSafeInteger(l.unitPricePesewas)||l.unitPricePesewas<0))throw new Error("Invalid cart line.");
        const payload=buildPosOrderPayload({merchantId:merchant.id,branchId:terminal.branchId,terminalId:terminal.id,cashierId:cashier.id,orderType,tableId:tableId||null,lines:cart,discountPesewas:effectiveDiscount,taxPesewas:tax});
        draft={key:orderKey,payload,orderId:null};persistOrder(draft);
      }
      try{
        const created=draft.orderId?await api<PosOrder>("/v1/pos/orders/"+draft.orderId,{signal:AbortSignal.timeout(20000)}):await api<PosOrder>("/v1/pos/orders",{body:draft.payload,idempotencyKey:draft.key,signal:AbortSignal.timeout(20000)});
        if(!created||!created.id||(draft.orderId&&created.id!==draft.orderId))throw new Error("Order response could not be verified.");
        remainingPosBalance(created);
        persistOrder({...draft,orderId:created.id});setOrder(created);
        if(!cart.length){setCart(draft.payload.items.map(l=>({variantId:l.variantId,label:l.itemName,unitPricePesewas:l.unitPricePesewas,qty:l.quantity,seatNumber:l.seatNumber})));setOrderType(draft.payload.orderType);setTableId(draft.payload.tableId??"");}
        return created;
      }catch(e){if(isFeatureNotEnabled(e))setModuleDisabled(true);throw e;}
    })();
    creatingOrder.current=task;void task.finally(()=>{creatingOrder.current=null;}).catch(()=>{});return task;
  }

  async function sendToKitchen(targetOrder: PosOrder) {
    if (kdsTicket || orderType === "RETAIL") return;
    try {
      const selectedTable = tables.find((t) => t.id === tableId);
      const created = await api<KdsTicketSummary>("/v1/restaurant/kds/tickets", {
        body: {
          merchantId: merchant.id,
          orderId: targetOrder.id,
          orderNumber: targetOrder.orderNumber,
          tableNumber: selectedTable?.tableNumber ?? null,
          serverName: cashier?.name ?? cashier?.badgeNumber ?? null,
          course: "MAIN",
          items: cart.map((c) => ({
            orderItemId: c.variantId,
            itemName: c.label,
            quantity: c.qty,
            modifiersJson: "[]",
            notes: c.seatNumber ? `Seat ${c.seatNumber}` : null,
          })),
          notes: orderType,
          stationId: null,
        },
      });
      setKdsTicket(created);
    } catch {
      // KDS routing is non-blocking if restaurant stations are not yet configured
    }
  }

  async function finish(paidOrder: PosOrder) {
    if(!isPaidPosOrder(paidOrder))throw new Error("Order is not fully paid.");
    const latest = await api<PosOrder>(`/v1/pos/orders/${paidOrder.id}`);
    if(latest.id!==paidOrder.id||!isPaidPosOrder(latest))throw new Error("Payment not verified by POS server.");
    const r = await api<PosReceipt>(`/v1/pos/orders/${paidOrder.id}/receipt`, { method: "POST" });
    if(!r?.receiptNumber||typeof r.escposFormattedText!=="string")throw new Error("Receipt unavailable; recover the paid order.");
    if (orderType !== "RETAIL" && !kdsTicket) {
      await sendToKitchen(latest);
    }
    sessionStorage.removeItem(orderStorage); draftRef.current=null;setSavedOrder({draft:null,blocked:false});
    setReceipt({ receipt: r, order: latest });
    setCart([]);
    setDiscount("");
    setOrder(null);
    setOrderKey(newKey());
    setEvenSplit(null);
    setSeatSplit(null);
    void reloadCatalog();
  }

  async function holdCurrentCart() {
    if(cartLocked)return;
    if (!terminal || !cashier) {
      setError("Select a terminal and authenticate a cashier before holding a cart.");
      return;
    }
    if (cart.length === 0) return;
    setError(null);
    setBusy(true);
    try {
      const payloadJson = JSON.stringify({
        items: cart,
        discount,
        taxPercent,
        orderType,
        tableId: tableId || null,
      });
      await api<HeldCart>("/v1/pos/carts/hold", {
        body: {
          terminalId: terminal.id,
          cashierId: cashier.id,
          merchantId: merchant.id,
          customerName: holdCustomer.trim() || null,
          cartPayloadJson: payloadJson,
          totalPesewas: total,
        },
      });
      setCart([]);
      setDiscount("");
      setHoldCustomer("");
      await loadHeldCarts(terminal.id);
    } catch (e) {
      if (isFeatureNotEnabled(e)) { setModuleDisabled(true); return; }
      setError(`Could not hold cart: ${message(e)}`);
    } finally {
      setBusy(false);
    }
  }

  async function recallCart(hc: HeldCart) {
    if(cartLocked||cart.length){setError("Clear the unsubmitted cart before recalling another.");return;}
    setError(null);
    setBusy(true);
    try {
      const recalled = await api<HeldCart>(`/v1/pos/carts/held/${hc.id}/recall`, { method: "POST" });
      const parsed = parseHeldCartPayload(recalled.cartPayloadJson);
      if (parsed) {
        setCart(parsed.items);
        setDiscount(parsed.discount);
        setTaxPercent(parsed.taxPercent);
        setOrderType(parsed.orderType);
        setTableId(parsed.tableId ?? "");
        setOrder(null);
        setOrderKey(newKey());
      } else {
        setError("Held cart payload could not be parsed.");
      }
      await loadHeldCarts(terminal?.id ?? null);
    } catch (e) {
      if (isFeatureNotEnabled(e)) { setModuleDisabled(true); return; }
      setError(`Could not recall cart: ${message(e)}`);
    } finally {
      setBusy(false);
    }
  }

  async function runEvenSplit() {
    setError(null);
    const count = Number(splitCount);
    if (!Number.isInteger(count) || count < 2) {
      setError("Split count must be at least 2.");
      return;
    }
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setSeatSplit(null);
      setEvenSplit(await api<EvenSplitResult>(`/v1/pos/orders/${ord.id}/split/even?count=${count}`, { method: "POST" }));
    } catch (e) {
      if (isFeatureNotEnabled(e)) { setModuleDisabled(true); return; }
      setError(`Split failed: ${message(e)}`);
    } finally {
      setBusy(false);
    }
  }

  async function runSeatSplit() {
    setError(null);
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setEvenSplit(null);
      setSeatSplit(await api<SeatSplitResult>(`/v1/pos/orders/${ord.id}/split/seats`, { method: "POST" }));
    } catch (e) {
      if (isFeatureNotEnabled(e)) { setModuleDisabled(true); return; }
      setError(`Seat split failed: ${message(e)}`);
    } finally {
      setBusy(false);
    }
  }

  if (moduleDisabled) {
    return (
      <div className="panel">
        <h3>POS Register</h3>
        <p className="notice warn">POS module is disabled in this environment (Stage 1).</p>
      </div>
    );
  }

  if (receipt) {
    return (
      <div className="panel">
        <h3>Sale complete</h3>
        <p>Order {receipt.order.orderNumber} · {formatMoney(receipt.order.totalPesewas)}
          {receipt.order.changePesewas > 0 && <> · <strong>Change {formatMoney(receipt.order.changePesewas)}</strong></>}
          {kdsTicket && <> · <span className="tag active">{formatKdsStatusLabel(kdsTicket.status)}</span></>}</p>
        <pre className="receipt">{receipt.receipt.escposFormattedText}</pre><button className="secondary" onClick={()=>{const url=URL.createObjectURL(new Blob([receipt.receipt.escposFormattedText],{type:"text/plain;charset=utf-8"}));const a=document.createElement("a");a.href=url;a.download="elepay_receipt_"+receipt.receipt.receiptNumber+".txt";document.body.appendChild(a);a.click();a.remove();URL.revokeObjectURL(url);}}>Download receipt</button>
        <button onClick={() => { setReceipt(null); setKdsTicket(null); }}>New sale</button>
      </div>
    );
  }

  return (
    <div>
      <div className="page-title"><div><p className="eyebrow">ELEPAY POS</p><h2>Cashier register</h2><p className="muted small">Scan products, review the order, collect and verify payment.</p></div></div>
      {savedOrder.blocked&&<p className="notice warn" role="alert">Saved order data is unavailable. Contact support before accepting another sale.</p>}
      {savedOrder.draft&&!order&&<div className="notice warn"><p>A saved order must be recovered before a new sale.</p><button disabled={busy} onClick={()=>{setBusy(true);void ensureOrder().catch(e=>setError(message(e))).finally(()=>setBusy(false));}}>Recover original order</button></div>}
      <PosTerminalBar
        merchantId={merchant.id}
        locked={Boolean(savedOrder.draft||order||busy)}
        terminalId={terminal?.id ?? null}
        cashierId={cashier?.id ?? null}
        onSelectTerminal={setTerminal}
        onSelectCashier={setCashier}
        onModuleDisabled={() => setModuleDisabled(true)}
      />

      <div className="pos-layout">
        <div>
          <h3>Products</h3>
          {loadError && <p className="error">{loadError}</p>}
          <label>Search or scan product barcode<input disabled={cartLocked} placeholder="Barcode, SKU or product name" value={search} onChange={e=>setSearch(e.target.value)} onKeyDown={e=>{if(e.key==="Enter"){e.preventDefault();const hit=exactBarcodeMatch(catalog,search);if(hit){add(hit);setSearch("");}else setError("No unique barcode or SKU match. Select the product manually.");}}}/></label>
          <p className="small muted">Use a keyboard-compatible barcode scanner or type the barcode. This scans products, not card credentials.</p>
          {catalog.length === 0 && !loadError && <p className="muted">No products yet. Add them in <strong>Inventory &amp; Stock</strong>.</p>}
          <div className="pos-items">
            {shown.map((r) => (
              <button key={r.variantId} className="secondary pos-item" disabled={cartLocked} onClick={() => add(r)}>
                <span>{itemLabel(r)}</span><strong>{formatMoney(r.retailPricePesewas)}</strong>
                {r.trackInventory && <span className="small muted">{Number(r.onHand)} in stock</span>}
              </button>
            ))}
          </div>

          {heldCarts.length > 0 && (
            <div className="panel" style={{ marginTop: "1rem" }}>
              <h4>Held Carts ({heldCarts.length})</h4>
              <table className="grid">
                <tbody>
                  {heldCarts.map((hc) => (
                    <tr key={hc.id}>
                      <td>{hc.customerName ?? "Walk-in"}</td>
                      <td>{formatMoney(hc.totalPesewas)}</td>
                      <td>
                        <button type="button" className="secondary small" disabled={cartLocked || cart.length>0} onClick={() => void recallCart(hc)}>
                          Recall
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div className="panel">
          <div className="flex-between">
            <h3>Sale</h3>
            {kdsTicket && <span className="tag active">{formatKdsStatusLabel(kdsTicket.status)}</span>}
          </div>

          <div className="grid-2" style={{ marginBottom: ".6rem" }}>
            <label>Order Type
              <select value={orderType} disabled={cartLocked} onChange={(e) => setOrderType(e.target.value as OrderType)}>
                {ORDER_TYPES.map((ot) => <option key={ot.value} value={ot.value}>{ot.label}</option>)}
              </select>
            </label>
            {orderType === "DINE_IN" && (
              <label>Table
                <select value={tableId} disabled={cartLocked} onChange={(e) => setTableId(e.target.value)}>
                  <option value="">-- Select table --</option>
                  {tables.map((t) => (
                    <option key={t.id} value={t.id}>
                      Table {t.tableNumber} ({t.capacity} seats · {t.status})
                    </option>
                  ))}
                </select>
              </label>
            )}
          </div>

          {cart.length === 0 ? <p className="muted">Tap products to add them.</p> : (
            <table className="grid">
              <tbody>
                {cart.map((c) => (
                  <tr key={c.variantId}>
                    <td>
                      {c.label}
                      {orderType === "DINE_IN" && (
                        <input
                          type="number"
                          min={1}
                          placeholder="Seat #"
                          value={c.seatNumber ?? ""}
                          disabled={cartLocked}
                          onChange={(e) => setSeat(c.variantId, e.target.value ? Number(e.target.value) : null)}
                          style={{ width: "4.5rem", marginLeft: ".4rem" }}
                        />
                      )}
                    </td>
                    <td style={{ whiteSpace: "nowrap" }}>
                      <button className="secondary small" disabled={cartLocked} onClick={() => setQty(c.variantId, c.qty - 1)}>−</button>
                      {c.qty}
                      <button className="secondary small" disabled={cartLocked} onClick={() => setQty(c.variantId, c.qty + 1)}>+</button>
                    </td>
                    <td>{formatMoney(c.unitPricePesewas * c.qty)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <div className="grid-2">
            <label>Discount (GH₵)<input inputMode="decimal" value={discount} disabled={cartLocked} onChange={(e) => setDiscount(e.target.value)} placeholder="0.00" /></label>
            <label>Tax %<input inputMode="decimal" value={taxPercent} disabled={cartLocked} onChange={(e) => setTaxPercent(e.target.value)} /></label>
          </div>
          <dl className="confirm">
            <dt>Subtotal</dt><dd>{formatMoney(subtotal)}</dd>
            {tax > 0 && <><dt>Tax</dt><dd>{formatMoney(tax)}</dd></>}
            <dt><strong>{order?"Server order total":"Estimated total"}</strong></dt><dd><strong>{formatMoney(order?.totalPesewas??total)}</strong></dd>{order&&<><dt>Outstanding</dt><dd>{formatMoney(payableTotal)}</dd></>}
          </dl>

          {error && <p className="error" role="alert">{error}</p>}

          {cart.length > 0 && !cartLocked && (
            <div className="flex-row" style={{ marginBottom: ".6rem", flexWrap: "wrap" }}>
              <input
                placeholder="Hold name / table"
                value={holdCustomer}
                onChange={(e) => setHoldCustomer(e.target.value)}
                style={{ width: "auto", flex: "1 1 120px" }}
              />
              <button type="button" className="secondary small" disabled={busy} onClick={() => void holdCurrentCart()}>
                Hold Cart
              </button>
              {orderType !== "RETAIL" && (
                <button
                  type="button"
                  className="secondary small"
                  disabled={busy}
                  onClick={() => void ensureOrder().then(sendToKitchen).catch((e) => setError(message(e)))}
                >
                  Send to Kitchen
                </button>
              )}
            </div>
          )}

          {cart.length > 0 && !savedOrder.blocked && !savedOrder.draft && (
            <details style={{ marginBottom: ".6rem" }}>
              <summary className="small muted">Split Bill</summary>
              <div className="flex-row" style={{ marginTop: ".4rem", flexWrap: "wrap" }}>
                <input
                  type="number"
                  min={2}
                  value={splitCount}
                  onChange={(e) => setSplitCount(e.target.value)}
                  style={{ width: "4rem" }}
                />
                <button type="button" className="secondary small" disabled={busy} onClick={() => void runEvenSplit()}>
                  Split Evenly
                </button>
                <button type="button" className="secondary small" disabled={busy} onClick={() => void runSeatSplit()}>
                  Split by Seat
                </button>
              </div>
              {evenSplit && (
                <ul className="small" style={{ marginTop: ".4rem" }}>
                  {evenSplit.portions.map((p) => (
                    <li key={p.portionIndex}>Portion {p.portionIndex}: <strong>{formatMoney(p.amountPesewas)}</strong></li>
                  ))}
                </ul>
              )}
              {seatSplit && (
                <ul className="small" style={{ marginTop: ".4rem" }}>
                  {seatSplit.seatGroups.map((g) => (
                    <li key={g.seatNumber}>Seat {g.seatNumber}: <strong>{formatMoney(g.subtotalPesewas)}</strong> ({g.items.length} items)</li>
                  ))}
                </ul>
              )}
            </details>
          )}

          {(cart.length > 0 || order) && !savedOrder.blocked && (
            <PosTenderPanel
              merchantId={merchant.id}
              terminalId={terminal?.id ?? null}
              totalPesewas={payableTotal}
              order={order}
              ensureOrder={ensureOrder}
              onOrderPaid={finish}
              onModuleDisabled={() => setModuleDisabled(true)}
              onClearCart={() => { if(!draftRef.current&&!order){setCart([]);setOrderKey(newKey());} }}
              hasOrder={Boolean(order||savedOrder.draft)}
            />
          )}
        </div>
      </div>
    </div>
  );
}

// =============================================================================================== Floor plan

interface FloorPlan { id: string; name: string }
interface DiningTable { id: string; sectionId: string; tableNumber: string; capacity: number; status: "AVAILABLE" | "OCCUPIED" | "RESERVED" | "DIRTY" }

export function FloorPlanView({ merchantId }: { merchantId: string }) {
  const [plans, reloadPlans, plansError] = useLoad<FloorPlan[]>(`/v1/restaurant/floor-plans/merchant/${merchantId}`, []);
  const [tables, reloadTables, tablesError] = useLoad<DiningTable[]>(`/v1/restaurant/tables/merchant/${merchantId}`, []);
  const [error, setError] = useState<string | null>(null);
  const [section, setSection] = useState("");
  const [table, setTable] = useState({ number: "", capacity: "4", sectionId: "" });

  async function addSection(e: React.FormEvent) {
    e.preventDefault(); setError(null);
    try { await api("/v1/restaurant/floor-plans", { body: { merchantId, name: section.trim(), layoutJson: "{}" } }); setSection(""); await reloadPlans(); }
    catch (err) { setError(message(err)); }
  }
  async function addTable(e: React.FormEvent) {
    e.preventDefault(); setError(null);
    const capacity = Number(table.capacity);
    const sectionId = table.sectionId || plans[0]?.id;
    if (!sectionId) { setError("Add a section (e.g. Terrace) first"); return; }
    if (!Number.isInteger(capacity) || capacity < 1) { setError("Seats must be a whole number"); return; }
    try { await api("/v1/restaurant/tables", { body: { merchantId, sectionId, tableNumber: table.number.trim(), capacity } }); setTable({ ...table, number: "" }); await reloadTables(); }
    catch (err) { setError(message(err)); }
  }
  async function setStatus(t: DiningTable, status: DiningTable["status"]) {
    setError(null);
    try {
      if (status === "AVAILABLE" && t.status !== "RESERVED") await api(`/v1/restaurant/tables/${t.id}/vacate?markDirty=false`, { method: "POST" });
      else await api(`/v1/restaurant/tables/${t.id}/status?status=${status}`, { method: "POST" });
      await reloadTables();
    } catch (err) { setError(message(err)); }
  }

  return (
    <div>
      {(plansError || tablesError || error) && <p className="error">{plansError ?? tablesError ?? error}</p>}
      {plans.length === 0 && <p className="muted">No floor plan yet. Add your sections (e.g. Inside, Terrace) and then your tables.</p>}
      {plans.map((p) => {
        const mine = tables.filter((t) => t.sectionId === p.id);
        return (
          <div key={p.id} style={{ marginBottom: "1rem" }}>
            <h4>{p.name}</h4>
            {mine.length === 0 ? <p className="muted small">No tables in this section.</p> : (
              <div className="table-grid">
                {mine.map((t) => (
                  <div key={t.id} className={`dining-table ${t.status.toLowerCase()}`}>
                    <strong>{t.tableNumber}</strong>
                    <span className="small">{t.capacity} seats · {t.status.toLowerCase()}</span>
                    <select value={t.status} onChange={(e) => void setStatus(t, e.target.value as DiningTable["status"])} aria-label={`Status of ${t.tableNumber}`}>
                      <option value="AVAILABLE">Available</option><option value="OCCUPIED">Occupied</option>
                      <option value="RESERVED">Reserved</option><option value="DIRTY">Needs cleaning</option>
                    </select>
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })}
      <div className="grid-2">
        <form className="panel" onSubmit={addSection}>
          <h4>Add section</h4>
          <label>Name<input value={section} onChange={(e) => setSection(e.target.value)} placeholder="Inside, Terrace…" required maxLength={64} /></label>
          <button type="submit">Add section</button>
        </form>
        <form className="panel" onSubmit={addTable}>
          <h4>Add table</h4>
          <label>Table name<input value={table.number} onChange={(e) => setTable({ ...table, number: e.target.value })} placeholder="T1" required maxLength={32} /></label>
          <label>Seats<input inputMode="numeric" value={table.capacity} onChange={(e) => setTable({ ...table, capacity: e.target.value })} required /></label>
          {plans.length > 1 && (
            <label>Section<select value={table.sectionId} onChange={(e) => setTable({ ...table, sectionId: e.target.value })}>
              {plans.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select></label>
          )}
          <button type="submit" disabled={plans.length === 0}>Add table</button>
        </form>
      </div>
    </div>
  );
}

// =============================================================================================== Invoices & quotes

interface Invoice { id: string; invoiceNumber: string; customerName: string; issueDate: string; dueDate: string; totalPesewas: number; paidAmountPesewas: number; status: string }
interface Quote { id: string; quoteNumber: string; customerName: string; issueDate: string; expiryDate: string; totalPesewas: number; status: string; convertedInvoiceId: string | null }
interface DocLine { description: string; quantity: string; price: string; tax: string }

export function InvoicesAndQuotes({ merchantId }: { merchantId: string }) {
  const [invoices, reloadInvoices, invError] = useLoad<Invoice[]>(`/v1/invoicing/invoices/merchant/${merchantId}`, []);
  const [quotes, reloadQuotes, qError] = useLoad<Quote[]>(`/v1/invoicing/quotes/merchant/${merchantId}`, []);
  const [creating, setCreating] = useState<"invoice" | "quote" | null>(null);
  const [customer, setCustomer] = useState({ name: "", phone: "", email: "", due: "" });
  const [lines, setLines] = useState<DocLine[]>([{ description: "", quantity: "1", price: "", tax: "0" }]);
  const [payment, setPayment] = useState<{ invoice: Invoice; amount: string; method: "CASH" | "BANK" | "MOMO" } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function create(e: React.FormEvent) {
    e.preventDefault(); setError(null);
    const items = [];
    for (const l of lines) {
      const price = money(l.price), qty = Number(l.quantity), tax = Number(l.tax);
      if (!l.description.trim() || !price || !(qty > 0) || !(tax >= 0 && tax <= 100)) { setError("Each line needs a description, quantity, price and a tax % from 0 to 100"); return; }
      items.push({ description: l.description.trim(), quantity: qty, unitPricePesewas: price, taxRateBps: Math.round(tax * 100), discountPesewas: 0 });
    }
    setBusy(true);
    try {
      await api(creating === "invoice" ? "/v1/invoicing/invoices" : "/v1/invoicing/quotes", { body: { merchantId, customerName: customer.name,
        customerPhone: customer.phone || null, customerEmail: customer.email || null, customerTin: null, issueDate: null, dueDate: customer.due || null, items, notes: null } });
      setCreating(null); setCustomer({ name: "", phone: "", email: "", due: "" }); setLines([{ description: "", quantity: "1", price: "", tax: "0" }]);
      await Promise.all([reloadInvoices(), reloadQuotes()]);
    } catch (err) { setError(message(err)); } finally { setBusy(false); }
  }

  async function convert(q: Quote) {
    setError(null);
    try { await api(`/v1/invoicing/quotes/${q.id}/convert`, { body: {} }); await Promise.all([reloadInvoices(), reloadQuotes()]); }
    catch (err) { setError(message(err)); }
  }

  async function recordPayment(e: React.FormEvent) {
    e.preventDefault(); if (!payment) return; setError(null);
    const amount = money(payment.amount);
    if (!amount) { setError("Enter the amount received"); return; }
    try { await api(`/v1/invoicing/invoices/${payment.invoice.id}/payments`, { body: { amountPesewas: amount, method: payment.method } }); setPayment(null); await reloadInvoices(); }
    catch (err) { setError(message(err)); }
  }

  const statusTag = (s: string) => s === "PAID" ? "active" : s === "PARTIALLY_PAID" || s === "ISSUED" || s === "DRAFT" ? "pending" : s === "CONVERTED" ? "info" : "failed";

  return (
    <div>
      <div className="flex-row">
        <button onClick={() => setCreating("invoice")}>New invoice</button>
        <button className="secondary" onClick={() => setCreating("quote")}>New quote</button>
      </div>
      {(invError || qError || error) && <p className="error">{invError ?? qError ?? error}</p>}
      {creating && (
        <form className="panel" onSubmit={create} style={{ marginTop: "1rem" }}>
          <h4>{creating === "invoice" ? "New invoice" : "New quote"}</h4>
          <div className="grid-2">
            <label>Customer name<input value={customer.name} onChange={(e) => setCustomer({ ...customer, name: e.target.value })} required /></label>
            <label>Phone<input value={customer.phone} onChange={(e) => setCustomer({ ...customer, phone: e.target.value })} /></label>
            <label>Email<input type="email" value={customer.email} onChange={(e) => setCustomer({ ...customer, email: e.target.value })} /></label>
            <label>{creating === "invoice" ? "Due date" : "Valid until"}<input type="date" value={customer.due} onChange={(e) => setCustomer({ ...customer, due: e.target.value })} /></label>
          </div>
          {lines.map((l, i) => (
            <div key={i} className="doc-line">
              <input aria-label="Description" placeholder="Description" value={l.description} onChange={(e) => setLines(lines.map((x, j) => j === i ? { ...x, description: e.target.value } : x))} />
              <input aria-label="Quantity" inputMode="decimal" value={l.quantity} onChange={(e) => setLines(lines.map((x, j) => j === i ? { ...x, quantity: e.target.value } : x))} />
              <input aria-label="Unit price GH₵" placeholder="Price" inputMode="decimal" value={l.price} onChange={(e) => setLines(lines.map((x, j) => j === i ? { ...x, price: e.target.value } : x))} />
              <input aria-label="Tax %" inputMode="decimal" value={l.tax} onChange={(e) => setLines(lines.map((x, j) => j === i ? { ...x, tax: e.target.value } : x))} />
              {lines.length > 1 && <button type="button" className="secondary small" onClick={() => setLines(lines.filter((_, j) => j !== i))}>Remove</button>}
            </div>
          ))}
          <p className="small muted">Columns: description · quantity · unit price (GH₵) · tax %</p>
          <button type="button" className="secondary" onClick={() => setLines([...lines, { description: "", quantity: "1", price: "", tax: "0" }])}>Add line</button>
          <button type="submit" disabled={busy}>Save</button>
          <button type="button" className="secondary" onClick={() => setCreating(null)}>Cancel</button>
        </form>
      )}
      <h3>Invoices</h3>
      {invoices.length === 0 ? <p className="muted">No invoices yet.</p> : (
        <table className="grid">
          <thead><tr><th>Number</th><th>Customer</th><th>Due</th><th>Total</th><th>Paid</th><th>Status</th><th /></tr></thead>
          <tbody>{invoices.map((inv) => (
            <tr key={inv.id}>
              <td className="mono">{inv.invoiceNumber}</td><td>{inv.customerName}</td><td className="small">{inv.dueDate}</td>
              <td>{formatMoney(inv.totalPesewas)}</td><td>{formatMoney(inv.paidAmountPesewas)}</td>
              <td><span className={`tag ${statusTag(inv.status)}`}>{inv.status.replace("_", " ").toLowerCase()}</span></td>
              <td>{inv.status !== "PAID" && inv.status !== "VOID" && <button className="secondary small" onClick={() => setPayment({ invoice: inv, amount: ((inv.totalPesewas - inv.paidAmountPesewas) / 100).toFixed(2), method: "CASH" })}>Record payment</button>}</td>
            </tr>))}</tbody>
        </table>
      )}
      {payment && (
        <form className="panel" onSubmit={recordPayment} style={{ marginTop: "1rem" }}>
          <h4>Payment received for {payment.invoice.invoiceNumber}</h4>
          <p className="small muted">Record money you already received. ELEPAY does not collect invoice payments for you yet.</p>
          <label>Amount (GH₵)<input inputMode="decimal" value={payment.amount} onChange={(e) => setPayment({ ...payment, amount: e.target.value })} /></label>
          <label>Received by<select value={payment.method} onChange={(e) => setPayment({ ...payment, method: e.target.value as "CASH" | "BANK" | "MOMO" })}>
            <option value="CASH">Cash</option><option value="BANK">Bank transfer</option><option value="MOMO">Mobile money</option></select></label>
          <button type="submit">Save payment</button>
          <button type="button" className="secondary" onClick={() => setPayment(null)}>Cancel</button>
        </form>
      )}
      <h3>Quotes</h3>
      {quotes.length === 0 ? <p className="muted">No quotes yet.</p> : (
        <table className="grid">
          <thead><tr><th>Number</th><th>Customer</th><th>Valid until</th><th>Total</th><th>Status</th><th /></tr></thead>
          <tbody>{quotes.map((q) => (
            <tr key={q.id}>
              <td className="mono">{q.quoteNumber}</td><td>{q.customerName}</td><td className="small">{q.expiryDate}</td>
              <td>{formatMoney(q.totalPesewas)}</td><td><span className={`tag ${statusTag(q.status)}`}>{q.status.toLowerCase()}</span></td>
              <td>{!q.convertedInvoiceId && q.status !== "CONVERTED" && <button className="secondary small" onClick={() => void convert(q)}>Turn into invoice</button>}</td>
            </tr>))}</tbody>
        </table>
      )}
    </div>
  );
}

// =============================================================================================== Customers & loyalty

interface Customer { id: string; customerCode: string; fullName: string; phone: string; email: string | null; totalSpendPesewas: number;
  transactionCount: number; rfmSegment: string; loyaltyTier: string; pointsBalance: number; lastPurchaseAt: string | null }

export function CustomersAndLoyalty({ merchantId }: { merchantId: string }) {
  const [customers, reload, loadError] = useLoad<Customer[]>(`/v1/customers/merchant/${merchantId}`, []);
  const [form, setForm] = useState({ name: "", phone: "", email: "" });
  const [adding, setAdding] = useState(false);
  const [action, setAction] = useState<{ customer: Customer; kind: "purchase" | "redeem"; value: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const shown = customers.filter((c) => `${c.fullName} ${c.phone} ${c.customerCode}`.toLowerCase().includes(search.toLowerCase()));

  async function add(e: React.FormEvent) {
    e.preventDefault(); setError(null);
    try { await api("/v1/customers", { body: { merchantId, fullName: form.name, phone: form.phone, email: form.email || null, address: null } });
      setForm({ name: "", phone: "", email: "" }); setAdding(false); await reload(); }
    catch (err) { setError(message(err)); }
  }

  async function act(e: React.FormEvent) {
    e.preventDefault(); if (!action) return; setError(null); setNotice(null);
    try {
      if (action.kind === "purchase") {
        const amount = money(action.value);
        if (!amount) { setError("Enter the purchase amount"); return; }
        const r = await api<{ pointsDelta: number; newPointsBalance: number }>(`/v1/customers/${action.customer.id}/purchases`, { body: { amountPesewas: amount } });
        setNotice(`${action.customer.fullName} earned ${r.pointsDelta} points (now ${r.newPointsBalance}).`);
      } else {
        const points = Number(action.value);
        if (!Number.isInteger(points) || points <= 0) { setError("Enter whole points to redeem"); return; }
        const r = await api<{ newPointsBalance: number; monetaryEquivalentPesewas: number }>(`/v1/customers/${action.customer.id}/redeem`, { body: { points } });
        setNotice(`Redeemed ${points} points (worth ${formatMoney(r.monetaryEquivalentPesewas)} off). ${r.newPointsBalance} points left.`);
      }
      setAction(null); await reload();
    } catch (err) { setError(message(err)); }
  }

  return (
    <div>
      <div className="flex-between">
        <h3>Customers &amp; loyalty</h3>
        <button onClick={() => setAdding(!adding)}>{adding ? "Close" : "Add customer"}</button>
      </div>
      <p className="small muted">Customers earn points on what they spend and can redeem them for a discount. Store credit and gift cards are not offered:
        they hold customer money, which needs Bank of Ghana approval first.</p>
      {(loadError || error) && <p className="error">{loadError ?? error}</p>}
      {notice && <p className="notice ok">{notice}</p>}
      {adding && (
        <form className="panel" onSubmit={add}>
          <div className="grid-2">
            <label>Name<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></label>
            <label>Phone<input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} required placeholder="024 000 0000" /></label>
            <label>Email<input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></label>
          </div>
          <button type="submit">Save customer</button>
        </form>
      )}
      <input placeholder="Search by name or phone" value={search} onChange={(e) => setSearch(e.target.value)} style={{ margin: ".8rem 0" }} />
      {customers.length === 0 ? <p className="muted">No customers yet.</p> : (
        <table className="grid">
          <thead><tr><th>Customer</th><th>Phone</th><th>Spent</th><th>Visits</th><th>Tier</th><th>Points</th><th /></tr></thead>
          <tbody>{shown.map((c) => (
            <tr key={c.id}>
              <td>{c.fullName}<div className="small muted">{c.customerCode}</div></td><td className="small">{c.phone}</td>
              <td>{formatMoney(c.totalSpendPesewas)}</td><td>{c.transactionCount}</td>
              <td><span className="tag info">{c.loyaltyTier.toLowerCase()}</span></td><td>{c.pointsBalance}</td>
              <td style={{ whiteSpace: "nowrap" }}>
                <button className="secondary small" onClick={() => setAction({ customer: c, kind: "purchase", value: "" })}>Record purchase</button>
                <button className="secondary small" disabled={c.pointsBalance <= 0} onClick={() => setAction({ customer: c, kind: "redeem", value: "" })}>Redeem</button>
              </td>
            </tr>))}</tbody>
        </table>
      )}
      {action && (
        <form className="panel" onSubmit={act} style={{ marginTop: "1rem" }}>
          <h4>{action.kind === "purchase" ? `Purchase by ${action.customer.fullName}` : `Redeem points for ${action.customer.fullName} (${action.customer.pointsBalance} available)`}</h4>
          <label>{action.kind === "purchase" ? "Amount spent (GH₵)" : "Points"}<input inputMode="decimal" value={action.value} onChange={(e) => setAction({ ...action, value: e.target.value })} required /></label>
          <button type="submit">Save</button>
          <button type="button" className="secondary" onClick={() => setAction(null)}>Cancel</button>
        </form>
      )}
    </div>
  );
}

// =============================================================================================== Payroll

interface Employee { id: string; employeeCode: string; fullName: string; phone: string | null; momoNetwork: string | null; momoNumber: string | null;
  bankCode: string | null; bankAccountNumber: string | null; employmentType: string; baseSalaryPesewas: number; allowancesPesewas: number }
interface PayrollRun { id: string; periodMonth: number; periodYear: number; totalGrossPesewas: number; totalPayeTaxPesewas: number; totalNetPayPesewas: number;
  status: string; initiatedBy: string; approvedBy: string | null }
interface Payslip { id: string; employeeId: string; employeeName: string; grossPayPesewas: number; ssnitTier2Pesewas: number;
  payeTaxPesewas: number; netPayPesewas: number; ssnitTier1EmployerPesewas: number; paymentStatus: string; payoutReference: string | null }

const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

export function PayrollAndEmployees({ merchantId }: { merchantId: string }) {
  const [employees, reloadEmployees, empError] = useLoad<Employee[]>(`/v1/payroll/employees/merchant/${merchantId}`, []);
  const [runs, reloadRuns, runError] = useLoad<PayrollRun[]>(`/v1/payroll/runs/merchant/${merchantId}`, []);
  const [adding, setAdding] = useState(false);
  const [form, setForm] = useState({ name: "", phone: "", ssnit: "", tin: "", payBy: "momo" as "momo" | "bank", network: "MTN", momo: "", bankCode: "", account: "", salary: "", allowances: "" });
  const now = new Date();
  const [period, setPeriod] = useState({ month: now.getMonth() + 1, year: now.getFullYear() });
  const [open, setOpen] = useState<PayrollRun | null>(null);
  const [slips, setSlips] = useState<Payslip[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const byId = useMemo(() => new Map(employees.map((e) => [e.id, e])), [employees]);

  async function addEmployee(e: React.FormEvent) {
    e.preventDefault(); setError(null);
    const salary = money(form.salary), allowances = form.allowances ? money(form.allowances) : 0;
    if (!salary) { setError("Enter the monthly basic salary"); return; }
    if (allowances === null) { setError("Enter allowances like 200.00, or leave empty"); return; }
    try {
      await api("/v1/payroll/employees", { body: { merchantId, fullName: form.name, phone: form.phone || null, email: null, ssnitNumber: form.ssnit || null, tin: form.tin || null,
        momoNetwork: form.payBy === "momo" ? form.network : null, momoNumber: form.payBy === "momo" ? form.momo : null,
        bankCode: form.payBy === "bank" ? form.bankCode : null, bankAccountNumber: form.payBy === "bank" ? form.account : null,
        employmentType: "FULL_TIME", baseSalaryPesewas: salary, allowancesPesewas: allowances } });
      setAdding(false); setForm({ ...form, name: "", phone: "", ssnit: "", tin: "", momo: "", account: "", salary: "", allowances: "" });
      await reloadEmployees();
    } catch (err) { setError(message(err)); }
  }

  async function openRun(r: PayrollRun) {
    setOpen(r); setError(null);
    try { setSlips(await api<Payslip[]>(`/v1/payroll/runs/${r.id}/payslips`)); } catch (err) { setError(message(err)); }
  }

  async function createRun() {
    setError(null); setBusy(true);
    try { const r = await api<PayrollRun>("/v1/payroll/runs", { body: { merchantId, month: period.month, year: period.year } }); await reloadRuns(); await openRun(r); }
    catch (err) { setError(message(err)); } finally { setBusy(false); }
  }

  async function step(path: "submit" | "approve") {
    if (!open) return; setError(null); setBusy(true);
    try {
      const updated = await api<PayrollRun>(`/v1/payroll/runs/${open.id}/${path}`, { method: "POST" });
      await reloadRuns();
      setOpen(updated);
    }
    catch (err) { setError(message(err)); } finally { setBusy(false); }
  }

  /** Pays staff through a real ELEPAY payout batch, then links the batch to this payroll. */
  async function payStaff() {
    if (!open) return;
    setError(null); setNotice(null); setBusy(true);
    try {
      const items = slips.map((s) => {
        const emp = byId.get(s.employeeId);
        if (!emp) throw new Error(`${s.employeeName} is no longer an active employee`);
        const momo = !!emp.momoNumber;
        return { reference: `payroll-${open.periodYear}-${String(open.periodMonth).padStart(2, "0")}-${emp.employeeCode}`.slice(0, 64), amount: s.netPayPesewas,
          type: momo ? "momo" : "bank", network: momo ? (emp.momoNetwork ?? "").toLowerCase() : (emp.bankCode ?? ""),
          accountNumber: momo ? emp.momoNumber : emp.bankAccountNumber, accountName: emp.fullName, platform: null };
      });
      const batch = await api<{ reference: string }>(`/v1/merchants/${merchantId}/payout-batches`, {
        body: { items, description: `Payroll ${MONTHS[open.periodMonth - 1]} ${open.periodYear}` }, idempotencyKey: `payroll-run-${open.id}` });
      await api(`/v1/payroll/runs/${open.id}/payout`, { body: { payoutBatchReference: batch.reference } });
      setNotice(`Payroll sent to ELEPAY Payouts as batch ${batch.reference}. Track each payment in the Payouts tab.`);
      await reloadRuns(); await openRun({ ...open, status: "PAYMENT_SUBMITTED" });
    } catch (err) { setError(`Staff were not paid: ${message(err)}`); } finally { setBusy(false); }
  }

  const label = (s: string) => ({ DRAFT: "draft", SUBMITTED: "awaiting approval", APPROVED: "approved, not paid", PAYMENT_SUBMITTED: "sent to payouts" } as Record<string, string>)[s] ?? s.toLowerCase();

  return (
    <div>
      {(empError || runError || error) && <p className="error">{empError ?? runError ?? error}</p>}
      {notice && <p className="notice ok">{notice}</p>}
      <div className="flex-between"><h3>Employees</h3><button onClick={() => setAdding(!adding)}>{adding ? "Close" : "Add employee"}</button></div>
      {adding && (
        <form className="panel" onSubmit={addEmployee}>
          <div className="grid-2">
            <label>Full name<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></label>
            <label>Monthly basic salary (GH₵)<input inputMode="decimal" value={form.salary} onChange={(e) => setForm({ ...form, salary: e.target.value })} required /></label>
            <label>Allowances (GH₵)<input inputMode="decimal" value={form.allowances} onChange={(e) => setForm({ ...form, allowances: e.target.value })} /></label>
            <label>Phone<input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></label>
            <label>SSNIT number<input value={form.ssnit} onChange={(e) => setForm({ ...form, ssnit: e.target.value })} /></label>
            <label>TIN / Ghana Card<input value={form.tin} onChange={(e) => setForm({ ...form, tin: e.target.value })} /></label>
            <label>Pay by<select value={form.payBy} onChange={(e) => setForm({ ...form, payBy: e.target.value as "momo" | "bank" })}>
              <option value="momo">Mobile money</option><option value="bank">Bank account</option></select></label>
            {form.payBy === "momo" ? <>
              <label>Network<select value={form.network} onChange={(e) => setForm({ ...form, network: e.target.value })}>
                <option value="MTN">MTN</option><option value="TELECEL">Telecel</option><option value="AIRTELTIGO">AirtelTigo</option></select></label>
              <label>MoMo number<input value={form.momo} onChange={(e) => setForm({ ...form, momo: e.target.value })} required /></label>
            </> : <>
              <label>Bank code<input value={form.bankCode} onChange={(e) => setForm({ ...form, bankCode: e.target.value.toUpperCase() })} required placeholder="e.g. GCB" /></label>
              <label>Account number<input value={form.account} onChange={(e) => setForm({ ...form, account: e.target.value })} required /></label>
            </>}
          </div>
          <button type="submit">Save employee</button>
        </form>
      )}
      {employees.length === 0 ? <p className="muted">No employees yet.</p> : (
        <table className="grid">
          <thead><tr><th>Name</th><th>Basic salary</th><th>Allowances</th><th>Paid to</th></tr></thead>
          <tbody>{employees.map((e) => (
            <tr key={e.id}><td>{e.fullName}<div className="small muted">{e.employeeCode}</div></td><td>{formatMoney(e.baseSalaryPesewas)}</td>
              <td>{formatMoney(e.allowancesPesewas)}</td><td className="small">{e.momoNumber ? `${e.momoNetwork} ${e.momoNumber}` : `${e.bankCode} ${e.bankAccountNumber}`}</td></tr>))}</tbody>
        </table>
      )}
      <h3>Payroll</h3>
      <div className="flex-row">
        <select value={period.month} onChange={(e) => setPeriod({ ...period, month: Number(e.target.value) })} style={{ maxWidth: "10rem" }}>
          {MONTHS.map((m, i) => <option key={m} value={i + 1}>{m}</option>)}
        </select>
        <input inputMode="numeric" value={period.year} onChange={(e) => setPeriod({ ...period, year: Number(e.target.value) })} style={{ maxWidth: "6rem" }} aria-label="Year" />
        <button onClick={() => void createRun()} disabled={busy || employees.length === 0}>Calculate payroll</button>
      </div>
      {runs.length > 0 && (
        <table className="grid">
          <thead><tr><th>Month</th><th>Gross</th><th>PAYE</th><th>Net pay</th><th>Status</th><th /></tr></thead>
          <tbody>{runs.map((r) => (
            <tr key={r.id}><td>{MONTHS[r.periodMonth - 1]} {r.periodYear}</td><td>{formatMoney(r.totalGrossPesewas)}</td><td>{formatMoney(r.totalPayeTaxPesewas)}</td>
              <td>{formatMoney(r.totalNetPayPesewas)}</td><td><span className={`tag ${r.status === "PAYMENT_SUBMITTED" ? "info" : "pending"}`}>{label(r.status)}</span></td>
              <td><button className="secondary small" onClick={() => void openRun(r)}>Open</button></td></tr>))}</tbody>
        </table>
      )}
      {open && (
        <div className="panel" style={{ marginTop: "1rem" }}>
          <div className="flex-between"><h4>{MONTHS[open.periodMonth - 1]} {open.periodYear} · {label(open.status)}</h4>
            <button className="secondary small" onClick={() => setOpen(null)}>Close</button></div>
          <table className="grid">
            <thead><tr><th>Employee</th><th>Gross</th><th>Employee SSNIT</th><th>PAYE</th><th>Net pay</th><th>Payment</th></tr></thead>
            <tbody>{slips.map((s) => (
              <tr key={s.id}><td>{s.employeeName}</td><td>{formatMoney(s.grossPayPesewas)}</td><td>{formatMoney(s.ssnitTier2Pesewas)}</td>
                <td>{formatMoney(s.payeTaxPesewas)}</td><td><strong>{formatMoney(s.netPayPesewas)}</strong></td>
                <td className="small">{s.paymentStatus === "SUBMITTED" ? `sent to payouts (${s.payoutReference})` : s.paymentStatus.toLowerCase()}</td></tr>))}</tbody>
          </table>
          {open.status === "DRAFT" && <button onClick={() => void step("submit")} disabled={busy}>Submit for approval</button>}
          {open.status === "SUBMITTED" && <button onClick={() => void step("approve")} disabled={busy}>Approve payroll</button>}
          {open.status === "APPROVED" && <>
            <p className="small muted">Paying sends one ELEPAY payout per employee from your available balance, with the usual payout checks.</p>
            <button onClick={() => void payStaff()} disabled={busy}>Pay staff ({formatMoney(slips.reduce((t, s) => t + s.netPayPesewas, 0))})</button>
          </>}
          {open.status === "PAYMENT_SUBMITTED" && <p className="small">Payments are being processed. See each one in the <strong>Payouts</strong> tab.</p>}
        </div>
      )}
    </div>
  );
}

// =============================================================================================== Business accounting

interface Balances { pending: number; available: number; reserved: number }
interface TxLine { kind: string; amount: number; fee: number; refunded: number; paidAt: string | null }
interface ProfitAndLoss { fromDate: string; toDate: string; totalRevenuePesewas: number; totalCogsPesewas: number; grossProfitPesewas: number;
  totalOperatingExpensePesewas: number; netProfitPesewas: number }
interface BalanceSheet { asOfDate: string; totalAssetsPesewas: number; totalLiabilitiesPesewas: number; totalEquityPesewas: number; netIncomePesewas: number; isBalanced: boolean }
interface TrialBalance { lines: { accountCode: string; accountName: string; category: string; debitBalancePesewas: number; creditBalancePesewas: number }[];
  totalDebitPesewas: number; totalCreditPesewas: number; isBalanced: boolean }

export function BusinessAccounting({ merchantId }: { merchantId: string }) {
  const today = new Date().toISOString().slice(0, 10);
  const [from, setFrom] = useState(today.slice(0, 8) + "01");
  const [to, setTo] = useState(today);
  const [balances, , balError] = useLoad<Balances | null>(`/v1/merchants/${merchantId}/balances`, null);
  const [lines, , txError] = useLoad<TxLine[]>(`/v1/merchants/${merchantId}/transactions`, []);
  const [pl, , plError] = useLoad<ProfitAndLoss | null>(`/v1/accounting/merchant/${merchantId}/profit-and-loss?from=${from}&to=${to}`, null);
  const [bs, , bsError] = useLoad<BalanceSheet | null>(`/v1/accounting/merchant/${merchantId}/balance-sheet?asOf=${to}`, null);
  const [tb, , tbError] = useLoad<TrialBalance | null>(`/v1/accounting/merchant/${merchantId}/trial-balance?asOf=${to}`, null);

  const inPeriod = lines.filter((l) => l.paidAt && l.paidAt.slice(0, 10) >= from && l.paidAt.slice(0, 10) <= to);
  const sales = inPeriod.filter((l) => l.kind === "sale").reduce((t, l) => t + l.amount, 0);
  const refunds = inPeriod.reduce((t, l) => t + l.refunded, 0);
  const fees = inPeriod.reduce((t, l) => t + l.fee, 0);
  const error = balError ?? txError ?? plError ?? bsError ?? tbError;
  const accounts = (tb?.lines ?? []).filter((l) => l.debitBalancePesewas !== 0 || l.creditBalancePesewas !== 0);

  return (
    <div>
      <div className="flex-row">
        <label>From<input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></label>
        <label>To<input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} /></label>
      </div>
      {error && <p className="error">{error}</p>}
      <div className="grid-2">
        <section className="panel">
          <h3>ELEPAY payments</h3>
          <p className="small muted">Money collected through ELEPAY in this period.</p>
          <table className="lines"><tbody>
            <tr><td>Sales collected</td><td className="num">{formatMoney(sales)}</td></tr>
            <tr><td>Refunded to customers</td><td className="num">-{formatMoney(refunds)}</td></tr>
            <tr><td>ELEPAY fees</td><td className="num">-{formatMoney(fees)}</td></tr>
            <tr className="total"><td>Net received</td><td className="num">{formatMoney(sales - refunds - fees)}</td></tr>
          </tbody></table>
          {balances && <table className="lines" style={{ marginTop: ".8rem" }}><tbody>
            <tr><td>Available to withdraw now</td><td className="num">{formatMoney(balances.available)}</td></tr>
            <tr><td>Waiting for settlement</td><td className="num">{formatMoney(balances.pending)}</td></tr>
            <tr><td>Held (disputes, payouts in progress)</td><td className="num">{formatMoney(balances.reserved)}</td></tr>
          </tbody></table>}
        </section>
        <section className="panel">
          <h3>Your books</h3>
          <p className="small muted">From entries posted in ELEPAY: invoices, invoice payments and payroll. Sales collected through ELEPAY are not posted here automatically.</p>
          {pl && <table className="lines"><tbody>
            <tr><td>Revenue</td><td className="num">{formatMoney(pl.totalRevenuePesewas)}</td></tr>
            <tr><td>Cost of goods sold</td><td className="num">-{formatMoney(pl.totalCogsPesewas)}</td></tr>
            <tr className="total"><td>Gross profit</td><td className="num">{formatMoney(pl.grossProfitPesewas)}</td></tr>
            <tr><td>Operating expenses</td><td className="num">-{formatMoney(pl.totalOperatingExpensePesewas)}</td></tr>
            <tr className="total"><td>Net profit before tax</td><td className="num">{formatMoney(pl.netProfitPesewas)}</td></tr>
          </tbody></table>}
          {bs && <table className="lines" style={{ marginTop: ".8rem" }}><tbody>
            <tr><td>Assets</td><td className="num">{formatMoney(bs.totalAssetsPesewas)}</td></tr>
            <tr><td>Liabilities</td><td className="num">{formatMoney(bs.totalLiabilitiesPesewas)}</td></tr>
            <tr><td>Equity (incl. profit to date)</td><td className="num">{formatMoney(bs.totalEquityPesewas + bs.netIncomePesewas)}</td></tr>
            {!bs.isBalanced && <tr><td colSpan={2} className="error">The books do not balance; check recent entries.</td></tr>}
          </tbody></table>}
        </section>
      </div>
      <h3>Account balances on {to}</h3>
      {accounts.length === 0 ? <p className="muted">No entries posted yet.</p> : (
        <table className="grid">
          <thead><tr><th>Code</th><th>Account</th><th>Debit</th><th>Credit</th></tr></thead>
          <tbody>
            {accounts.map((l) => <tr key={l.accountCode}><td className="mono">{l.accountCode}</td><td>{l.accountName}</td>
              <td className="num">{l.debitBalancePesewas ? formatMoney(l.debitBalancePesewas) : ""}</td><td className="num">{l.creditBalancePesewas ? formatMoney(l.creditBalancePesewas) : ""}</td></tr>)}
            {tb && <tr className="total"><td colSpan={2}>Total</td><td className="num">{formatMoney(tb.totalDebitPesewas)}</td><td className="num">{formatMoney(tb.totalCreditPesewas)}</td></tr>}
          </tbody>
        </table>
      )}
      <button className="secondary" style={{ marginTop: "1rem" }} onClick={() => window.print()}>Print</button>
    </div>
  );
}
