import { useEffect, useState, type ReactNode } from "react";
import QRCode from "qrcode";
import { api, newKey } from "./api";
import { formatMoney, parseMoney } from "./money";
import { BusinessAccounting, CustomersAndLoyalty, FloorPlanView, InventoryAndStock, InvoicesAndQuotes, PayrollAndEmployees, PosRegister } from "./MerchantTools";
import {
  IconArrowLeftRight,
  IconBook,
  IconCheck,
  IconChefHat,
  IconCopy,
  IconExternalLink,
  IconFileText,
  IconHeart,
  IconLandmark,
  IconLayoutGrid,
  IconMonitor,
  IconPackage,
  IconPrinter,
  IconQrCode,
  IconReceipt,
  IconRotateCcw,
  IconSearch,
  IconSend,
  IconShieldAlert,
  IconUsers,
  IconUtensils,
  IconX,
} from "./ui/Icons";

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

type MerchantTab = (typeof TABS)[number];

const NAV_GROUPS: { label: string; items: { tab: MerchantTab; icon: ReactNode }[] }[] = [
  {
    label: "Core",
    items: [
      { tab: "Overview", icon: <IconLayoutGrid size={15} /> },
      { tab: "Collect", icon: <IconQrCode size={15} /> },
    ],
  },
  {
    label: "Point of Sale",
    items: [
      { tab: "POS Register", icon: <IconMonitor size={15} /> },
      { tab: "Restaurant & KDS", icon: <IconUtensils size={15} /> },
      { tab: "Orders & Receipts", icon: <IconReceipt size={15} /> },
    ],
  },
  {
    label: "Operations",
    items: [
      { tab: "Inventory & Stock", icon: <IconPackage size={15} /> },
      { tab: "Invoices & Quotes", icon: <IconFileText size={15} /> },
      { tab: "Customers & Loyalty", icon: <IconHeart size={15} /> },
    ],
  },
  {
    label: "Finance & Team",
    items: [
      { tab: "Transactions", icon: <IconArrowLeftRight size={15} /> },
      { tab: "Settlements", icon: <IconLandmark size={15} /> },
      { tab: "Payouts", icon: <IconSend size={15} /> },
      { tab: "Refunds", icon: <IconRotateCcw size={15} /> },
      { tab: "Disputes", icon: <IconShieldAlert size={15} /> },
      { tab: "Payroll & Employees", icon: <IconUsers size={15} /> },
      { tab: "Business Accounting", icon: <IconBook size={15} /> },
    ],
  },
];

