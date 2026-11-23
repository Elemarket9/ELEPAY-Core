import { useEffect, useMemo, useRef, useState } from "react";
import QRCode from "qrcode";
import { Code128Barcode } from "./Barcode128";
import { addCashLocally, loadOfflineCatalog, newOfflineOrder, offlineDb, reconcileQueued,
  saveOfflineCatalog, type OfflineOrder, type OfflinePayload, type ReconcileResult } from "./offline-pos";
import { api, ApiError, newKey } from "./api";
import { formatMoney, parseMoney } from "./money";

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
  const load = async () => {
    if (!path) return;
    setLoading(true);
    try { setData(await api<T>(path)); setError(null); } catch (e) { setError(message(e)); } finally { setLoading(false); }
  };
  useEffect(() => { void load(); }, [path]);
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

interface PosOrder { id: string; orderNumber: string; totalPesewas: number; paidPesewas: number; changePesewas: number; status: string }
interface DigitalCheckout { id: string; reference: string; amount: number; status: string; payUrl: string | null }
interface PosReceipt { receiptNumber: string; escposFormattedText: string }
interface PaymentTerminal { id: string; model: string; serialNumber: string; branchId: string | null }
interface DiningTableOption { id: string; tableNumber: string; status: string; currentOrderId: string | null }
interface SplitQuoteLine { label: string; amountPesewas: number }
interface PaymentCode { checkoutReference: string; barcodeValue: string; qrPayload: string; amountPesewas: number; status: string }
interface TerminalSession { status: string; instructions: string | null; checkoutReference: string; providerReference: string }

const TAX_KEY = (merchantId: string) => `elepay.pos.taxPercent.${merchantId}`;

