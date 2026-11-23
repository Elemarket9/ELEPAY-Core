import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { api, newKey } from "./api";
import { formatMoney, parseMoney } from "./money";
import { BusinessAccounting, CustomersAndLoyalty, FloorPlanView, InventoryAndStock, InvoicesAndQuotes, PayrollAndEmployees, PosRegister } from "./MerchantTools";

interface MerchantView { id: string; tradingName: string; kind: string; status: string; merchantCode: string; role: string }
interface Balances { pending: number; available: number; reserved: number }
interface Line {
  reference: string; line: number; kind: string; description: string | null; amount: number; fee: number; net: number;
  refunded: number; disputed: number; pending: number; settled: number; status: string; paidAt: string | null; collector: string;
}
interface Payout { reference: string; amount: number; status: string; clientReference: string; createdAt: string; failureReason: string | null }
interface Destination { id: string; type: string; network: string; accountNumber: string; accountName: string; verification: string }

const TABS = [
  "Overview", "POS Register", "Orders & Receipts", "Inventory & Stock", "Restaurant & KDS",
  "Invoices & Quotes", "Payroll & Employees", "Customers & Loyalty", "Business Accounting",
  "Transactions", "Settlements", "Refunds", "Disputes", "Payouts", "Collect"
] as const;

/** Merchant BackOffice & Business Management Dashboard */
export function MerchantDashboard() {
  const [merchants, setMerchants] = useState<MerchantView[]>([]);
  const [current, setCurrent] = useState<string | null>(null);
  const [tab, setTab] = useState<(typeof TABS)[number]>("Overview");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<MerchantView[]>("/v1/merchants").then((m) => { setMerchants(m); setCurrent(m[0]?.id ?? null); })
      .catch((e) => setError(String(e.message ?? e)));
  }, []);

  const merchant = merchants.find((m) => m.id === current);
  return (
    <main>
      <h1>Merchant dashboard</h1>
      {error && <p className="error">{error}</p>}
      {merchants.length > 1 && (
        <select value={current ?? ""} onChange={(e) => setCurrent(e.target.value)}>
          {merchants.map((m) => <option key={m.id} value={m.id}>{m.tradingName} ({m.role})</option>)}
        </select>
      )}
      {merchant && (
        <>
          <p className="muted">{merchant.tradingName} · code {merchant.merchantCode} · {merchant.status} · your role: {merchant.role}</p>
          <nav className="tabs">{TABS.map((t) => <button key={t} className={t === tab ? "active" : ""} onClick={() => setTab(t)}>{t}</button>)}</nav>

          {tab === "Overview" && <Overview id={merchant.id} />}
          {tab === "POS Register" && <PosRegister merchant={merchant} />}
          {tab === "Orders & Receipts" && <OrdersAndReceipts merchantId={merchant.id} />}
          {tab === "Inventory & Stock" && <InventoryAndStock merchantId={merchant.id} />}
          {tab === "Restaurant & KDS" && <RestaurantAndKds merchantId={merchant.id} />}
          {tab === "Invoices & Quotes" && <InvoicesAndQuotes merchantId={merchant.id} />}
          {tab === "Payroll & Employees" && <PayrollAndEmployees merchantId={merchant.id} />}
          {tab === "Customers & Loyalty" && <CustomersAndLoyalty merchantId={merchant.id} />}
          {tab === "Business Accounting" && <BusinessAccounting merchantId={merchant.id} />}
          {tab === "Transactions" && <Transactions id={merchant.id} />}
          {tab === "Settlements" && <Rows id={merchant.id} path="settlements" columns={["reference", "amount", "lines", "createdAt"]} />}
          {tab === "Refunds" && <Rows id={merchant.id} path="refunds" columns={["reference", "checkout", "line", "amount", "status", "reason"]} />}
          {tab === "Disputes" && <Rows id={merchant.id} path="disputes" columns={["checkout", "line", "amount", "reason", "status"]} />}
          {tab === "Payouts" && <Payouts id={merchant.id} />}
          {tab === "Collect" && <Collect merchant={merchant} />}
        </>
      )}
    </main>
  );
}