/** Merchant BackOffice & Business Management Dashboard */
export function MerchantDashboard() {
  const [merchants, setMerchants] = useState<MerchantView[]>([]);
  const [current, setCurrent] = useState<string | null>(null);
  const [tab, setTab] = useState<MerchantTab>("Overview");
  const [copiedCode, setCopiedCode] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<MerchantView[]>("/v1/merchants").then((m) => { setMerchants(m); setCurrent(m[0]?.id ?? null); })
      .catch((e) => setError(String(e.message ?? e)));
  }, []);

  const merchant = merchants.find((m) => m.id === current);

  function copyMerchantCode(code: string) {
    void navigator.clipboard?.writeText(code).then(() => {
      setCopiedCode(true);
      window.setTimeout(() => setCopiedCode(false), 1800);
    }).catch(() => {});
  }

  return (
    <main className="workspace-main">
      {error && <p className="notice error" role="alert">{error}</p>}
      {!merchant && !error && (
        <div className="panel" style={{ maxWidth: "480px", margin: "2rem auto", textAlign: "center" }}>
          <p className="muted" style={{ margin: 0 }}>Loading merchant workspace…</p>
        </div>
      )}
      {merchant && (
        <div className="workspace-shell">
          <aside className="workspace-sidebar" aria-label="Merchant navigation">
            <div className="sidebar-context">
              <div className="sidebar-context-title">{merchant.tradingName}</div>
              <div className="sidebar-context-meta">
                <span className={`tag ${merchant.status === "active" ? "active" : "pending"}`}>{merchant.status}</span>
                <button
                  type="button"
                  className="code-chip"
                  title="Copy merchant code"
                  onClick={() => copyMerchantCode(merchant.merchantCode)}
                >
                  {copiedCode ? <IconCheck size={12} /> : <IconCopy size={12} />}
                  {merchant.merchantCode}
                </button>
              </div>
              {merchants.length > 1 && (
                <select
                  aria-label="Switch merchant"
                  value={current ?? ""}
                  onChange={(e) => setCurrent(e.target.value)}
                  style={{ marginTop: "0.65rem", padding: "0.42rem 0.6rem", fontSize: "0.82rem" }}
                >
                  {merchants.map((m) => <option key={m.id} value={m.id}>{m.tradingName} ({m.role})</option>)}
                </select>
              )}
            </div>

            {NAV_GROUPS.map((group) => (
              <div key={group.label} className="sidebar-group">
                <div className="sidebar-group-label">{group.label}</div>
                <nav className="sidebar-nav">
                  {group.items.map((item) => (
                    <button
                      key={item.tab}
                      type="button"
                      className={`sidebar-btn ${tab === item.tab ? "active" : ""}`}
                      onClick={() => setTab(item.tab)}
                    >
                      {item.icon}
                      <span>{item.tab}</span>
                    </button>
                  ))}
                </nav>
              </div>
            ))}
          </aside>

          <section className="workspace-content">
            <div className="page-header">
              <div>
                <h1 style={{ marginBottom: "0.15rem" }}>{tab}</h1>
                <p className="page-header-subtitle">
                  {merchant.tradingName} · Code <span className="mono">{merchant.merchantCode}</span> · Role: <strong>{merchant.role}</strong>
                </p>
              </div>
              <div className="flex-row" style={{ gap: "0.45rem" }}>
                {tab !== "POS Register" && (
                  <button type="button" className="secondary small" onClick={() => setTab("POS Register")}>
                    <IconMonitor size={14} /> Open POS
                  </button>
                )}
                {tab !== "Collect" && (
                  <button type="button" className="small" onClick={() => setTab("Collect")}>
                    <IconQrCode size={14} /> Collect Payment
                  </button>
                )}
              </div>
            </div>

            {tab === "Overview" && <Overview id={merchant.id} onNavigate={setTab} />}
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
          </section>
        </div>
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
      <div className="panel" style={{ padding: "0.85rem 1rem", marginBottom: "0.85rem" }}>
        <div className="flex-between">
          <div className="flex-row" style={{ flex: 1 }}>
            <div className="pos-search-wrap" style={{ margin: 0, flex: "1 1 240px", maxWidth: "340px" }}>
              <IconSearch size={15} />
              <input
                placeholder="Search receipt ref or item…"
                value={searchRef}
                onChange={(e) => setSearchRef(e.target.value)}
              />
            </div>
            <select
              aria-label="Filter by status"
              value={filterStatus}
              onChange={(e) => setFilterStatus(e.target.value)}
              style={{ width: "auto", margin: 0 }}
            >
              <option value="all">All Orders</option>
              <option value="settled">Settled</option>
              <option value="pending">Pending</option>
              <option value="refunded">Refunded</option>
            </select>
          </div>
          <span className="small muted">{filtered.length} receipt{filtered.length === 1 ? "" : "s"}</span>
        </div>
      </div>

      <div className="table-wrap">
        <table className="grid">
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
                <td className="mono small">{o.reference}</td>
                <td><strong>{o.kind}</strong> <span className="muted">{o.description || "Order item"}</span></td>
                <td>{o.collector}</td>
                <td className="num mono">{formatMoney(o.amount)}</td>
                <td className="num mono muted">{formatMoney(o.fee)}</td>
                <td className="num mono" style={{ fontWeight: 600 }}>{formatMoney(o.net)}</td>
                <td><span className={`tag ${o.status === "settled" ? "active" : "pending"}`}>{o.status}</span></td>
                <td>
                  <button className="small secondary" onClick={() => setSelectedReceipt(o)}>View Receipt</button>
                </td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <tr><td colSpan={8} className="muted" style={{ textAlign: "center", padding: "1.5rem" }}>No orders match the current filter.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {selectedReceipt && (
        <div className="modal-backdrop">
          <div className="modal-content" style={{ maxWidth: "460px" }}>
            <div className="flex-between" style={{ marginBottom: "0.75rem" }}>
              <h3 style={{ margin: 0 }}>Receipt Details</h3>
              <button type="button" className="small ghost" aria-label="Close" onClick={() => setSelectedReceipt(null)}>
                <IconX size={16} />
              </button>
            </div>
            <p className="mono small muted" style={{ marginBottom: "0.85rem" }}>Reference: {selectedReceipt.reference}</p>
            <div className="checkout-summary-box">
              <table className="lines">
                <tbody>
                  <tr><td>Item Description</td><td className="num">{selectedReceipt.description || "Sales Item"}</td></tr>
                  <tr><td>Gross Amount</td><td className="num mono">{formatMoney(selectedReceipt.amount)}</td></tr>
                  <tr><td>Platform &amp; Processing Fee</td><td className="num mono">{formatMoney(selectedReceipt.fee)}</td></tr>
                  <tr className="total"><td>Merchant Net Receivable</td><td className="num mono">{formatMoney(selectedReceipt.net)}</td></tr>
                </tbody>
              </table>
            </div>
            <div className="flex-row" style={{ justifyContent: "flex-end" }}>
              <button type="button" className="secondary" onClick={() => setSelectedReceipt(null)}>Close</button>
              <button type="button" onClick={() => window.print()}>
                <IconPrinter size={15} /> Print Receipt
              </button>
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
      <div className="flex-between" style={{ marginBottom: "1rem" }}>
        <div className="segmented" role="tablist">
          <button type="button" className={subTab === "floor" ? "active" : ""} onClick={() => setSubTab("floor")}>
            <IconUtensils size={14} /> Table Floor Plan
          </button>
          <button type="button" className={subTab === "kds" ? "active" : ""} onClick={() => setSubTab("kds")}>
            <IconChefHat size={14} /> Kitchen Display System (KDS)
          </button>
        </div>
        <a href="/kitchen" target="_blank" rel="noreferrer" className="code-chip" style={{ textDecoration: "none" }}>
          <IconExternalLink size={13} /> Launch Full-Screen KDS
        </a>
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
    if (s.output === "PRINTER") return { label: "Printer only", tone: "info" };
    if (!s.lastSeenAt) return { label: "Not connected yet", tone: "pending" };
    const mins = Math.round((Date.now() - Date.parse(s.lastSeenAt)) / 60000);
    return mins < 3 ? { label: "Online", tone: "active" } : { label: `Last seen ${mins}m ago`, tone: "pending" };
  };

  return (
    <div>
      <div className="panel" style={{ background: "linear-gradient(135deg, #f2f9f5 0%, #ffffff 100%)", borderColor: "var(--ok-border)" }}>
        <div className="flex-between">
          <div>
            <h3 style={{ marginBottom: "0.25rem" }}>Live Kitchen Display</h3>
            <p className="small muted" style={{ margin: 0 }}>
              Open <a href="/kitchen" target="_blank" rel="noreferrer"><strong>{window.location.origin}/kitchen</strong></a> on a kitchen tablet or screen.
              Works offline when internet drops and syncs automatically on reconnect.
            </p>
          </div>
          <a href="/kitchen" target="_blank" rel="noreferrer">
            <button type="button" className="small"><IconExternalLink size={14} /> Open /kitchen</button>
          </a>
        </div>
      </div>

      {error && <p className="notice error" role="alert">{error}</p>}

      <div className="panel" style={{ marginTop: "1rem" }}>
        <h3>Paired Kitchen Devices</h3>
        <p className="muted small">Pair a tablet or printer phone once; it stays signed in to the kitchen display after restarts.</p>
        <div className="flex-row" style={{ marginBottom: "0.75rem" }}>
          <select value={pairStation} onChange={(e) => setPairStation(e.target.value)} style={{ maxWidth: "18rem", margin: 0 }}>
            <option value="">Station: choose on the device</option>
            {stations.map((s) => <option key={s.id} value={s.id}>Station: {s.name}</option>)}
          </select>
          <button type="button" style={{ margin: 0 }} onClick={() => void newPairingCode()}>Pair a kitchen device</button>
        </div>
        {pairing && (
          <div className="kitchen-pair-codes">
            <p style={{ marginBottom: "0.5rem" }}>On the device, open <strong>{window.location.origin}/kitchen</strong> and enter:</p>
            <dl className="confirm" style={{ maxWidth: "360px" }}>
              <dt>Merchant code</dt><dd className="big-code" style={{ textAlign: "left" }}>{pairing.merchantCode}</dd>
              <dt>Pairing code</dt><dd className="big-code" style={{ textAlign: "left" }}>{pairing.code.slice(0, 3)} {pairing.code.slice(3)}</dd>
            </dl>
            <p className="muted small" style={{ margin: 0 }}>Single-use code, expires at {new Date(pairing.expiresAt).toLocaleTimeString("en-GH", { hour: "2-digit", minute: "2-digit" })}.</p>
          </div>
        )}
        <div className="table-wrap" style={{ margin: "0.75rem 0 0" }}>
          <table className="grid">
            <thead><tr><th>Device</th><th>Paired</th><th /></tr></thead>
            <tbody>
              {devices.map((d) => (
                <tr key={d.id}>
                  <td><strong>{d.label}</strong></td>
                  <td className="small muted">{new Date(d.createdAt).toLocaleDateString("en-GH")}</td>
                  <td className="num">
                    {d.revokedAt ? <span className="tag failed">removed</span>
                      : <button className="secondary small" onClick={() => void revokeDevice(d.id)}>Remove</button>}
                  </td>
                </tr>
              ))}
              {devices.length === 0 && <tr><td colSpan={3} className="muted small">No paired devices yet.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>

      <div className="panel" style={{ marginTop: "1rem" }}>
        <h3>Stations</h3>
        <div className="table-wrap" style={{ margin: 0 }}>
          <table className="grid">
            <thead><tr><th>Station</th><th>Output</th><th>Printer</th><th>Status</th><th /></tr></thead>
            <tbody>
              {stations.map((s) => {
                const st = screenState(s);
                return (
                  <tr key={s.id}>
                    <td><strong>{s.name}</strong>{s.isDefault && <span className="tag info" style={{ marginLeft: "0.45rem" }}>default</span>}</td>
                    <td>{s.output === "BOTH" ? "Screen + printer" : s.output === "PRINTER" ? "Printer" : "Screen"}</td>
                    <td className="small mono">{s.printerAddress ?? "—"}{s.printerAddress && ` · ${s.paperWidth} cols`}</td>
                    <td><span className={`tag ${st.tone}`}>{st.label}</span></td>
                    <td className="num"><button className="secondary small" onClick={() => void remove(s.id)}>Remove</button></td>
                  </tr>
                );
              })}
              {stations.length === 0 && <tr><td colSpan={5} className="muted">No stations yet. Without stations every order shows on every kitchen screen.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>

      <form onSubmit={add} className="panel" style={{ marginTop: "1rem" }}>
        <h3>Add a station</h3>
        <div className="grid-2">
          <label>Name<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Kitchen, Grill, Drinks…" required /></label>
          <label>Shows orders on
            <select value={form.output} onChange={(e) => setForm({ ...form, output: e.target.value as KitchenStation["output"] })}>
              <option value="SCREEN">Screen</option><option value="BOTH">Screen and printer</option><option value="PRINTER">Printer only</option>
            </select>
          </label>
          <label>Printer address {form.output === "SCREEN" && <span className="muted small">(optional fallback)</span>}
            <input className="mono" value={form.printerAddress} onChange={(e) => setForm({ ...form, printerAddress: e.target.value })}
              placeholder="tcp://192.168.1.50:9100" required={form.output !== "SCREEN"} />
          </label>
          <label>Paper width
            <select value={form.paperWidth} onChange={(e) => setForm({ ...form, paperWidth: Number(e.target.value) })}>
              <option value={32}>58mm (32 characters)</option><option value={42}>80mm (42 characters)</option><option value={48}>80mm wide (48 characters)</option>
            </select>
          </label>
        </div>
        <label className="choice" style={{ marginTop: "0.5rem" }}>
          <input type="checkbox" checked={form.isDefault} onChange={(e) => setForm({ ...form, isDefault: e.target.checked })} />
          Default station for items without an assigned station
        </label>
        <div style={{ marginTop: "0.75rem" }}>
          <button type="submit">Add station</button>
        </div>
      </form>
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// Merchant Views (Overview, Transactions, Rows, Payouts, Collect)
// -------------------------------------------------------------------------------------------------

function Overview({ id, onNavigate }: { id: string; onNavigate: (tab: MerchantTab) => void }) {
  const [b, setB] = useState<Balances | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { api<Balances>(`/v1/merchants/${id}/balances`).then(setB).catch((e) => setErr(e.message)); }, [id]);
  if (err) return <p className="notice error">{err}</p>;
  if (!b) return <p className="muted">Loading balances…</p>;
  return (
    <>
      <div className="kpi-grid">
        <div className="kpi-card primary">
          <div className="kpi-label">Available Balance</div>
          <div className="kpi-value">{formatMoney(b.available)}</div>
          <div className="kpi-meta">Ready for instant withdrawal</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-label">Pending Settlement</div>
          <div className="kpi-value">{formatMoney(b.pending)}</div>
          <div className="kpi-meta">Clearing into available balance</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-label">Reserved</div>
          <div className="kpi-value">{formatMoney(b.reserved)}</div>
          <div className="kpi-meta">Payouts in progress &amp; active disputes</div>
        </div>
      </div>

      <div className="panel" style={{ marginTop: "1rem" }}>
        <h3>Quick Actions</h3>
        <div className="flex-row">
          <button type="button" onClick={() => onNavigate("POS Register")}>
            <IconMonitor size={15} /> Open POS Register
          </button>
          <button type="button" className="secondary" onClick={() => onNavigate("Collect")}>
            <IconQrCode size={15} /> Create Payment Link
          </button>
          <button type="button" className="secondary" onClick={() => onNavigate("Payouts")}>
            <IconSend size={15} /> Request Withdrawal
          </button>
          <button type="button" className="secondary" onClick={() => onNavigate("Restaurant & KDS")}>
            <IconChefHat size={15} /> Floor Plan &amp; KDS
          </button>
        </div>
      </div>
    </>
  );
}

export function validateRefundInput(
  amountInput: string,
  reason: string,
  maxPesewas: number,
): { pesewas: number } | { error: string } {
  const pesewas = parseMoney(amountInput);
  if (pesewas === null || pesewas <= 0) {
    return { error: "Enter a valid refund amount, e.g. 10.00." };
  }
  if (pesewas > maxPesewas) {
    return { error: `Refund amount exceeds available balance (${formatMoney(maxPesewas)}).` };
  }
  if (!reason.trim()) {
    return { error: "Enter a refund reason." };
  }
  return { pesewas };
}

function Transactions({ id }: { id: string }) {
  const [lines, setLines] = useState<Line[]>([]);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refundTarget, setRefundTarget] = useState<Line | null>(null);
  const [refundAmount, setRefundAmount] = useState("");
  const [refundReason, setRefundReason] = useState("");
  const [refundError, setRefundError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = () => api<Line[]>(`/v1/merchants/${id}/transactions`)
    .then((res) => { setLines(res); setError(null); })
    .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  useEffect(() => { void load(); }, [id]);

  function openRefundModal(l: Line) {
    setRefundTarget(l);
    setRefundAmount("");
    setRefundReason("");
    setRefundError(null);
  }

  async function submitRefund(e: React.FormEvent) {
    e.preventDefault();
    if (!refundTarget || busy) return;
    const maxPesewas = refundTarget.amount - refundTarget.refunded - refundTarget.disputed;
    const validated = validateRefundInput(refundAmount, refundReason, maxPesewas);
    if ("error" in validated) {
      setRefundError(validated.error);
      return;
    }
    setRefundError(null);
    setMsg(null);
    setBusy(true);
    try {
      await api(`/v1/merchants/${id}/checkouts/${refundTarget.reference}/allocations/${refundTarget.line}/refunds`,
        { idempotencyKey: newKey(), body: { amount: validated.pesewas, reason: refundReason.trim() } });
      setMsg("Refund recorded");
      setRefundTarget(null);
      await load();
    } catch (err) {
      setRefundError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {error && <p className="notice error" role="alert">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}
      <div className="table-wrap">
        <table className="grid">
          <thead><tr><th>Receipt</th><th>Line</th><th>From</th><th className="num">Amount</th><th className="num">Fee</th>
            <th className="num">Refunded</th><th>Status</th><th /></tr></thead>
          <tbody>
            {lines.map((l) => (
              <tr key={l.reference + l.line}>
                <td className="mono small">{l.reference}</td>
                <td><strong>{l.kind}</strong> <span className="muted">{l.description ?? ""}</span></td>
                <td>{l.collector}</td>
                <td className="num mono">{formatMoney(l.amount)}</td>
                <td className="num mono muted">{formatMoney(l.fee)}</td>
                <td className="num mono">{formatMoney(l.refunded)}</td>
                <td><span className={`tag ${l.status === "settled" ? "active" : "pending"}`}>{l.status}</span></td>
                <td className="num">{l.refunded + l.disputed < l.amount && <button className="small secondary" onClick={() => openRefundModal(l)}>Refund</button>}</td>
              </tr>
            ))}
            {lines.length === 0 && (
              <tr><td colSpan={8} className="muted" style={{ textAlign: "center", padding: "1.5rem" }}>No transactions found.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {refundTarget && (
        <div className="modal-backdrop">
          <div className="modal-content" style={{ maxWidth: "460px" }}>
            <div className="flex-between" style={{ marginBottom: "0.75rem" }}>
              <h3 style={{ margin: 0 }}>Issue Refund</h3>
              <button type="button" className="small ghost" aria-label="Close" disabled={busy} onClick={() => setRefundTarget(null)}>
                <IconX size={16} />
              </button>
            </div>
            <p className="small muted">
              Receipt <code className="mono">{refundTarget.reference}</code> · Max refundable:{" "}
              <strong className="mono">{formatMoney(refundTarget.amount - refundTarget.refunded - refundTarget.disputed)}</strong>
            </p>
            {refundError && <p className="notice error" role="alert">{refundError}</p>}
            <form onSubmit={(e) => void submitRefund(e)}>
              <label>Refund Amount (GH₵)
                <input
                  className="mono"
                  inputMode="decimal"
                  value={refundAmount}
                  onChange={(e) => setRefundAmount(e.target.value)}
                  placeholder="0.00"
                  required
                />
              </label>
              <label>Reason
                <input
                  value={refundReason}
                  onChange={(e) => setRefundReason(e.target.value)}
                  placeholder="e.g. Customer returned item"
                  required
                />
              </label>
              <div className="flex-row" style={{ justifyContent: "flex-end", marginTop: "1rem" }}>
                <button type="button" className="secondary" disabled={busy} onClick={() => setRefundTarget(null)}>Cancel</button>
                <button type="submit" disabled={busy}>{busy ? "Recording…" : "Submit Refund"}</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </>
  );
}

function Rows({ id, path, columns }: { id: string; path: string; columns: string[] }) {
  const [rows, setRows] = useState<Record<string, unknown>[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setErr(null);
    api<Record<string, unknown>[]>(`/v1/merchants/${id}/${path}`)
      .then((res) => { if (!cancelled) setRows(res); })
      .catch((e) => { if (!cancelled) setErr(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [id, path]);
  if (err) return <p className="notice error" role="alert">{err}</p>;
  return (
    <div className="table-wrap">
      <table className="grid">
        <thead><tr>{columns.map((c) => <th key={c} className={c === "amount" ? "num" : ""}>{c}</th>)}</tr></thead>
        <tbody>
          {loading && (
            <tr><td colSpan={columns.length} className="muted" style={{ textAlign: "center", padding: "1.5rem" }}>Loading {path}…</td></tr>
          )}
          {!loading && rows.length === 0 && (
            <tr><td colSpan={columns.length} className="muted" style={{ textAlign: "center", padding: "1.5rem" }}>No {path} found.</td></tr>
          )}
          {!loading && rows.map((r, i) => <tr key={i}>{columns.map((c) => (
            <td key={c} className={c === "amount" ? "num mono" : c === "reference" || c === "checkout" ? "mono small" : ""}>
              {c === "amount" ? formatMoney(Number(r[c])) : c === "status" ? <span className={`tag ${String(r[c]).toLowerCase()}`}>{String(r[c] ?? "")}</span> : String(r[c] ?? "")}
            </td>
          ))}</tr>)}
        </tbody>
      </table>
    </div>
  );
}

function Payouts({ id }: { id: string }) {
  const [payouts, setPayouts] = useState<Payout[]>([]);
  const [destinations, setDestinations] = useState<Destination[]>([]);
  const [amount, setAmount] = useState("");
  const [destination, setDestination] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    setPayouts(await api<Payout[]>(`/v1/merchants/${id}/payouts`));
    const d = await api<Destination[]>(`/v1/merchants/${id}/settlement-destinations`);
    setDestinations(d);
    setDestination((prev) => prev && d.some((x) => x.id === prev && x.verification === "verified")
      ? prev
      : (d.find((x) => x.verification === "verified")?.id ?? ""));
  };
  useEffect(() => { load().catch((e) => setError(e instanceof Error ? e.message : String(e))); }, [id]);

  async function withdraw() {
    if (busy) return;
    setError(null);
    setMsg(null);
    if (!destination) {
      setError("Select a verified settlement destination before withdrawing.");
      return;
    }
    const pesewas = parseMoney(amount);
    if (!pesewas || pesewas <= 0) {
      setError("Enter a valid withdrawal amount, e.g. 50.00.");
      return;
    }
    setBusy(true);
    try {
      await api(`/v1/merchants/${id}/withdrawals`, { idempotencyKey: newKey(), body: { amount: pesewas, destinationId: destination } });
      setMsg("Withdrawal requested");
      setAmount("");
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <section className="panel" style={{ marginBottom: "1rem" }}>
        <h3>Request Withdrawal</h3>
        <div className="grid-2">
          <label>Destination<select value={destination} onChange={(e) => setDestination(e.target.value)}>
            <option value="">-- Select verified destination --</option>
            {destinations.map((d) => <option key={d.id} value={d.id} disabled={d.verification !== "verified"}>
              {d.type} {d.network} {d.accountNumber} — {d.accountName} ({d.verification})</option>)}
          </select></label>
          <label>Amount (GH₵)<input className="mono" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="50.00" /></label>
        </div>
        <button disabled={busy} onClick={() => void withdraw()}>
          <IconSend size={15} />
          {busy ? "Submitting…" : "Withdraw Funds"}
        </button>
        {error && <p className="notice error" role="alert">{error}</p>}
        {msg && <p className="notice ok">{msg}</p>}
      </section>
      <div className="table-wrap">
        <table className="grid">
          <thead><tr><th>Reference</th><th>Item</th><th className="num">Amount</th><th>Status</th><th>Reason</th></tr></thead>
          <tbody>
            {payouts.map((p) => (
              <tr key={p.reference}>
                <td className="mono small">{p.reference}</td>
                <td>{p.clientReference}</td>
                <td className="num mono" style={{ fontWeight: 600 }}>{formatMoney(p.amount)}</td>
                <td><span className={`tag ${p.status === "paid" || p.status === "completed" ? "active" : p.status === "failed" ? "failed" : "pending"}`}>{p.status}</span></td>
                <td className="small muted">{p.failureReason ?? "—"}</td>
              </tr>
            ))}
            {payouts.length === 0 && (
              <tr><td colSpan={5} className="muted" style={{ textAlign: "center", padding: "1.5rem" }}>No payouts recorded yet.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}

function Collect({ merchant }: { merchant: MerchantView }) {
  const [amount, setAmount] = useState("");
  const [link, setLink] = useState<string | null>(null);
  const [copiedLink, setCopiedLink] = useState(false);
  const [qr, setQr] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<{ payload: string }>(`/v1/merchants/${merchant.id}/qr`).then((r) => QRCode.toDataURL(r.payload, { margin: 1, width: 220 }))
      .then(setQr).catch(() => setQr(null));
  }, [merchant.id]);

  async function create() {
    if (busy) return;
    setMsg(null);
    const pesewas = parseMoney(amount);
    if (!pesewas || pesewas <= 0) {
      setMsg("Enter a valid payment amount, e.g. 25.00.");
      return;
    }
    setBusy(true);
    try {
      const r = await api<{ payUrl: string }>(`/v1/merchants/${merchant.id}/checkouts`, { idempotencyKey: newKey(), body: { amount: pesewas } });
      setLink(r.payUrl);
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid-2">
      <section className="panel">
        <h3>Request a Payment</h3>
        <p className="small muted">Generate a hosted ELEPAY checkout link for MoMo, Card, GhQR, or Wallet payment.</p>
        <label>Amount (GH₵)<input className="mono" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="25.00" /></label>
        <button disabled={busy} onClick={() => void create()}>{busy ? "Creating…" : "Create Payment Link"}</button>
        {link && (
          <div className="notice ok" style={{ marginTop: "1rem" }}>
            <div className="flex-between">
              <strong className="small">Payment link ready</strong>
              <button
                type="button"
                className="code-chip"
                onClick={() => {
                  void navigator.clipboard?.writeText(link).then(() => {
                    setCopiedLink(true);
                    window.setTimeout(() => setCopiedLink(false), 1800);
                  }).catch(() => {});
                }}
              >
                {copiedLink ? <><IconCheck size={12} /> Copied</> : <><IconCopy size={12} /> Copy link</>}
              </button>
            </div>
            <a href={link} className="mono small" style={{ wordBreak: "break-all" }}>{link}</a>
          </div>
        )}
        {msg && <p className="notice error" role="alert">{msg}</p>}
      </section>
      <section className="panel" style={{ textAlign: "center" }}>
        <h3>Shop QR Code</h3>
        <p className="small muted">Merchant Code <strong className="mono">{merchant.merchantCode}</strong></p>
        {qr ? (
          <div className="qr-frame" style={{ margin: "0.75rem auto 0", maxWidth: "260px" }}>
            <img src={qr} alt={`QR code for merchant ${merchant.merchantCode}`} width={200} height={200} />
          </div>
        ) : (
          <p className="muted">QR unavailable</p>
        )}
      </section>
    </div>
  );
}