export function PosRegister({ merchant }: { merchant: MerchantRef }) {
  const [serverCatalog, reloadCatalog, loadError] = useLoad<CatalogRow[]>(`/v1/inventory/catalog/merchant/${merchant.id}`, []);
  const [cachedCatalog, setCachedCatalog] = useState<CatalogRow[]>([]);
  const catalog = serverCatalog.length ? serverCatalog : cachedCatalog;
  const [tables, reloadTables] = useLoad<DiningTableOption[]>(`/v1/restaurant/tables/merchant/${merchant.id}`, []);
  const [terminals] = useLoad<PaymentTerminal[]>(`/v1/pos/terminals/available/${merchant.id}`, []);
  const [orderType, setOrderType] = useState("RETAIL");
  const [tableId, setTableId] = useState("");
  const [terminalId, setTerminalId] = useState("");
  const [paymentCode, setPaymentCode] = useState<PaymentCode | null>(null);
  const [terminalSession, setTerminalSession] = useState<TerminalSession | null>(null);
  const [destinationTable, setDestinationTable] = useState("");
  const [splitMethod, setSplitMethod] = useState<"even" | "seats" | "items" | "amount">("even");
  const [splitCount, setSplitCount] = useState("2");
  const [splitAmount, setSplitAmount] = useState("");
  const [splitPreview, setSplitPreview] = useState<SplitQuoteLine[]>([]);
  const [tableNotice, setTableNotice] = useState("");
  const [addingRound, setAddingRound] = useState(false);
  const [search, setSearch] = useState("");
  const [cart, setCart] = useState<{ row: CatalogRow; qty: number }[]>([]);
  const [discount, setDiscount] = useState("");
  const [taxPercent, setTaxPercent] = useState(() => { try { return localStorage.getItem(TAX_KEY(merchant.id)) ?? "0"; } catch { return "0"; } });
  const [tendered, setTendered] = useState("");
  const [order, setOrder] = useState<PosOrder | null>(null);
  const [digital, setDigital] = useState<DigitalCheckout | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [receipt, setReceipt] = useState<{ receipt: PosReceipt; order: PosOrder } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [networkOnline, setNetworkOnline] = useState(() => navigator.onLine);
  const [offlineOrders, setOfflineOrders] = useState<OfflineOrder[]>([]);
  const [activeOffline, setActiveOffline] = useState<OfflineOrder | null>(null);
  const [offlineWarning, setOfflineWarning] = useState("");
  const syncBusy = useRef(false);

  async function refreshOffline() {
    const saved = await offlineDb.list(merchant.id);
    setOfflineOrders(saved);
    setActiveOffline((selected) => selected
      ? saved.find((o) => o.offlineTxId === selected.offlineTxId) ?? selected : null);
  }

  async function syncPending() {
    if (syncBusy.current || !navigator.onLine) return;
    syncBusy.current = true;
    try {
      await reconcileQueued(offlineDb, merchant.id,
        (payload: OfflinePayload) => api<ReconcileResult>("/v1/pos/sync", { body: payload }));
      await refreshOffline();
    } catch (e) {
      setOfflineWarning(`Local orders retained: ${message(e)}`);
    } finally {
      syncBusy.current = false;
    }
  }

  useEffect(() => {
    let live = true;
    void loadOfflineCatalog<CatalogRow>(merchant.id).then((snapshot) => {
      if (live && snapshot) setCachedCatalog(snapshot.rows);
    }).catch(() => {});
    void refreshOffline().catch(() => setOfflineWarning("Offline storage cannot be opened on this device"));
    const status = () => {
      setNetworkOnline(navigator.onLine);
      if (navigator.onLine) void syncPending();
    };
    window.addEventListener("online", status);
    window.addEventListener("offline", status);
    const interval = window.setInterval(() => {
      if (navigator.onLine) void syncPending();
    }, 12000);
    if (navigator.onLine) void syncPending();
    void navigator.storage?.persist?.().catch(() => false);
    return () => {
      live = false;
      clearInterval(interval);
      window.removeEventListener("online", status);
      window.removeEventListener("offline", status);
    };
  }, [merchant.id]);

  useEffect(() => {
    if (serverCatalog.length) void saveOfflineCatalog(merchant.id, serverCatalog).catch(() =>
      setOfflineWarning("Menu snapshot could not be saved for offline operation"));
  }, [merchant.id, serverCatalog]);

  const subtotal = cart.reduce((s, c) => s + c.row.retailPricePesewas * c.qty, 0);
  const discountP = discount.trim() ? money(discount) : 0;
  const taxRate = Number(taxPercent);
  const taxValid = Number.isFinite(taxRate) && taxRate >= 0 && taxRate <= 100;
  const tax = discountP !== null && taxValid ? Math.round((subtotal - Math.min(discountP, subtotal)) * taxRate / 100) : 0;
  const total = discountP !== null ? subtotal - Math.min(discountP, subtotal) + tax : subtotal;
  const locallyCollected = activeOffline?.payments.reduce((sum, x) => sum + x.amountPesewas, 0) ?? 0;
  const balanceDue = activeOffline ? Math.max(0, activeOffline.totalPesewas - locallyCollected)
    : order ? Math.max(0, order.totalPesewas - order.paidPesewas) : total;
  const shown = catalog.filter((r) => `${itemLabel(r)} ${r.sku} ${r.barcode ?? ""}`.toLowerCase().includes(search.toLowerCase()));

  useEffect(() => { try { localStorage.setItem(TAX_KEY(merchant.id), taxPercent); } catch { /* ignore */ } }, [taxPercent, merchant.id]);
  useEffect(() => { if (digital?.payUrl) void QRCode.toDataURL(digital.payUrl, { margin: 1, width: 220 }).then(setQr); else setQr(null); }, [digital]);

  const add = (row: CatalogRow) => setCart((c) => c.some((x) => x.row.variantId === row.variantId)
    ? c.map((x) => x.row.variantId === row.variantId ? { ...x, qty: x.qty + 1 } : x) : [...c, { row, qty: 1 }]);
  const setQty = (id: string, qty: number) => setCart((c) => qty <= 0 ? c.filter((x) => x.row.variantId !== id) : c.map((x) => x.row.variantId === id ? { ...x, qty } : x));

  async function queueCurrentOffline(receivedCashPesewas = 0) {
    if (order || activeOffline || addingRound || digital)
      throw new Error("Finish the current sale before offline capture");
    if (discountP === null || !taxValid || cart.length === 0)
      throw new Error("Choose items with a valid discount and tax");
    if (orderType === "DINE_IN" && !tableId) throw new Error("Select a table");
    const terminal = terminals.find((t) => t.id === terminalId);
    let draft = newOfflineOrder({
      merchantId: merchant.id, branchId: terminal?.branchId ?? null, terminalId: terminal?.id ?? null,
      cashierId: null, customerId: null, orderType: orderType as OfflineOrder["orderType"],
      tableId: tableId || null,
      items: cart.map((c) => ({
        variantId: c.row.variantId, itemName: itemLabel(c.row), quantity: c.qty,
        unitPricePesewas: c.row.retailPricePesewas, discountPesewas: 0, taxPesewas: 0,
        modifiersJson: "[]", seatNumber: null, course: "MAIN", notes: null,
      })),
      discountPesewas: Math.min(discountP, subtotal), taxPesewas: tax, tipPesewas: 0,
      totalPesewas: total, payments: [],
    });
    if (receivedCashPesewas) draft = addCashLocally(draft, receivedCashPesewas);
    await offlineDb.put(draft);
    setActiveOffline(draft);
    setCart([]);
    setTendered("");
    await refreshOffline();
    setTableNotice("Saved locally. Other kitchen devices cannot see this order until it reaches the server.");
    return draft;
  }

  async function ensureOrder(): Promise<PosOrder> {
    if (order) return order;
    if (discountP === null) throw new Error("Enter the discount like 5.00");
    if (!taxValid) throw new Error("Tax must be between 0 and 100%");
    if ((orderType === "DINE_IN" || orderType === "QR") && !tableId) throw new Error("Select a table first");
    const chosenTerminal = terminals.find((t) => t.id === terminalId);
    const created = await api<PosOrder>("/v1/pos/orders", { body: { merchantId: merchant.id, branchId: chosenTerminal?.branchId ?? null, terminalId: chosenTerminal?.id ?? null, cashierId: null, customerId: null, tableId: tableId || null,
      items: cart.map((c) => ({ variantId: c.row.variantId, itemName: itemLabel(c.row), quantity: c.qty, unitPricePesewas: c.row.retailPricePesewas,
        discountPesewas: 0, taxPesewas: 0, modifiersJson: "[]", seatNumber: null, course: null, notes: null })),
      discountPesewas: Math.min(discountP, subtotal), taxPesewas: tax, tipPesewas: 0, orderType } });
    setOrder(created);
    setCart([]); // Once sent to the kitchen, the server is the order's source of truth.
    return created;
  }

  async function finish(o: PosOrder) {
    const r = await api<PosReceipt>(`/v1/pos/orders/${o.id}/receipt`, { method: "POST" });
    const latest = await api<PosOrder>(`/v1/pos/orders/${o.id}`);
    setReceipt({ receipt: r, order: latest });
    setCart([]); setDiscount(""); setTendered(""); setOrder(null); setDigital(null);
    setPaymentCode(null); setTerminalSession(null); setSplitPreview([]); setAddingRound(false);
    void reloadCatalog();
  }

  async function sendToKitchen() {
    setError(null); setBusy(true);
    try {
      if (addingRound && order) {
        if (cart.length === 0) throw new Error("Select items for the next round");
        const latest = await api<PosOrder>(`/v1/pos/orders/${order.id}/items`, {
          body: { items: cart.map((c) => ({
            variantId: c.row.variantId, itemName: itemLabel(c.row), quantity: c.qty,
            unitPricePesewas: c.row.retailPricePesewas, discountPesewas: 0,
            taxPesewas: taxValid ? Math.round(c.row.retailPricePesewas * c.qty * taxRate / 100) : 0,
            modifiersJson: "[]", seatNumber: null, course: "MAIN", notes: null,
          })) }, idempotencyKey: newKey() });
        setOrder(latest); setCart([]); setAddingRound(false); setSplitPreview([]);
        setTableNotice("Next round sent to the kitchen. Both rounds remain on one bill.");
      } else {
        await ensureOrder();
        setTableNotice("Order sent to the kitchen.");
      }
    }
    catch (e) { setError(`Could not send order: ${message(e)}`); }
    finally { setBusy(false); }
  }

  async function showPaymentBarcode() {
    if (addingRound && cart.length) { setError("Send the next round to the kitchen before payment"); return; }
    setError(null); setBusy(true);
    try {
      const o = await ensureOrder();
      const code = await api<PaymentCode>(`/v1/pos/orders/${o.id}/payment-code`, { method: "POST" });
      setPaymentCode(code);
      setDigital({ id: o.id, reference: code.checkoutReference, amount: code.amountPesewas, status: "open", payUrl: code.qrPayload });
    } catch (e) { setError(`Could not generate payment barcode: ${message(e)}`); }
    finally { setBusy(false); }
  }

  async function startContactless() {
    if (addingRound && cart.length) { setError("Send the next round to the kitchen before payment"); return; }
    if (!terminalId) { setError("Choose a paired payment terminal for NFC tap"); return; }
    setError(null); setBusy(true);
    try {
      const o = await ensureOrder();
      const s = await api<TerminalSession>(`/v1/pos/orders/${o.id}/terminal-payments`, {
        body: { terminalId, mode: "NFC_TAP" }, idempotencyKey: newKey() });
      setTerminalSession(s);
    } catch (e) { setError(`NFC unavailable: ${message(e)}`); }
    finally { setBusy(false); }
  }

  async function transferTable() {
    if (!order || !tableId || !destinationTable) return;
    setError(null); setBusy(true);
    try {
      await api(`/v1/restaurant/tables/${tableId}/move?destinationId=${encodeURIComponent(destinationTable)}`, { method: "POST" });
      setTableId(destinationTable); setDestinationTable(""); setSplitPreview([]);
      await reloadTables();
    } catch (e) { setError(`Table transfer failed: ${message(e)}`); }
    finally { setBusy(false); }
  }

  async function closeCompletedTable() {
    if (!receipt || !tableId) return;
    setError(null); setBusy(true);
    try {
      await api(`/v1/restaurant/tables/${tableId}/close`, { method: "POST" });
      setTableNotice("Table closed and marked for cleaning.");
      setTableId(""); await reloadTables();
    } catch (e) { setError(`Cannot close table: ${message(e)}`); }
    finally { setBusy(false); }
  }

  async function previewBillSplit() {
    if (addingRound && cart.length) { setError("Send the next round to the kitchen before splitting the bill"); return; }
    setError(null); setBusy(true);
    try {
      const o = await ensureOrder();
      const prefix = `/v1/pos/orders/${o.id}/split`;
      if (splitMethod === "even") {
        const n = Number(splitCount);
        if (!Number.isInteger(n) || n < 2 || n > 100) throw new Error("Choose 2-100 portions");
        const q = await api<{ portions: { portionIndex: number; amountPesewas: number }[] }>(`${prefix}/even?count=${n}`, { method: "POST" });
        setSplitPreview(q.portions.map((p) => ({ label: `Guest ${p.portionIndex}`, amountPesewas: p.amountPesewas })));
      } else if (splitMethod === "amount") {
        const first = money(splitAmount);
        if (first === null || first <= 0 || first >= o.totalPesewas) throw new Error("Enter a smaller positive share");
        const q = await api<{ firstPesewas: number; remainingPesewas: number }>(`${prefix}/amount?firstPesewas=${first}`, { method: "POST" });
        setSplitPreview([{ label: "First share", amountPesewas: q.firstPesewas }, { label: "Remaining share", amountPesewas: q.remainingPesewas }]);
      } else if (splitMethod === "items") {
        const q = await api<{ portions: { orderItemId: string; itemName: string; amountPesewas: number }[] }>(`${prefix}/items`, { method: "POST" });
        setSplitPreview(q.portions.map((p) => ({ label: p.itemName, amountPesewas: p.amountPesewas })));
      } else {
        const q = await api<{ seatGroups: { seatNumber: number; totalDuePesewas: number }[] }>(`${prefix}/seats`, { method: "POST" });
        setSplitPreview(q.seatGroups.map((p) => ({ label: `Seat ${p.seatNumber}`, amountPesewas: p.totalDuePesewas })));
      }
    } catch (e) { setError(`Split preview failed: ${message(e)}`); setSplitPreview([]); }
    finally { setBusy(false); }
  }

  async function payCash() {
    if (addingRound && cart.length) { setError("Send the next round to the kitchen before payment"); return; }
    setError(null);
    const cash = money(tendered);
    if (cash === null || cash <= 0) { setError("Enter the cash received, like 50.00"); return; }
    // The POS order accepts multiple verified tenders. Partial cash leaves the remaining bill open.
    if (cash > 10_000_000_000) { setError("Cash amount exceeds the allowed input size"); return; }
    setBusy(true);
    try {
      const o = await ensureOrder();
      const paid = await api<PosOrder>(`/v1/pos/orders/${o.id}/payments`, { body: { tenderType: "CASH", amountPesewas: cash, reference: null }, idempotencyKey: newKey() });
      if (paid.status !== "PAID") { setOrder(paid); setTendered(""); setTableNotice(`Partial cash recorded. Balance: ${formatMoney(Math.max(0, paid.totalPesewas - paid.paidPesewas))}`); return; }
      await finish(paid);
    } catch (e) { setError(`Sale not recorded: ${message(e)}`); } finally { setBusy(false); }
  }

  async function startDigital() {
    if (addingRound && cart.length) { setError("Send the next round to the kitchen before payment"); return; }
    setError(null); setBusy(true);
    try {
      const o = await ensureOrder();
      setDigital(await api<DigitalCheckout>(`/v1/pos/orders/${o.id}/digital-checkout`, { method: "POST" }));
    } catch (e) { setError(`Could not start MoMo/card payment: ${message(e)}`); } finally { setBusy(false); }
  }

  async function checkDigital() {
    if (!order) return;
    setError(null); setBusy(true);
    try {
      const paid = await api<PosOrder>(`/v1/pos/orders/${order.id}/digital-checkout/confirm`, { method: "POST" });
      await finish(paid);
    } catch (e) { setError(`Not paid yet: ${message(e)}`); } finally { setBusy(false); }
  }

  async function cancelDigital() {
    if (!order) return;
    setBusy(true);
    try { await api(`/v1/pos/orders/${order.id}/digital-checkout/cancel`, { method: "POST" }); setDigital(null); }
    catch (e) { setError(message(e)); } finally { setBusy(false); }
  }

  if (receipt) {
    return (
      <div className="panel">
        <h3>Sale complete</h3>
        <p>Order {receipt.order.orderNumber} · {formatMoney(receipt.order.totalPesewas)}
          {receipt.order.changePesewas > 0 && <> · <strong>Change {formatMoney(receipt.order.changePesewas)}</strong></>}</p>
        <pre className="receipt">{receipt.receipt.escposFormattedText}</pre>
        {tableNotice && <p className="notice ok">{tableNotice}</p>}
        {error && <p className="error">{error}</p>}
        {orderType === "DINE_IN" && tableId && <button disabled={busy} onClick={() => void closeCompletedTable()}>
          Close table after kitchen marks it Served
        </button>}
        <div className="flex-row">
          <button className="secondary" onClick={() => window.print()}>Print receipt</button>
          <button onClick={() => { setReceipt(null); setTableNotice(""); }}>New sale</button>
        </div>
      </div>
    );
  }

  return (
    <div className="pos-layout">
      <div>
        <h3>Products</h3>
        {loadError && <p className="error">{loadError}</p>}
        <input placeholder="Search or scan barcode" value={search} onChange={(e) => setSearch(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") { const hit = catalog.find((r) => r.barcode === search.trim() || r.sku === search.trim()); if (hit) { add(hit); setSearch(""); } } }} />
        {catalog.length === 0 && !loadError && <p className="muted">No products yet. Add them in <strong>Inventory &amp; Stock</strong>.</p>}
        <div className="pos-items">
          {shown.map((r) => (
            <button key={r.variantId} className="secondary pos-item" disabled={!!order && !addingRound} onClick={() => add(r)}>
              <span>{itemLabel(r)}</span><strong>{formatMoney(r.retailPricePesewas)}</strong>
              {r.trackInventory && <span className="small muted">{Number(r.onHand)} in stock</span>}
            </button>
          ))}
        </div>
      </div>
      <div className="panel">
        <h3>Sale</h3>
        <div className="grid-2">
          {terminals.length > 0 && <label>Payment terminal
            <select value={terminalId} disabled={!!order} onChange={(e) => setTerminalId(e.target.value)}>
              <option value="">No terminal selected</option>
              {terminals.map((t) => <option key={t.id} value={t.id}>{t.model} – {t.serialNumber}</option>)}
            </select>
          </label>}
          <label>Order channel
            <select value={orderType} disabled={!!order} onChange={(e) => { setOrderType(e.target.value); setTableId(""); }}>
              <option value="RETAIL">Retail</option><option value="DINE_IN">Dine-in</option>
              <option value="TAKEOUT">Takeaway</option><option value="DELIVERY">Delivery</option>
            </select>
          </label>
          {orderType === "DINE_IN" && <label>Table
            <select value={tableId} disabled={!!order} onChange={(e) => setTableId(e.target.value)}>
              <option value="">Select available table</option>
              {tables.filter((t) => t.status === "AVAILABLE").map((t) => <option key={t.id} value={t.id}>{t.tableNumber}</option>)}
            </select>
          </label>}
        </div>
        {cart.length === 0 ? <p className="muted">{order
          ? "Order open. Add another round or collect payment."
          : "Tap products to add them."}</p> : (
          <table className="grid">
            <tbody>
              {cart.map((c) => (
                <tr key={c.row.variantId}>
                  <td>{itemLabel(c.row)}</td>
                  <td style={{ whiteSpace: "nowrap" }}>
                    <button className="secondary small pos-qty" disabled={!!order && !addingRound} onClick={() => setQty(c.row.variantId, c.qty - 1)}>−</button>
                    {c.qty}
                    <button className="secondary small pos-qty" disabled={!!order && !addingRound} onClick={() => setQty(c.row.variantId, c.qty + 1)}>+</button>
                  </td>
                  <td>{formatMoney(c.row.retailPricePesewas * c.qty)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="grid-2">
          <label>Discount (GH₵)<input inputMode="decimal" value={discount} disabled={!!order} onChange={(e) => setDiscount(e.target.value)} placeholder="0.00" /></label>
          <label>Tax %<input inputMode="decimal" value={taxPercent} disabled={!!order} onChange={(e) => setTaxPercent(e.target.value)} /></label>
        </div>
        <dl className="confirm">
          {order ? <>
            <dt>Current bill total</dt><dd><strong>{formatMoney(order.totalPesewas)}</strong></dd>
            {addingRound && cart.length > 0 && <>
              <dt>Unsaved new items</dt><dd>{formatMoney(subtotal)}</dd>
            </>}
          </> : <>
            <dt>Subtotal</dt><dd>{formatMoney(subtotal)}</dd>
            {tax > 0 && <><dt>Tax</dt><dd>{formatMoney(tax)}</dd></>}
            <dt><strong>Total</strong></dt><dd><strong>{formatMoney(total)}</strong></dd>
          </>}
        </dl>
        {error && <p className="error">{error}</p>}
        {tableNotice && <p className="notice ok">{tableNotice}</p>}
        {order && order.status === "PENDING" && orderType !== "RETAIL" && <>
          {!addingRound ? <button className="secondary" onClick={() => { setAddingRound(true); setTableNotice(""); }}>
            Add another round
          </button> : <button className="secondary" onClick={() => { setAddingRound(false); setCart([]); }}>
            Cancel new round
          </button>}
        </>}
        {order && tableId && orderType === "DINE_IN" && <div className="pos-transfer">
          <label>Move open order to an available table
            <select value={destinationTable} onChange={(e) => setDestinationTable(e.target.value)}>
              <option value="">Choose available destination</option>
              {tables.filter((t) => t.status === "AVAILABLE" && !t.currentOrderId && t.id !== tableId).map((t) =>
                <option key={t.id} value={t.id}>{t.tableNumber}</option>)}
            </select>
          </label>
          <button className="secondary" disabled={busy || !destinationTable} onClick={() => void transferTable()}>Transfer table</button>
        </div>}
        {(cart.length > 0 || !!order) && <div className="pos-bill-split">
          <h4>Split the bill</h4>
          <div className="grid-2">
            <label>Split method
              <select value={splitMethod} onChange={(e) => { setSplitMethod(e.target.value as typeof splitMethod); setSplitPreview([]); }}>
                <option value="even">Equal shares</option>
                <option value="seats">By seat</option>
                <option value="items">By item</option>
                <option value="amount">By amount</option>
              </select>
            </label>
            {splitMethod === "even" && <label>Number of people<input inputMode="numeric" value={splitCount} onChange={(e) => setSplitCount(e.target.value)} /></label>}
            {splitMethod === "amount" && <label>First share (GH₵)<input inputMode="decimal" value={splitAmount} onChange={(e) => setSplitAmount(e.target.value)} /></label>}
          </div>
          <button className="secondary" disabled={busy} onClick={() => void previewBillSplit()}>Preview split</button>
          {splitPreview.length > 0 && <div>
            <p className="small muted">Each share includes allocated discounts and charges. Payments remain on the one order.</p>
            <ul>{splitPreview.map((line, i) => <li key={i} className="flex-between">
              <span>{line.label}</span><strong>{formatMoney(line.amountPesewas)}</strong>
            </li>)}</ul>
            <strong>Split total: {formatMoney(splitPreview.reduce((a, b) => a + b.amountPesewas, 0))}</strong>
          </div>}
        </div>}
        {order && orderType !== "RETAIL" && <p className="notice ok">Order {order.orderNumber} sent to kitchen. Payment status: {order.status}.</p>}
        {orderType !== "RETAIL" && cart.length > 0 && (!order || addingRound) && 
          <button onClick={() => void sendToKitchen()} disabled={busy}>
            {addingRound ? "Send new round to kitchen" : "Send to kitchen"}
          </button>}
        {terminalSession && <p className="notice">Terminal request pending: {terminalSession.instructions ?? "Ask customer to tap their card."} Confirm only after your acquirer settles the checkout.</p>}
        {order && <p>Paid: {formatMoney(order.paidPesewas)} · Remaining balance: <strong>{formatMoney(balanceDue)}</strong></p>}
        {digital ? (
          <div className="notice">
            <p>Customer pays <strong>{formatMoney(digital.amount)}</strong> with MoMo or card:</p>
            {qr && <img src={qr} alt="Payment QR code" width={220} height={220} />}
            {digital.payUrl && <p className="small mono">{digital.payUrl}</p>}
            {paymentCode && <div className="payment-code-panel">
              <strong>Scannable Code 128 payment reference</strong>
              <Code128Barcode value={paymentCode.barcodeValue} />
              <p className="mono small">{paymentCode.barcodeValue}</p>
              <p className="muted small">Scanning only opens checkout. Payment requires verified MoMo/card confirmation.</p>
            </div>}
            <button onClick={() => void checkDigital()} disabled={busy}>Check payment</button>
            <button className="secondary" onClick={() => void cancelDigital()} disabled={busy}>Cancel payment</button>
          </div>
        ) : (cart.length > 0 || !!order) && (
          <>
            <label>Cash received (GH₵) — full or partial
              <input inputMode="decimal" value={tendered} onChange={(e) => setTendered(e.target.value)}
                placeholder={(balanceDue / 100).toFixed(2)} /></label>
            <div className="flex-row">
              <button onClick={() => void payCash()} disabled={busy}>Cash</button>
              <button className="secondary" onClick={() => void startDigital()} disabled={busy}>MoMo / card</button>
              <button className="secondary" onClick={() => void showPaymentBarcode()} disabled={busy}>Payment QR / barcode</button>
              <button className="secondary" onClick={() => void startContactless()} disabled={busy || !terminalId}>NFC tap on terminal</button>
              {!order && <button className="secondary" onClick={() => setCart([])}>Clear</button>}
            </div>
          </>
        )}
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
    try { await api(`/v1/payroll/runs/${open.id}/${path}`, { method: "POST" }); await reloadRuns();
      setOpen({ ...open, status: path === "submit" ? "SUBMITTED" : "APPROVED" }); }
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