// -------------------------------------------------------------------------------------------------
// 2. Orders & Receipts View
// -------------------------------------------------------------------------------------------------
function OrdersAndReceipts({ merchantId }: { merchantId: string }) {
  const [filterStatus, setFilterStatus] = useState("all");
  const [searchRef, setSearchRef] = useState("");
  const [selectedReceipt, setSelectedReceipt] = useState<Line | null>(null);
  const [orders, setOrders] = useState<Line[]>([]);

  useEffect(() => {
    api<Line[]>(`/v1/merchants/${merchantId}/transactions`)
      .then(setOrders)
      .catch(() => setOrders([]));
  }, [merchantId]);

  const filtered = orders.filter((o) => {
    const matchStatus = filterStatus === "all" || o.status.toLowerCase() === filterStatus.toLowerCase();
    const matchRef = o.reference.toLowerCase().includes(searchRef.toLowerCase()) ||
                     (o.description && o.description.toLowerCase().includes(searchRef.toLowerCase()));
    return matchStatus && matchRef;
  });

  return (
    <div>
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
            <th>Payer</th>
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
              <td><span className={`tag ${o.status === "settled" ? "active" : "pending"}`}>{o.status}</span></td>
              <td>
                <button className="small secondary" onClick={() => setSelectedReceipt(o)}>View Receipt</button>
              </td>
            </tr>
          ))}
          {filtered.length === 0 && (
            <tr><td colSpan={8} className="muted">No orders match the current filter.</td></tr>
          )}
        </tbody>
      </table>

      {selectedReceipt && (
        <div className="modal-backdrop">
          <div className="modal-content" style={{ maxWidth: "450px" }}>
            <div className="flex-between">
              <h3>Receipt Details</h3>
              <button className="small secondary" onClick={() => setSelectedReceipt(null)}>✕</button>
            </div>
            <p className="mono small">Reference: {selectedReceipt.reference}</p>
            <table className="lines">
              <tbody>
                <tr><td>Item Description</td><td className="num">{selectedReceipt.description || "Sales Item"}</td></tr>
                <tr><td>Gross Amount</td><td className="num">{formatMoney(selectedReceipt.amount)}</td></tr>
                <tr><td>Platform & Processing Fee</td><td className="num">{formatMoney(selectedReceipt.fee)}</td></tr>
                <tr className="total"><td>Merchant Net Receivable</td><td className="num">{formatMoney(selectedReceipt.net)}</td></tr>
              </tbody>
            </table>
            <div style={{ marginTop: "1rem" }}>
              <button onClick={() => window.print()}>Print Receipt</button>
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

function Overview({ id }: { id: string }) {
  const [b, setB] = useState<Balances | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { api<Balances>(`/v1/merchants/${id}/balances`).then(setB).catch((e) => setErr(e.message)); }, [id]);
  if (err) return <p className="muted">{err}</p>;
  if (!b) return <p>Loading…</p>;
  return (
    <div className="cards">
      <div className="card"><div className="muted">Pending settlement</div><div className="big">{formatMoney(b.pending)}</div></div>
      <div className="card"><div className="muted">Available</div><div className="big">{formatMoney(b.available)}</div></div>
      <div className="card"><div className="muted">Reserved (payouts, disputes)</div><div>{formatMoney(b.reserved)}</div></div>
    </div>
  );
}

function Transactions({ id }: { id: string }) {
  const [lines, setLines] = useState<Line[]>([]);
  const [msg, setMsg] = useState<string | null>(null);
  const load = () => api<Line[]>(`/v1/merchants/${id}/transactions`).then(setLines).catch((e) => setMsg(e.message));
  useEffect(() => { void load(); }, [id]);

  async function refund(l: Line) {
    const input = prompt(`Refund amount in GH₵ (max ${formatMoney(l.amount - l.refunded - l.disputed)})`);
    const amount = input ? parseMoney(input) : null;
    const reason = amount ? prompt("Reason") : null;
    if (!amount || !reason) return;
    try {
      await api(`/v1/merchants/${id}/checkouts/${l.reference}/allocations/${l.line}/refunds`,
        { idempotencyKey: newKey(), body: { amount, reason } });
      setMsg("Refund recorded");
      await load();
    } catch (e) { setMsg(e instanceof Error ? e.message : String(e)); }
  }

  return (
    <>
      {msg && <p className="small">{msg}</p>}
      <table className="grid">
        <thead><tr><th>Receipt</th><th>Line</th><th>From</th><th className="num">Amount</th><th className="num">Fee</th>
          <th className="num">Refunded</th><th>Status</th><th /></tr></thead>
        <tbody>{lines.map((l) => (
          <tr key={l.reference + l.line}><td>{l.reference}</td><td>{l.kind} {l.description ?? ""}</td><td>{l.collector}</td>
            <td className="num">{formatMoney(l.amount)}</td><td className="num">{formatMoney(l.fee)}</td>
            <td className="num">{formatMoney(l.refunded)}</td><td>{l.status}</td>
            <td>{l.refunded + l.disputed < l.amount && <button className="small" onClick={() => void refund(l)}>Refund</button>}</td></tr>
        ))}</tbody>
      </table>
    </>
  );
}

function Rows({ id, path, columns }: { id: string; path: string; columns: string[] }) {
  const [rows, setRows] = useState<Record<string, unknown>[]>([]);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { api<Record<string, unknown>[]>(`/v1/merchants/${id}/${path}`).then(setRows).catch((e) => setErr(e.message)); }, [id, path]);
  if (err) return <p className="muted">{err}</p>;
  return (
    <table className="grid">
      <thead><tr>{columns.map((c) => <th key={c}>{c}</th>)}</tr></thead>
      <tbody>{rows.map((r, i) => <tr key={i}>{columns.map((c) => (
        <td key={c} className={c === "amount" ? "num" : ""}>{c === "amount" ? formatMoney(Number(r[c])) : String(r[c] ?? "")}</td>
      ))}</tr>)}</tbody>
    </table>
  );
}

