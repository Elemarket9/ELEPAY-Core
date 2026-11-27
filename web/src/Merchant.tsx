import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import QRCode from "qrcode";
import { api, newKey } from "./api";
import { formatMoney, parseMoney } from "./money";
import { BusinessAccounting, CustomersAndLoyalty, FloorPlanView, InventoryAndStock, InvoicesAndQuotes, PayrollAndEmployees, PosRegister } from "./MerchantTools";
import {
  buildDailyReportCsvFilename,
  filterLiveOrdersLocally,
  formatDailyReportCsv,
  formatDurationSeconds,
  formatOrderAge,
  formatOrderTypeLabel,
  formatQuantity,
  formatTenderLabel,
  kitchenStatusTone,
  paymentStatusTone,
  todayInAccra,
  type BranchView,
  type DailyReport,
  type LiveOrderPage,
  type LiveOrderSummary,
} from "./pos/reportingHelpers";
import {
  IconArrowLeftRight,
  IconBook,
  IconCheck,
  IconChefHat,
  IconClock,
  IconCopy,
  IconDownload,
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
  IconRefresh,
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
interface Destination {
  id: string;
  type: string;
  network: string;
  accountNumber: string;
  accountName: string;
  verification: string;
  coolingOffUntil?: string | null;
  oobVerificationStatus?: string | null;
}

function isDestinationReady(d: Destination): boolean {
  if (d.verification !== "verified") return false;
  if (d.coolingOffUntil && new Date(d.coolingOffUntil).getTime() > Date.now()) return false;
  if (d.oobVerificationStatus && d.oobVerificationStatus !== "confirmed") return false;
  return true;
}

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
  const [loaded, setLoaded] = useState(false);
  const [current, setCurrent] = useState<string | null>(null);
  const [tab, setTab] = useState<MerchantTab>("Overview");
  const [copiedCode, setCopiedCode] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<MerchantView[]>("/v1/merchants")
      .then((m) => { setMerchants(m); setCurrent(m[0]?.id ?? null); })
      .catch((e) => setError(String(e.message ?? e)))
      .finally(() => setLoaded(true));
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
      {!loaded && !error && (
        <div className="panel" style={{ maxWidth: "480px", margin: "2rem auto", textAlign: "center" }}>
          <p className="muted" style={{ margin: 0 }}>Loading merchant workspace…</p>
        </div>
      )}
      {loaded && !merchant && !error && (
        <div className="panel" style={{ maxWidth: "480px", margin: "2rem auto", textAlign: "center" }}>
          <h3 style={{ marginTop: 0 }}>No merchant workspace</h3>
          <p className="muted" style={{ marginBottom: 0 }}>
            No merchant accounts are linked to your profile yet. Ask your business owner to add you, or complete merchant onboarding.
          </p>
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
// 2. Live Orders & Receipts View (Phase 6)
// -------------------------------------------------------------------------------------------------
interface PosOrderItemDetail {
  id: string;
  itemName: string;
  quantity: number;
  unitPricePesewas: number;
  discountPesewas: number;
  taxPesewas: number;
  totalPesewas: number;
  seatNumber?: number | null;
  course?: string | null;
  notes?: string | null;
}

interface PosReceiptDetail {
  id: string;
  receiptNumber: string;
  orderId: string;
  escposFormattedText: string;
  createdAt: string;
}

function OrdersAndReceipts({ merchantId }: { merchantId: string }) {
  const [branches, setBranches] = useState<BranchView[]>([]);
  const [branchId, setBranchId] = useState("");
  const [filterStatus, setFilterStatus] = useState("ALL");
  const [filterType, setFilterType] = useState("ALL");
  const [searchQuery, setSearchQuery] = useState("");
  const [autoRefresh, setAutoRefresh] = useState(true);

  const [orders, setOrders] = useState<LiveOrderSummary[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [fetchedAtMs, setFetchedAtMs] = useState(() => Date.now());
  const [nowMs, setNowMs] = useState(() => Date.now());

  const [selectedOrder, setSelectedOrder] = useState<LiveOrderSummary | null>(null);
  const [orderItems, setOrderItems] = useState<PosOrderItemDetail[]>([]);
  const [orderReceipt, setOrderReceipt] = useState<PosReceiptDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [generatingReceipt, setGeneratingReceipt] = useState(false);

  useEffect(() => {
    api<BranchView[]>(`/v1/merchants/${merchantId}/branches`)
      .then((list) => setBranches(list.filter((b) => b.active)))
      .catch(() => setBranches([]));
  }, [merchantId]);

  const loadOrders = useCallback(
    async (opts: { silent?: boolean; cursor?: string | null } = {}) => {
      const { silent = false, cursor = null } = opts;
      if (cursor) setLoadingMore(true);
      else if (!silent) setLoading(true);

      const params = new URLSearchParams({ merchantId, limit: "50" });
      if (branchId) params.set("branchId", branchId);
      if (filterStatus !== "ALL") params.set("status", filterStatus);
      if (filterType !== "ALL") params.set("type", filterType);
      if (cursor) params.set("cursor", cursor);

      try {
        const page = await api<LiveOrderPage>(`/v1/pos/orders?${params.toString()}`);
        setOrders((prev) => {
          if (!cursor) return page.orders;
          const seen = new Set(prev.map((o) => o.id));
          return [...prev, ...page.orders.filter((o) => !seen.has(o.id))];
        });
        setNextCursor(page.nextCursor);
        setHasMore(page.hasMore);
        setFetchedAtMs(Date.now());
        setLoadError(null);
      } catch (e) {
        if (!cursor) setOrders([]);
        setLoadError(e instanceof Error ? e.message : String(e));
      } finally {
        setLoading(false);
        setLoadingMore(false);
      }
    },
    [merchantId, branchId, filterStatus, filterType],
  );

  useEffect(() => {
    void loadOrders();
  }, [loadOrders]);

  useEffect(() => {
    if (!autoRefresh) return;
    const timer = window.setInterval(() => {
      void loadOrders({ silent: true });
    }, 10_000);
    return () => window.clearInterval(timer);
  }, [autoRefresh, loadOrders]);

  useEffect(() => {
    const tick = window.setInterval(() => setNowMs(Date.now()), 15_000);
    return () => window.clearInterval(tick);
  }, []);

  async function openOrderDetail(order: LiveOrderSummary) {
    setSelectedOrder(order);
    setOrderItems([]);
    setOrderReceipt(null);
    setDetailError(null);
    setDetailLoading(true);
    try {
      const items = await api<PosOrderItemDetail[]>(`/v1/pos/orders/${order.id}/items`);
      setOrderItems(items);
      if (order.paymentStatus === "PAID") {
        try {
          const rec = await api<PosReceiptDetail>(`/v1/pos/orders/${order.id}/receipt`);
          setOrderReceipt(rec);
        } catch {
          setOrderReceipt(null);
        }
      }
    } catch (e) {
      setDetailError(e instanceof Error ? e.message : String(e));
    } finally {
      setDetailLoading(false);
    }
  }

  async function generateReceipt(orderId: string) {
    setGeneratingReceipt(true);
    setDetailError(null);
    try {
      const rec = await api<PosReceiptDetail>(`/v1/pos/orders/${orderId}/receipt`, { method: "POST" });
      setOrderReceipt(rec);
    } catch (e) {
      setDetailError(e instanceof Error ? e.message : String(e));
    } finally {
      setGeneratingReceipt(false);
    }
  }

  const branchNameById = new Map(branches.map((b) => [b.id, b.name]));
  const filtered = filterLiveOrdersLocally(orders, searchQuery);
  const elapsedSec = Math.max(0, Math.floor((nowMs - fetchedAtMs) / 1000));

  return (
    <div>
      {loadError && <p className="notice error" role="alert">Could not load live orders: {loadError}</p>}
      <div className="panel" style={{ padding: "0.85rem 1rem", marginBottom: "0.85rem" }}>
        <div className="flex-between" style={{ flexWrap: "wrap", gap: "0.65rem" }}>
          <div className="flex-row" style={{ flex: 1, flexWrap: "wrap", gap: "0.5rem" }}>
            <div className="pos-search-wrap" style={{ margin: 0, flex: "1 1 200px", maxWidth: "280px" }}>
              <IconSearch size={15} />
              <input
                placeholder="Search order #, table, server…"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
            </div>
            {branches.length > 0 && (
              <select
                aria-label="Filter by branch"
                value={branchId}
                onChange={(e) => setBranchId(e.target.value)}
                style={{ width: "auto", margin: 0 }}
              >
                <option value="">All branches</option>
                {branches.map((b) => (
                  <option key={b.id} value={b.id}>{b.name}</option>
                ))}
              </select>
            )}
            <select
              aria-label="Filter by payment status"
              value={filterStatus}
              onChange={(e) => setFilterStatus(e.target.value)}
              style={{ width: "auto", margin: 0 }}
            >
              <option value="ALL">All statuses</option>
              <option value="OPEN">Open (Pending + Partial)</option>
              <option value="PENDING">Pending</option>
              <option value="PARTIAL">Partial</option>
              <option value="PAID">Paid</option>
              <option value="VOIDED">Voided</option>
            </select>
            <select
              aria-label="Filter by order type"
              value={filterType}
              onChange={(e) => setFilterType(e.target.value)}
              style={{ width: "auto", margin: 0 }}
            >
              <option value="ALL">All types</option>
              <option value="DINE_IN">Dine-in</option>
              <option value="TAKEAWAY">Takeaway</option>
              <option value="DELIVERY">Delivery</option>
              <option value="STANDARD">Counter</option>
            </select>
          </div>
          <div className="flex-row" style={{ gap: "0.5rem", alignItems: "center" }}>
            <button
              type="button"
              className={`small ${autoRefresh ? "" : "secondary"}`}
              onClick={() => setAutoRefresh((v) => !v)}
              title="Toggle 10-second live refresh"
            >
              <IconClock size={13} /> {autoRefresh ? "Live (10s)" : "Auto-refresh off"}
            </button>
            <button
              type="button"
              className="small secondary"
              disabled={loading}
              onClick={() => void loadOrders()}
            >
              <IconRefresh size={13} /> {loading ? "Loading…" : "Refresh"}
            </button>
            <span className="small muted">{filtered.length} order{filtered.length === 1 ? "" : "s"}</span>
          </div>
        </div>
      </div>

      <div className="table-wrap">
        <table className="grid">
          <thead>
            <tr>
              <th>Order #</th>
              <th>Type</th>
              <th>Table</th>
              <th>Server</th>
              <th className="num">Items</th>
              <th className="num">Total</th>
              <th className="num">Paid</th>
              <th>Payment</th>
              <th>Kitchen</th>
              <th>Age</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((o) => (
              <tr
                key={o.id}
                onClick={() => void openOrderDetail(o)}
                style={{ cursor: "pointer" }}
              >
                <td className="mono small" style={{ fontWeight: 700 }}>{o.orderNumber}</td>
                <td>{formatOrderTypeLabel(o.orderType)}</td>
                <td>{o.tableNumber ? <strong>Table {o.tableNumber}</strong> : <span className="muted">—</span>}</td>
                <td>{o.serverName || <span className="muted">—</span>}</td>
                <td className="num mono">{o.itemCount}</td>
                <td className="num mono" style={{ fontWeight: 600 }}>{formatMoney(o.totalPesewas)}</td>
                <td className="num mono muted">{formatMoney(o.paidPesewas)}</td>
                <td>
                  <span className={`tag ${paymentStatusTone(o.paymentStatus)}`}>{o.paymentStatus}</span>
                </td>
                <td>
                  <span className={`tag ${kitchenStatusTone(o.kitchenStatus)}`}>{o.kitchenStatus}</span>
                </td>
                <td className="mono small muted">{formatOrderAge(o.ageSeconds + elapsedSec)}</td>
                <td onClick={(e) => e.stopPropagation()}>
                  <button type="button" className="small secondary" onClick={() => void openOrderDetail(o)}>
                    Open
                  </button>
                </td>
              </tr>
            ))}
            {filtered.length === 0 && !loading && (
              <tr>
                <td colSpan={11} className="muted" style={{ textAlign: "center", padding: "1.5rem" }}>
                  No live orders match the current filter.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {hasMore && nextCursor && (
        <div style={{ display: "flex", justifyContent: "center", marginTop: "0.85rem" }}>
          <button
            type="button"
            className="secondary"
            disabled={loadingMore}
            onClick={() => void loadOrders({ cursor: nextCursor })}
          >
            {loadingMore ? "Loading more…" : "Load more orders"}
          </button>
        </div>
      )}

      {selectedOrder && (
        <div className="modal-backdrop">
          <div className="modal-content" style={{ maxWidth: "560px" }}>
            <div className="flex-between" style={{ marginBottom: "0.75rem" }}>
              <div>
                <h3 style={{ margin: 0 }}>Order {selectedOrder.orderNumber}</h3>
                <p className="small muted" style={{ margin: "0.2rem 0 0" }}>
                  {formatOrderTypeLabel(selectedOrder.orderType)}
                  {selectedOrder.tableNumber ? ` · Table ${selectedOrder.tableNumber}` : ""}
                  {selectedOrder.branchId && branchNameById.has(selectedOrder.branchId)
                    ? ` · ${branchNameById.get(selectedOrder.branchId)}`
                    : ""}
                  {selectedOrder.serverName ? ` · ${selectedOrder.serverName}` : ""}
                </p>
              </div>
              <button type="button" className="small ghost" aria-label="Close" onClick={() => setSelectedOrder(null)}>
                <IconX size={16} />
              </button>
            </div>

            <div className="flex-row" style={{ gap: "0.5rem", marginBottom: "0.85rem", flexWrap: "wrap" }}>
              <span className={`tag ${paymentStatusTone(selectedOrder.paymentStatus)}`}>
                Payment: {selectedOrder.paymentStatus}
              </span>
              <span className={`tag ${kitchenStatusTone(selectedOrder.kitchenStatus)}`}>
                Kitchen: {selectedOrder.kitchenStatus} ({selectedOrder.kdsTicketCount} ticket{selectedOrder.kdsTicketCount === 1 ? "" : "s"})
              </span>
              <span className="small muted">Age: {formatOrderAge(selectedOrder.ageSeconds + elapsedSec)}</span>
            </div>

            {detailError && <p className="notice error" role="alert">{detailError}</p>}
            {detailLoading && <p className="muted small">Loading order items…</p>}

            {!detailLoading && orderItems.length > 0 && (
              <div className="table-wrap" style={{ marginBottom: "0.85rem" }}>
                <table className="grid">
                  <thead>
                    <tr>
                      <th>Item</th>
                      <th className="num">Qty</th>
                      <th className="num">Price</th>
                      <th className="num">Total</th>
                    </tr>
                  </thead>
                  <tbody>
                    {orderItems.map((it) => (
                      <tr key={it.id}>
                        <td>
                          <strong>{it.itemName}</strong>
                          {(it.course || it.seatNumber) && (
                            <div className="small muted">
                              {it.course ? it.course : ""}
                              {it.course && it.seatNumber ? " · " : ""}
                              {it.seatNumber ? `Seat ${it.seatNumber}` : ""}
                            </div>
                          )}
                        </td>
                        <td className="num mono">{formatQuantity(it.quantity)}</td>
                        <td className="num mono">{formatMoney(it.unitPricePesewas)}</td>
                        <td className="num mono">{formatMoney(it.totalPesewas)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <div className="checkout-summary-box">
              <table className="lines">
                <tbody>
                  <tr><td>Subtotal</td><td className="num mono">{formatMoney(selectedOrder.subtotalPesewas)}</td></tr>
                  {selectedOrder.discountPesewas > 0 && (
                    <tr><td>Discount</td><td className="num mono">-{formatMoney(selectedOrder.discountPesewas)}</td></tr>
                  )}
                  {selectedOrder.taxPesewas > 0 && (
                    <tr><td>Tax</td><td className="num mono">{formatMoney(selectedOrder.taxPesewas)}</td></tr>
                  )}
                  {selectedOrder.tipPesewas > 0 && (
                    <tr><td>Tip</td><td className="num mono">{formatMoney(selectedOrder.tipPesewas)}</td></tr>
                  )}
                  <tr className="total"><td>Total</td><td className="num mono">{formatMoney(selectedOrder.totalPesewas)}</td></tr>
                  <tr><td>Paid</td><td className="num mono">{formatMoney(selectedOrder.paidPesewas)}</td></tr>
                  {selectedOrder.balancePesewas > 0 && (
                    <tr><td>Balance Due</td><td className="num mono" style={{ fontWeight: 700 }}>{formatMoney(selectedOrder.balancePesewas)}</td></tr>
                  )}
                </tbody>
              </table>
            </div>

            {orderReceipt && (
              <div style={{ marginTop: "0.75rem" }}>
                <div className="small muted" style={{ marginBottom: "0.35rem" }}>
                  Receipt <span className="mono">{orderReceipt.receiptNumber}</span>
                </div>
                <pre
                  className="mono small"
                  style={{
                    background: "#f8faf9",
                    border: "1px solid var(--line, #dfe6e2)",
                    borderRadius: "8px",
                    padding: "0.65rem",
                    maxHeight: "200px",
                    overflow: "auto",
                    margin: 0,
                  }}
                >
                  {orderReceipt.escposFormattedText}
                </pre>
              </div>
            )}

            <div className="flex-row" style={{ justifyContent: "flex-end", marginTop: "0.9rem", gap: "0.5rem" }}>
              <button type="button" className="secondary" onClick={() => setSelectedOrder(null)}>Close</button>
              {selectedOrder.paymentStatus === "PAID" && !orderReceipt && (
                <button
                  type="button"
                  className="secondary"
                  disabled={generatingReceipt}
                  onClick={() => void generateReceipt(selectedOrder.id)}
                >
                  <IconReceipt size={15} /> {generatingReceipt ? "Generating…" : "Generate Receipt"}
                </button>
              )}
              {selectedOrder.paymentStatus === "PAID" && (
                <button type="button" onClick={() => window.print()}>
                  <IconPrinter size={15} /> Print Receipt
                </button>
              )}
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
  useEffect(() => {
    setErr(null);
    api<Balances>(`/v1/merchants/${id}/balances`)
      .then(setB)
      .catch((e) => setErr(e instanceof Error ? e.message : String(e)));
  }, [id]);

  return (
    <>
      {err && <p className="notice error" role="alert">{err}</p>}
      {!b && !err && <p className="muted">Loading balances…</p>}
      {b && (
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
      )}

      <TodayReportDashboard merchantId={id} />

      <div className="panel" style={{ marginTop: "1rem" }}>
        <h3>Quick Actions</h3>
        <div className="flex-row">
          <button type="button" onClick={() => onNavigate("POS Register")}>
            <IconMonitor size={15} /> Open POS Register
          </button>
          <button type="button" className="secondary" onClick={() => onNavigate("Orders & Receipts")}>
            <IconReceipt size={15} /> Live Orders
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

function TodayReportDashboard({ merchantId }: { merchantId: string }) {
  const [branches, setBranches] = useState<BranchView[]>([]);
  const [branchId, setBranchId] = useState("");
  const [date, setDate] = useState(() => todayInAccra());
  const [report, setReport] = useState<DailyReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [topItemsTab, setTopItemsTab] = useState<"revenue" | "quantity">("revenue");

  useEffect(() => {
    api<BranchView[]>(`/v1/merchants/${merchantId}/branches`)
      .then((list) => setBranches(list.filter((br) => br.active)))
      .catch(() => setBranches([]));
  }, [merchantId]);

  const loadReport = useCallback(async () => {
    setLoading(true);
    setError(null);
    const params = new URLSearchParams({ merchantId, date });
    if (branchId) params.set("branchId", branchId);
    try {
      const data = await api<DailyReport>(`/v1/pos/reports/daily?${params.toString()}`);
      setReport(data);
    } catch (e) {
      setReport(null);
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [merchantId, branchId, date]);

  useEffect(() => {
    void loadReport();
  }, [loadReport]);

  const branchNameById = new Map(branches.map((br) => [br.id, br.name]));
  const isBranchScopedByServer = Boolean(!branchId && report?.branchId);
  const effectiveBranchId = branchId || report?.branchId || null;
  const effectiveBranchName = effectiveBranchId ? (branchNameById.get(effectiveBranchId) ?? null) : null;

  function exportCsv() {
    if (!report) return;
    const csv = formatDailyReportCsv(report, effectiveBranchName);
    const blob = new Blob(["\uFEFF" + csv], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = buildDailyReportCsvFilename(report.businessDate, effectiveBranchName);
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }

  const totalTenderAmount = report
    ? report.tenderMix.reduce((sum, t) => sum + t.amountPesewas, 0)
    : 0;
  const topItems = report
    ? (topItemsTab === "revenue" ? report.topItemsByRevenue : report.topItemsByQuantity)
    : [];

  return (
    <div style={{ marginTop: "1rem" }}>
      <div className="panel" style={{ padding: "0.9rem 1rem", marginBottom: "0.85rem" }}>
        <div className="flex-between" style={{ flexWrap: "wrap", gap: "0.75rem" }}>
          <div>
            <h3 style={{ margin: 0 }}>
              Today&apos;s Operations{" "}
              <span className="small muted" style={{ fontWeight: 500 }}>
                ({report?.businessDate ?? date} · {report?.timezone ?? "Africa/Accra"})
              </span>
            </h3>
            {isBranchScopedByServer && (
              <p className="small muted" style={{ margin: "0.2rem 0 0" }}>
                Branch-scoped view: <strong>{effectiveBranchName ?? report?.branchId}</strong>
              </p>
            )}
          </div>
          <div className="flex-row" style={{ flexWrap: "wrap", gap: "0.5rem", alignItems: "center" }}>
            {branches.length > 0 && !isBranchScopedByServer && (
              <select
                aria-label="Report branch"
                value={branchId}
                onChange={(e) => setBranchId(e.target.value)}
                style={{ width: "auto", margin: 0 }}
              >
                <option value="">All branches</option>
                {branches.map((br) => (
                  <option key={br.id} value={br.id}>{br.name}</option>
                ))}
              </select>
            )}
            <input
              type="date"
              aria-label="Report business date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              style={{ width: "auto", margin: 0 }}
            />
            <button
              type="button"
              className="small secondary"
              disabled={loading}
              onClick={() => void loadReport()}
            >
              <IconRefresh size={13} /> {loading ? "Loading…" : "Refresh"}
            </button>
            <button
              type="button"
              className="small"
              disabled={!report || loading}
              onClick={exportCsv}
            >
              <IconDownload size={13} /> Export CSV
            </button>
          </div>
        </div>
      </div>

      {error && <p className="notice error" role="alert">Could not load daily report: {error}</p>}
      {loading && !report && <p className="muted small">Loading daily report…</p>}

      {report && (
        <>
          <div className="kpi-grid">
            <div className="kpi-card primary">
              <div className="kpi-label">Net Sales</div>
              <div className="kpi-value">{formatMoney(report.sales.netSalesPesewas)}</div>
              <div className="kpi-meta">
                Gross {formatMoney(report.sales.grossSalesPesewas)} · {report.sales.paidOrderCount} paid / {report.sales.openOrderCount} open
              </div>
            </div>
            <div className="kpi-card">
              <div className="kpi-label">Total Collected</div>
              <div className="kpi-value">{formatMoney(report.sales.totalCollectedPesewas)}</div>
              <div className="kpi-meta">
                Tax {formatMoney(report.sales.taxPesewas)} · Tips {formatMoney(report.sales.tipsPesewas)}
              </div>
            </div>
            <div className="kpi-card">
              <div className="kpi-label">Discounts &amp; Voids</div>
              <div className="kpi-value">{formatMoney(report.sales.discountsPesewas + report.sales.voidsPesewas)}</div>
              <div className="kpi-meta">
                Discounts {formatMoney(report.sales.discountsPesewas)} · Voids {formatMoney(report.sales.voidsPesewas)} ({report.sales.voidedOrderCount} voided)
              </div>
            </div>
            <div className="kpi-card">
              <div className="kpi-label">Avg Kitchen Time</div>
              <div className="kpi-value">{formatDurationSeconds(report.kitchenTiming.avgFiredToReadySeconds)}</div>
              <div className="kpi-meta">
                Fired→Ready · Ready→Served {formatDurationSeconds(report.kitchenTiming.avgReadyToServedSeconds)} ({report.kitchenTiming.ticketCount} tickets)
              </div>
            </div>
          </div>

          <div className="grid-2" style={{ marginTop: "1rem", alignItems: "start" }}>
            <div className="panel" style={{ margin: 0 }}>
              <h3 style={{ marginTop: 0 }}>Tender Breakdown</h3>
              <div className="table-wrap" style={{ margin: 0 }}>
                <table className="grid">
                  <thead>
                    <tr>
                      <th>Tender</th>
                      <th className="num">Count</th>
                      <th className="num">Amount</th>
                      <th className="num">Share</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.tenderMix.map((t) => {
                      const pct = totalTenderAmount > 0 ? Math.round((t.amountPesewas / totalTenderAmount) * 100) : 0;
                      return (
                        <tr key={t.tenderType}>
                          <td><strong>{formatTenderLabel(t.tenderType)}</strong></td>
                          <td className="num mono">{t.count}</td>
                          <td className="num mono">{formatMoney(t.amountPesewas)}</td>
                          <td className="num mono muted">{pct}%</td>
                        </tr>
                      );
                    })}
                    {report.tenderMix.length === 0 && (
                      <tr><td colSpan={4} className="muted" style={{ textAlign: "center" }}>No tenders recorded.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="panel" style={{ margin: 0 }}>
              <h3 style={{ marginTop: 0 }}>Orders by Type &amp; AOV</h3>
              <div className="table-wrap" style={{ margin: 0 }}>
                <table className="grid">
                  <thead>
                    <tr>
                      <th>Order Type</th>
                      <th className="num">Orders</th>
                      <th className="num">Total</th>
                      <th className="num">Avg Order</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.orderTypes.map((ot) => (
                      <tr key={ot.orderType}>
                        <td><strong>{formatOrderTypeLabel(ot.orderType)}</strong></td>
                        <td className="num mono">{ot.orderCount}</td>
                        <td className="num mono">{formatMoney(ot.totalPesewas)}</td>
                        <td className="num mono">{formatMoney(ot.averageOrderValuePesewas)}</td>
                      </tr>
                    ))}
                    {report.orderTypes.length === 0 && (
                      <tr><td colSpan={4} className="muted" style={{ textAlign: "center" }}>No orders recorded.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <div className="panel" style={{ marginTop: "1rem" }}>
            <div className="flex-between" style={{ marginBottom: "0.65rem", flexWrap: "wrap", gap: "0.5rem" }}>
              <h3 style={{ margin: 0 }}>Shift Cash Reconciliation</h3>
              <span className="small muted">
                {report.shiftCash.openShiftCount} open · {report.shiftCash.closedShiftCount} closed
              </span>
            </div>
            <div className="kpi-grid" style={{ marginBottom: report.shiftCash.shifts.length > 0 ? "0.85rem" : 0 }}>
              <div className="kpi-card">
                <div className="kpi-label">Expected Cash</div>
                <div className="kpi-value">{formatMoney(report.shiftCash.totalExpectedCashPesewas)}</div>
              </div>
              <div className="kpi-card">
                <div className="kpi-label">Counted Cash</div>
                <div className="kpi-value">{formatMoney(report.shiftCash.totalCountedCashPesewas)}</div>
              </div>
              <div className="kpi-card">
                <div className="kpi-label">Cash Variance</div>
                <div
                  className="kpi-value"
                  style={{
                    color:
                      report.shiftCash.totalVariancePesewas === 0
                        ? "var(--ok, #0a6e46)"
                        : "var(--danger, #b42318)",
                  }}
                >
                  {formatMoney(report.shiftCash.totalVariancePesewas)}
                </div>
              </div>
            </div>
            {report.shiftCash.shifts.length > 0 && (
              <div className="table-wrap" style={{ margin: 0 }}>
                <table className="grid">
                  <thead>
                    <tr>
                      <th>Cashier</th>
                      <th>Branch</th>
                      <th>Status</th>
                      <th className="num">Opening</th>
                      <th className="num">Expected</th>
                      <th className="num">Counted</th>
                      <th className="num">Variance</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.shiftCash.shifts.map((s) => (
                      <tr key={s.shiftId}>
                        <td>{s.cashierName || <span className="muted">—</span>}</td>
                        <td>{s.branchId ? (branchNameById.get(s.branchId) ?? s.branchId.slice(0, 8)) : "—"}</td>
                        <td><span className={`tag ${s.status === "CLOSED" ? "active" : "info"}`}>{s.status}</span></td>
                        <td className="num mono">{formatMoney(s.openingBalancePesewas)}</td>
                        <td className="num mono">{formatMoney(s.expectedCashPesewas)}</td>
                        <td className="num mono">
                          {s.countedCashPesewas !== null ? formatMoney(s.countedCashPesewas) : "—"}
                        </td>
                        <td className="num mono">
                          {s.variancePesewas !== null ? formatMoney(s.variancePesewas) : "—"}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="grid-2" style={{ marginTop: "1rem", alignItems: "start" }}>
            <div className="panel" style={{ margin: 0 }}>
              <div className="flex-between" style={{ marginBottom: "0.65rem", flexWrap: "wrap", gap: "0.5rem" }}>
                <h3 style={{ margin: 0 }}>Top 10 Items</h3>
                <div className="segmented" role="tablist">
                  <button
                    type="button"
                    className={topItemsTab === "revenue" ? "active" : ""}
                    onClick={() => setTopItemsTab("revenue")}
                  >
                    By Revenue
                  </button>
                  <button
                    type="button"
                    className={topItemsTab === "quantity" ? "active" : ""}
                    onClick={() => setTopItemsTab("quantity")}
                  >
                    By Quantity
                  </button>
                </div>
              </div>
              <div className="table-wrap" style={{ margin: 0 }}>
                <table className="grid">
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Item</th>
                      <th className="num">Qty</th>
                      <th className="num">Revenue</th>
                    </tr>
                  </thead>
                  <tbody>
                    {topItems.map((it, idx) => (
                      <tr key={`${topItemsTab}-${it.itemName}-${idx}`}>
                        <td className="mono small muted">{idx + 1}</td>
                        <td><strong>{it.itemName}</strong></td>
                        <td className="num mono">{formatQuantity(it.quantity)}</td>
                        <td className="num mono">{formatMoney(it.revenuePesewas)}</td>
                      </tr>
                    ))}
                    {topItems.length === 0 && (
                      <tr><td colSpan={4} className="muted" style={{ textAlign: "center" }}>No item sales recorded.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="panel" style={{ margin: 0 }}>
              <div className="flex-between" style={{ marginBottom: "0.65rem" }}>
                <h3 style={{ margin: 0 }}>Voids</h3>
                <span className="small muted">{report.voids.length} entr{report.voids.length === 1 ? "y" : "ies"}</span>
              </div>
              <div className="table-wrap" style={{ margin: 0 }}>
                <table className="grid">
                  <thead>
                    <tr>
                      <th>Order #</th>
                      <th>Item</th>
                      <th className="num">Amount</th>
                      <th>Reason</th>
                      <th>By / Approved</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.voids.map((v, idx) => (
                      <tr key={`${v.orderId}-${v.orderItemId ?? "order"}-${idx}`}>
                        <td className="mono small">{v.orderNumber}</td>
                        <td>
                          {v.itemName ?? <span className="muted">Entire Order</span>}
                          {v.quantity !== null && v.quantity !== undefined && (
                            <span className="small muted"> ×{formatQuantity(v.quantity)}</span>
                          )}
                        </td>
                        <td className="num mono">{formatMoney(v.amountPesewas)}</td>
                        <td className="small">{v.reason}</td>
                        <td className="small muted">
                          {v.voidedBy || "—"}
                          {v.approvedBy ? ` / ${v.approvedBy}` : ""}
                        </td>
                      </tr>
                    ))}
                    {report.voids.length === 0 && (
                      <tr><td colSpan={5} className="muted" style={{ textAlign: "center" }}>No voids recorded for this date.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </>
      )}
    </div>
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

  const refundKeyRef = useRef<{ sig: string; key: string } | null>(null);

  function openRefundModal(l: Line) {
    refundKeyRef.current = null;
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
    const sig = `${refundTarget.reference}:${refundTarget.line}:${validated.pesewas}:${refundReason.trim()}`;
    if (refundKeyRef.current?.sig !== sig) {
      refundKeyRef.current = { sig, key: newKey() };
    }
    setRefundError(null);
    setMsg(null);
    setBusy(true);
    try {
      await api(`/v1/merchants/${id}/checkouts/${refundTarget.reference}/allocations/${refundTarget.line}/refunds`,
        { idempotencyKey: refundKeyRef.current.key, body: { amount: validated.pesewas, reason: refundReason.trim() } });
      refundKeyRef.current = null;
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
  const [oobCodes, setOobCodes] = useState<Record<string, string>>({});
  const [oobBusyId, setOobBusyId] = useState<string | null>(null);
  const withdrawKeyRef = useRef<{ sig: string; key: string } | null>(null);

  const load = async () => {
    setPayouts(await api<Payout[]>(`/v1/merchants/${id}/payouts`));
    const d = await api<Destination[]>(`/v1/merchants/${id}/settlement-destinations`);
    setDestinations(d);
    setDestination((prev) => prev && d.some((x) => x.id === prev && isDestinationReady(x))
      ? prev
      : (d.find((x) => isDestinationReady(x))?.id ?? ""));
  };
  useEffect(() => { load().catch((e) => setError(e instanceof Error ? e.message : String(e))); }, [id]);

  async function confirmDestinationOob(destId: string) {
    const code = (oobCodes[destId] ?? "").trim();
    if (code.length < 6) {
      setError("Enter the 6-digit confirmation code sent to your email and SMS.");
      return;
    }
    setError(null);
    setMsg(null);
    setOobBusyId(destId);
    try {
      await api(`/v1/merchants/${id}/settlement-destinations/${destId}/confirm-oob`, {
        body: { token: code },
      });
      setOobCodes((prev) => ({ ...prev, [destId]: "" }));
      setMsg("Settlement destination confirmed via Email/SMS code.");
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setOobBusyId(null);
    }
  }

  async function resendDestinationOob(destId: string) {
    setError(null);
    setMsg(null);
    setOobBusyId(destId);
    try {
      await api(`/v1/merchants/${id}/settlement-destinations/${destId}/resend-oob`, {
        method: "POST",
      });
      setMsg("Fresh confirmation code sent to your merchant email and SMS.");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setOobBusyId(null);
    }
  }

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
    const sig = `${destination}:${pesewas}`;
    if (withdrawKeyRef.current?.sig !== sig) {
      withdrawKeyRef.current = { sig, key: newKey() };
    }
    setBusy(true);
    try {
      await api(`/v1/merchants/${id}/withdrawals`, { idempotencyKey: withdrawKeyRef.current.key, body: { amount: pesewas, destinationId: destination } });
      withdrawKeyRef.current = null;
      setMsg("Withdrawal requested");
      setAmount("");
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const pendingOobDestinations = destinations.filter(
    (d) => d.oobVerificationStatus && d.oobVerificationStatus !== "confirmed",
  );

  return (
    <>
      {pendingOobDestinations.length > 0 && (
        <section className="panel" style={{ marginBottom: "1rem" }}>
          <h3>Confirm Settlement Destination (Email &amp; SMS Code)</h3>
          <p className="small muted">
            Enter the 6-digit verification code sent to your merchant contact email and phone to activate withdrawals.
          </p>
          {pendingOobDestinations.map((d) => (
            <div
              key={d.id}
              style={{
                display: "flex",
                flexWrap: "wrap",
                alignItems: "center",
                gap: "0.6rem",
                padding: "0.65rem 0",
                borderTop: "1px solid var(--line)",
              }}
            >
              <div style={{ flex: "1 1 220px" }}>
                <strong>{d.type} {d.network} {d.accountNumber}</strong>
                <div className="small muted">{d.accountName} · Status: {d.oobVerificationStatus}</div>
              </div>
              <input
                className="mono"
                inputMode="numeric"
                maxLength={6}
                placeholder="6-digit code"
                value={oobCodes[d.id] ?? ""}
                onChange={(e) => setOobCodes((prev) => ({
                  ...prev,
                  [d.id]: e.target.value.replace(/\D/g, "").slice(0, 6),
                }))}
                style={{ width: 140 }}
              />
              <button
                type="button"
                disabled={oobBusyId === d.id}
                onClick={() => void confirmDestinationOob(d.id)}
              >
                {oobBusyId === d.id ? "Confirming…" : "Confirm Code"}
              </button>
              <button
                type="button"
                className="secondary"
                disabled={oobBusyId === d.id}
                onClick={() => void resendDestinationOob(d.id)}
              >
                Resend Code
              </button>
            </div>
          ))}
        </section>
      )}
      <section className="panel" style={{ marginBottom: "1rem" }}>
        <h3>Request Withdrawal</h3>
        <div className="grid-2">
          <label>Destination<select value={destination} onChange={(e) => setDestination(e.target.value)}>
            <option value="">-- Select verified destination --</option>
            {destinations.map((d) => {
              const ready = isDestinationReady(d);
              const cooling = d.coolingOffUntil && new Date(d.coolingOffUntil).getTime() > Date.now();
              const statusNote = d.verification !== "verified"
                ? d.verification
                : cooling
                  ? "cooling-off active"
                  : d.oobVerificationStatus && d.oobVerificationStatus !== "confirmed"
                    ? `OOB ${d.oobVerificationStatus}`
                    : d.verification;
              return (
                <option key={d.id} value={d.id} disabled={!ready}>
                  {d.type} {d.network} {d.accountNumber} — {d.accountName} ({statusNote})
                </option>
              );
            })}
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
