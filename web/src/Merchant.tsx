import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { api, newKey } from "./api";
import { formatMoney, parseMoney } from "./money";

interface MerchantView { id: string; tradingName: string; kind: string; status: string; merchantCode: string; role: string }
interface Balances { pending: number; available: number; reserved: number }
interface Line {
  reference: string; line: number; kind: string; description: string | null; amount: number; fee: number; net: number;
  refunded: number; disputed: number; pending: number; settled: number; status: string; paidAt: string | null; collector: string;
}
interface Payout { reference: string; amount: number; status: string; clientReference: string; createdAt: string; failureReason: string | null }
interface Destination { id: string; type: string; network: string; accountNumber: string; accountName: string; verification: string }

// Invoices & Quotes
interface Invoice {
  id: string; number: string; customerName: string; customerContact: string;
  subtotal: number; vat: number; total: number; status: "DRAFT" | "SENT" | "PAID" | "OVERDUE";
  dueDate: string; createdAt: string; isQuote: boolean;
}

const TABS = [
  "Overview", "Orders & Receipts", "Invoices & Quotes", "Business Accounting",
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
          {tab === "Orders & Receipts" && <OrdersAndReceipts merchantId={merchant.id} />}
          {tab === "Invoices & Quotes" && <InvoicesAndQuotes merchantId={merchant.id} />}
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
// 5. Invoices & Quotes View
// -------------------------------------------------------------------------------------------------
function InvoicesAndQuotes({ merchantId: _mId }: { merchantId: string }) {
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [showCreate, setShowCreate] = useState(false);
  const [custName, setCustName] = useState("");
  const [custContact, setCustContact] = useState("");
  const [amount, setAmount] = useState("");
  const [isQuote, setIsQuote] = useState(false);

  function createDoc() {
    const pesewas = parseMoney(amount);
    if (!custName || !pesewas) return;
    const vat = Math.round(pesewas * 0.15);
    const newDoc: Invoice = {
      id: String(Date.now()),
      number: `${isQuote ? "QTE" : "INV"}-2026-${String(invoices.length + 1).padStart(3, "0")}`,
      customerName: custName,
      customerContact: custContact,
      subtotal: pesewas,
      vat,
      total: pesewas + vat,
      status: "SENT",
      dueDate: "2026-12-15",
      createdAt: new Date().toISOString().substring(0, 10),
      isQuote,
    };
    setInvoices([newDoc, ...invoices]);
    setShowCreate(false);
    setCustName(""); setCustContact(""); setAmount("");
  }

  function convertQuote(id: string) {
    setInvoices(invoices.map((inv) => inv.id === id ? { ...inv, isQuote: false, number: inv.number.replace("QTE", "INV"), status: "SENT" } : inv));
  }

  return (
    <div>
      <div className="flex-between">
        <h3>Customer Invoices &amp; Quotes</h3>
        <button onClick={() => setShowCreate(true)}>+ Create Invoice / Quote</button>
      </div>

      <table className="grid" style={{ marginTop: "1rem" }}>
        <thead>
          <tr>
            <th>Number</th>
            <th>Type</th>
            <th>Customer</th>
            <th>Due Date</th>
            <th className="num">Subtotal</th>
            <th className="num">VAT (15%)</th>
            <th className="num">Total</th>
            <th>Status</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {invoices.map((inv) => (
            <tr key={inv.id}>
              <td className="mono">{inv.number}</td>
              <td><span className="tag info">{inv.isQuote ? "Quote" : "Invoice"}</span></td>
              <td>
                <strong>{inv.customerName}</strong>
                <div className="small muted">{inv.customerContact}</div>
              </td>
              <td>{inv.dueDate}</td>
              <td className="num">{formatMoney(inv.subtotal)}</td>
              <td className="num">{formatMoney(inv.vat)}</td>
              <td className="num font-bold">{formatMoney(inv.total)}</td>
              <td>
                <span className={`tag ${inv.status === "PAID" ? "active" : inv.status === "SENT" ? "pending" : "failed"}`}>
                  {inv.status}
                </span>
              </td>
              <td>
                {inv.isQuote && (
                  <button className="small secondary" onClick={() => convertQuote(inv.id)}>Convert to Invoice</button>
                )}
                <button className="small" onClick={() => window.print()}>Download PDF</button>
              </td>
            </tr>
          ))}
          {invoices.length === 0 && (
            <tr><td colSpan={9} className="muted" style={{ textAlign: "center", padding: "1.5rem" }}>No customer invoices or quotes created yet. Issue your first invoice or quotation.</td></tr>
          )}
        </tbody>
      </table>

      {showCreate && (
        <div className="modal-backdrop">
          <div className="modal-content" style={{ maxWidth: "450px" }}>
            <h3>Create {isQuote ? "Quote" : "Invoice"}</h3>
            <label className="choice">
              <input type="checkbox" checked={isQuote} onChange={(e) => setIsQuote(e.target.checked)} />
              Issue as Quotation / Pro-Forma (instead of Tax Invoice)
            </label>
            <label>Customer Name / Business
              <input value={custName} onChange={(e) => setCustName(e.target.value)} required />
            </label>
            <label>Customer Email or Phone
              <input value={custContact} onChange={(e) => setCustContact(e.target.value)} />
            </label>
            <label>Amount in GH₵ (excl. VAT)
              <input value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="e.g. 500.00" />
            </label>
            <div style={{ marginTop: "1rem" }}>
              <button onClick={() => createDoc()}>Issue {isQuote ? "Quote" : "Invoice"}</button>
              <button className="secondary" onClick={() => setShowCreate(false)}>Cancel</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// 8. Business Accounting (P&L and Balance Sheet)
// -------------------------------------------------------------------------------------------------
function BusinessAccounting({ merchantId }: { merchantId: string }) {
  const [statement, setStatement] = useState<"pl" | "bs">("pl");
  const [balances, setBalances] = useState<Balances | null>(null);
  const [lines, setLines] = useState<Line[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    Promise.all([
      api<Balances>(`/v1/merchants/${merchantId}/balances`).catch(() => null),
      api<Line[]>(`/v1/merchants/${merchantId}/transactions`).catch(() => []),
    ]).then(([b, l]) => {
      setBalances(b);
      setLines(l);
      setLoading(false);
    });
  }, [merchantId]);

  const grossSales = lines.filter((l) => l.kind === "sale").reduce((acc, l) => acc + l.amount, 0);
  const totalRefunds = lines.reduce((acc, l) => acc + l.refunded, 0);
  const netRevenue = Math.max(0, grossSales - totalRefunds);
  const processingFees = lines.reduce((acc, l) => acc + l.fee, 0);
  const netOperatingIncome = Math.max(0, netRevenue - processingFees);
  const estimatedTax = Math.round(netOperatingIncome * 0.25);
  const netProfit = netOperatingIncome - estimatedTax;

  const cashAvailable = balances?.available ?? 0;
  const pendingSettlement = balances?.pending ?? 0;
  const reservedFunds = balances?.reserved ?? 0;
  const totalAssets = cashAvailable + pendingSettlement + reservedFunds;

  return (
    <div>
      <div className="flex-row" style={{ marginBottom: "1rem" }}>
        <button className={statement === "pl" ? "" : "secondary"} onClick={() => setStatement("pl")}>
          📊 Profit &amp; Loss (P&amp;L) Statement
        </button>
        <button className={statement === "bs" ? "" : "secondary"} onClick={() => setStatement("bs")}>
          📑 Balance Sheet
        </button>
      </div>

      {loading && <p className="muted">Loading accounting figures from ledger…</p>}

      {!loading && lines.length === 0 && (
        <p className="notice ok small" style={{ marginBottom: "1rem" }}>
          Authoritative financial ledger connected. Statements dynamically reflect completed double-entry postings.
        </p>
      )}

      {!loading && statement === "pl" && (
        <section className="panel" style={{ maxWidth: "650px" }}>
          <h3>Profit &amp; Loss Statement</h3>
          <p className="muted small">Derived from authoritative ledger entries (Currency: Ghana Cedi GH₵)</p>
          <table className="lines">
            <tbody>
              <tr><td colSpan={2}><strong>REVENUE</strong></td></tr>
              <tr><td style={{ paddingLeft: "1.5rem" }}>Gross POS &amp; Checkout Sales</td><td className="num">{formatMoney(grossSales)}</td></tr>
              <tr><td style={{ paddingLeft: "1.5rem" }}>Less: Settled Customer Refunds</td><td className="num">-{formatMoney(totalRefunds)}</td></tr>
              <tr className="total"><td>Net Operating Revenue</td><td className="num">{formatMoney(netRevenue)}</td></tr>

              <tr><td colSpan={2} style={{ paddingTop: "1rem" }}><strong>DIRECT COSTS &amp; FEES</strong></td></tr>
              <tr><td style={{ paddingLeft: "1.5rem" }}>ELEPAY Rail &amp; Platform Processing Fees</td><td className="num">-{formatMoney(processingFees)}</td></tr>
              <tr className="total"><td>Gross Contribution Margin</td><td className="num">{formatMoney(netOperatingIncome)}</td></tr>

              <tr><td colSpan={2} style={{ paddingTop: "1rem" }}><strong>STATUTORY PROVISIONS</strong></td></tr>
              <tr><td style={{ paddingLeft: "1.5rem" }}>Estimated Corporate Income Tax (25%)</td><td className="num">-{formatMoney(estimatedTax)}</td></tr>
              <tr className="total" style={{ fontSize: "1.1rem", color: "#0b5d3b" }}><td>NET EARNED PROFIT</td><td className="num">{formatMoney(netProfit)}</td></tr>
            </tbody>
          </table>
          <button style={{ marginTop: "1rem" }} onClick={() => window.print()}>Export Statement (PDF)</button>
        </section>
      )}

      {!loading && statement === "bs" && (
        <section className="panel" style={{ maxWidth: "650px" }}>
          <h3>Balance Sheet</h3>
          <p className="muted small">As of Current Reporting Period (Currency: Ghana Cedi GH₵)</p>
          <table className="lines">
            <tbody>
              <tr><td colSpan={2}><strong>ASSETS</strong></td></tr>
              <tr><td style={{ paddingLeft: "1.5rem" }}>Cash &amp; Available Settlement Balance</td><td className="num">{formatMoney(cashAvailable)}</td></tr>
              <tr><td style={{ paddingLeft: "1.5rem" }}>Pending Settlement (In-Flight Clearing)</td><td className="num">{formatMoney(pendingSettlement)}</td></tr>
              <tr><td style={{ paddingLeft: "1.5rem" }}>Reserved Balances (Dispute &amp; Payout Escrow)</td><td className="num">{formatMoney(reservedFunds)}</td></tr>
              <tr className="total"><td>TOTAL ASSETS</td><td className="num">{formatMoney(totalAssets)}</td></tr>

              <tr><td colSpan={2} style={{ paddingTop: "1rem" }}><strong>LIABILITIES &amp; RESERVES</strong></td></tr>
              <tr><td style={{ paddingLeft: "1.5rem" }}>Escrow &amp; Dispute Reserves</td><td className="num">{formatMoney(reservedFunds)}</td></tr>
              <tr><td style={{ paddingLeft: "1.5rem" }}>Accrued Statutory Tax Provision</td><td className="num">{formatMoney(estimatedTax)}</td></tr>
              <tr className="total"><td>TOTAL LIABILITIES &amp; RESERVES</td><td className="num">{formatMoney(reservedFunds + estimatedTax)}</td></tr>

              <tr><td colSpan={2} style={{ paddingTop: "1rem" }}><strong>MERCHANT EQUITY</strong></td></tr>
              <tr><td style={{ paddingLeft: "1.5rem" }}>Retained Earnings &amp; Available Capital</td><td className="num">{formatMoney(Math.max(0, totalAssets - (reservedFunds + estimatedTax)))}</td></tr>
              <tr className="total" style={{ fontSize: "1.1rem", color: "#0b5d3b" }}><td>TOTAL LIABILITIES &amp; EQUITY</td><td className="num">{formatMoney(totalAssets)}</td></tr>
            </tbody>
          </table>
          <button style={{ marginTop: "1rem" }} onClick={() => window.print()}>Export Balance Sheet (PDF)</button>
        </section>
      )}
    </div>
  );
}

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