function Payouts({ id }: { id: string }) {
  const [payouts, setPayouts] = useState<Payout[]>([]);
  const [destinations, setDestinations] = useState<Destination[]>([]);
  const [amount, setAmount] = useState("");
  const [destination, setDestination] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const load = async () => {
    setPayouts(await api<Payout[]>(`/v1/merchants/${id}/payouts`));
    const d = await api<Destination[]>(`/v1/merchants/${id}/settlement-destinations`);
    setDestinations(d);
    setDestination(d.find((x) => x.verification === "verified")?.id ?? "");
  };
  useEffect(() => { load().catch((e) => setMsg(e.message)); }, [id]);

  async function withdraw() {
    const pesewas = parseMoney(amount);
    if (!pesewas || !destination) return;
    try {
      await api(`/v1/merchants/${id}/withdrawals`, { idempotencyKey: newKey(), body: { amount: pesewas, destinationId: destination } });
      setMsg("Withdrawal requested");
      await load();
    } catch (e) { setMsg(e instanceof Error ? e.message : String(e)); }
  }

  return (
    <>
      <section className="panel">
        <h3>Withdraw</h3>
        <label>Destination<select value={destination} onChange={(e) => setDestination(e.target.value)}>
          {destinations.map((d) => <option key={d.id} value={d.id} disabled={d.verification !== "verified"}>
            {d.type} {d.network} {d.accountNumber} — {d.accountName} ({d.verification})</option>)}
        </select></label>
        <label>Amount (GH₵)<input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></label>
        <button onClick={() => void withdraw()}>Withdraw</button>
        {msg && <p className="small">{msg}</p>}
      </section>
      <table className="grid">
        <thead><tr><th>Reference</th><th>Item</th><th className="num">Amount</th><th>Status</th><th>Reason</th></tr></thead>
        <tbody>{payouts.map((p) => <tr key={p.reference}><td>{p.reference}</td><td>{p.clientReference}</td>
          <td className="num">{formatMoney(p.amount)}</td><td>{p.status}</td><td>{p.failureReason}</td></tr>)}</tbody>
      </table>
    </>
  );
}

function Collect({ merchant }: { merchant: MerchantView }) {
  const [amount, setAmount] = useState("");
  const [link, setLink] = useState<string | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  useEffect(() => {
    api<{ payload: string }>(`/v1/merchants/${merchant.id}/qr`).then((r) => QRCode.toDataURL(r.payload, { margin: 1, width: 220 }))
      .then(setQr).catch(() => setQr(null));
  }, [merchant.id]);

  async function create() {
    const pesewas = parseMoney(amount);
    if (!pesewas) return;
    try {
      const r = await api<{ payUrl: string }>(`/v1/merchants/${merchant.id}/checkouts`, { idempotencyKey: newKey(), body: { amount: pesewas } });
      setLink(r.payUrl);
    } catch (e) { setMsg(e instanceof Error ? e.message : String(e)); }
  }

  return (
    <div className="cards">
      <section className="panel">
        <h3>Request a payment</h3>
        <label>Amount (GH₵)<input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></label>
        <button onClick={() => void create()}>Create payment link</button>
        {link && <p><a href={link}>{link}</a></p>}
        {msg && <p className="error">{msg}</p>}
      </section>
      <section className="panel">
        <h3>Shop QR · code {merchant.merchantCode}</h3>
        {qr ? <img src={qr} alt={`QR code for merchant ${merchant.merchantCode}`} width={220} height={220} /> : <p className="muted">QR unavailable</p>}
      </section>
    </div>
  );
}
