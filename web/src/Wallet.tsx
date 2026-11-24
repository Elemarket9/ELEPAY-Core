import { useEffect, useRef, useState } from "react";
import { api, ApiError, newKey } from "./api";
import { formatMoney, normalizeGhanaPhone, parseMoney } from "./money";

interface Account { code: string; bucket: string; balance: number }
interface Lookup {
  status: string; rail: string; destination: string; verifiedName: string | null;
  confirmationId: string | null; expiresAt: string | null; message: string | null;
}
interface Preview { verifiedName: string; destination: string; amount: number; fee: number; total: number; expiresAt: string; rail: string }
interface Operation { reference: string; kind: string; amount: number; status: string; counterparty: string | null; createdAt: string }

const WALLET_TABS = ["Send Money", "Airtime & Data", "Pay Bills", "Virtual Cards", "Savings Goals", "Export Statements"] as const;

/**
 * Customer web wallet with Send Money, Airtime/Data top-ups, Bill payments,
 * Virtual card controls, Savings goals, and Receipt/Statement exports.
 */
export function Wallet() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [operations, setOperations] = useState<Operation[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [moduleDisabled, setModuleDisabled] = useState(false);
  const loadSequence = useRef(0);
  const [activeTab, setActiveTab] = useState<(typeof WALLET_TABS)[number]>("Send Money");

  async function load() {
    const sequence = ++loadSequence.current;
    setAccounts([]); setOperations([]);
    setError(null);
    setModuleDisabled(false);
    setLoading(true);
    try {
      const w = await api<{ accounts: Account[] }>("/v1/wallet");
      if (sequence !== loadSequence.current) return;
      setAccounts(w.accounts);
      const history = await api<Operation[]>("/v1/wallet/operations");
      if (sequence === loadSequence.current) setOperations(history);
    } catch (e) {
      if (sequence !== loadSequence.current) return;
      if (e instanceof ApiError && e.code === "FEATURE_NOT_ENABLED") {
        setModuleDisabled(true);
        setAccounts([]);
        setOperations([]);
      } else {
        setError(e instanceof ApiError && e.status === 404 ? "No ELEPAY wallet is linked to this sign-in yet." : String(e));
      }
    } finally {
      if (sequence === loadSequence.current) setLoading(false);
    }
  }
  useEffect(() => { void load(); }, []);

  const bal = (bucket: string): string => {
    if (loading) return "Loading…";
    const account = accounts.find((a) => a.bucket === bucket);
    return account ? formatMoney(account.balance) : "Unavailable";
  };

  return (
    <main>
      <div className="page-title"><div><p className="eyebrow">PERSONAL WALLET</p><h1>My wallet</h1><p className="muted">Your balances and transaction activity.</p></div><button className="secondary" disabled={loading} onClick={() => void load()}>{loading ? "Refreshing…" : "Refresh balances"}</button></div>
      {moduleDisabled && (
        <p className="notice warn">Consumer wallet module is disabled in this environment (Stage 1).</p>
      )}
      {error && <p className="error" role="alert">{error}</p>}
      <div className="cards wallet-balances" aria-busy={loading}>
        <div className="card balance-primary"><div className="muted">Available</div><div className="big">{bal("available")}</div></div>
        <div className="card"><div className="muted">Pending</div><div>{bal("pending")}</div></div>
        <div className="card"><div className="muted">Reserved</div><div>{bal("reserved")}</div></div>
      </div>

      <nav className="tabs" aria-label="Wallet services">
        {WALLET_TABS.map((t) => (
          <button key={t} aria-pressed={t === activeTab} className={t === activeTab ? "active" : ""} onClick={() => setActiveTab(t)}>
            {t}
          </button>
        ))}
      </nav>

      {!moduleDisabled && activeTab === "Send Money" && <SendMoney onSent={() => void load()} />}
      {activeTab === "Airtime & Data" && <AirtimeAndData />}
      {activeTab === "Pay Bills" && <PayBills />}
      {activeTab === "Virtual Cards" && <VirtualCards />}
      {activeTab === "Savings Goals" && <SavingsGoals />}
      {activeTab === "Export Statements" && <ExportStatements operations={operations} />}

      <h2 style={{ marginTop: "2rem" }}>Recent Activity</h2>
      <div className="table-scroll" tabIndex={0} role="region" aria-label="Recent wallet transactions"><table className="grid">
        <thead>
          <tr>
            <th>Reference</th>
            <th>Type</th>
            <th>Counterparty</th>
            <th className="num">Amount</th>
            <th>Status</th>
            <th>Action</th>
          </tr>
        </thead>
        <tbody>
          {operations.map((o) => (
            <tr key={o.reference}>
              <td className="mono small">{o.reference}</td>
              <td>{o.kind}</td>
              <td>{o.counterparty ?? "—"}</td>
              <td className="num">{formatMoney(o.amount)}</td>
              <td><span className={`tag ${transactionStatusClass(o.status)}`}>{o.status}</span></td>
              <td>
                <button
                  className="small secondary"
                  onClick={() => downloadTransactionSummary(o)}
                >
                  Transaction summary
                </button>
              </td>
            </tr>
          ))}
          {operations.length === 0 && (
            <tr><td colSpan={6} className="muted">{loading ? "Loading…" : "No transactions found."}</td></tr>
          )}
        </tbody>
      </table></div>
    </main>
  );
}

// -------------------------------------------------------------------------------------------------
// 1. Send Money (Core Wallet)
// -------------------------------------------------------------------------------------------------
export function transactionStatusClass(status: string): string {
  switch (status.toLowerCase()) {
    case "completed": case "success": case "succeeded": case "settled": case "paid": return "active";
    case "failed": case "declined": case "cancelled": case "expired": case "rejected": return "failed";
    default: return "pending";
  }
}

function SendMoney({ onSent }: { onSent: () => void }) {
  const [rail, setRail] = useState("wallet");
  const [phone, setPhone] = useState("");
  const [network, setNetwork] = useState("mtn");
  const [lookup, setLookup] = useState<Lookup | null>(null);
  const [amount, setAmount] = useState("");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [key, setKey] = useState(newKey());
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const actionLock = useRef(false);

  function edit(next: string) {
    setPhone(next);
    setLookup(null);
    setPreview(null);
  }

  async function check() {
    if (actionLock.current) return;
    const msisdn = normalizeGhanaPhone(phone);
    if (!msisdn) { setMessage("Enter the full number, e.g. 024 123 4567 or +233 24 123 4567"); return; }
    actionLock.current = true;
    setBusy(true);
    try {
      setLookup(await api<Lookup>("/v1/wallet/recipients/lookup", { body: { rail, phone: msisdn, network } }));
      setMessage(null);
    } catch (e) { setMessage(e instanceof Error ? e.message : String(e)); }
    finally { actionLock.current = false; setBusy(false); }
  }

  async function review() {
    if (actionLock.current) return;
    const pesewas = parseMoney(amount);
    if (!lookup?.confirmationId || !pesewas) { setMessage("Enter an amount"); return; }
    actionLock.current = true;
    setBusy(true);
    try {
      setPreview(await api<Preview>("/v1/wallet/transfers/preview", { body: { confirmationId: lookup.confirmationId, amount: pesewas } }));
      setKey(newKey());
      setMessage(null);
    } catch (e) { setMessage(e instanceof Error ? e.message : String(e)); }
    finally { actionLock.current = false; setBusy(false); }
  }

  async function confirm() {
    if (actionLock.current || !preview || !lookup?.confirmationId) return;
    actionLock.current = true;
    setBusy(true);
    try {
      const r = await api<{ reference: string; status: string }>("/v1/wallet/transfers/confirmed", {
        idempotencyKey: key, body: { confirmationId: lookup.confirmationId, amount: preview.amount },
      });
      setMessage(`Transfer status: ${r.status}. Reference ${r.reference}. Check recent activity for updates.`);
      setLookup(null); setPreview(null); setPhone(""); setAmount("");
      onSent();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : String(e));
      if (e instanceof ApiError && e.code.startsWith("confirmation_")) { setLookup(null); setPreview(null); }
    } finally {
      actionLock.current = false;
      setBusy(false);
    }
  }

  return (
    <section className="panel">
      <h2>Send money</h2>
      <label>Send to<select disabled={busy} value={rail} onChange={(e) => { setRail(e.target.value); edit(phone); }}>
        <option value="wallet">ELEPAY wallet</option><option value="momo">Mobile money</option>
      </select></label>
      <label>Phone number<input inputMode="tel" autoComplete="tel" disabled={busy} value={phone} onChange={(e) => edit(e.target.value)} /></label>
      {rail === "momo" && <label>Network<select disabled={busy} value={network} onChange={(e) => { setNetwork(e.target.value); setLookup(null); setPreview(null); }}>
        <option value="mtn">MTN</option><option value="telecel">Telecel</option><option value="airteltigo">AirtelTigo</option>
      </select></label>}
      {!lookup && <button disabled={busy} onClick={() => void check()}>{busy ? "Checking…" : "Check recipient"}</button>}
      {lookup && lookup.status !== "verified" && <p className="notice warn">{lookup.message}</p>}
      {lookup?.status === "verified" && !preview && (
        <>
          <p className="notice ok">Recipient: <strong>{lookup.verifiedName}</strong> ({lookup.destination})</p>
          <label>Amount (GH₵)<input inputMode="decimal" disabled={busy} value={amount} onChange={(e) => setAmount(e.target.value)} /></label>
          <button disabled={busy} onClick={() => void review()}>{busy ? "Reviewing…" : "Review"}</button>
        </>
      )}
      {preview && (
        <div className="confirm">
          <h3>Confirm transfer</h3>
          <dl>
            <dt>To</dt><dd><strong>{preview.verifiedName}</strong></dd>
            <dt>Destination</dt><dd>{preview.destination}</dd>
            <dt>Amount</dt><dd>{formatMoney(preview.amount)}</dd>
            <dt>Fee</dt><dd>{formatMoney(preview.fee)}</dd>
            <dt>Total</dt><dd><strong>{formatMoney(preview.total)}</strong></dd>
          </dl>
          <button disabled={busy} onClick={() => void confirm()}>{busy ? "Sending…" : `Send ${formatMoney(preview.total)}`}</button>
          <button className="secondary" disabled={busy} onClick={() => setPreview(null)}>Back</button>
        </div>
      )}
      {message && <p className="notice" role="status">{message}</p>}
    </section>
  );
}

// -------------------------------------------------------------------------------------------------
// 2. Airtime & Data Top-ups
// -------------------------------------------------------------------------------------------------
function AirtimeAndData() {
  return (
    <section className="panel">
      <h2>Airtime &amp; data</h2>
      <p className="muted">No verified carrier payment connection is available for this screen. No recharge or wallet deduction is performed.</p>
    </section>
  );
}

// -------------------------------------------------------------------------------------------------
// 3. Utility Bill Payments
// -------------------------------------------------------------------------------------------------
function PayBills() {
  return (
    <section className="panel">
      <h2>Bill payments</h2>
      <p className="muted">External biller verification and settlement are not connected to this screen. No account is claimed verified and no bill is marked paid.</p>
    </section>
  );
}

// -------------------------------------------------------------------------------------------------
// 4. Virtual Cards Controls
// -------------------------------------------------------------------------------------------------
function VirtualCards() {
  return (
    <section className="panel">
      <h2>Virtual cards</h2>
      <p className="muted">No card issuer connection has been verified. Issuance and card controls are unavailable rather than simulated in browser state.</p>
    </section>
  );
}

// -------------------------------------------------------------------------------------------------
// 5. Savings Goals
// -------------------------------------------------------------------------------------------------
function SavingsGoals() {
  return (
    <section className="panel">
      <h2>Savings goals</h2>
      <p className="muted">Ledger-backed savings-goal transactions are not connected to this screen. Saving or releasing funds through browser-only state is disabled.</p>
    </section>
  );
}

// -------------------------------------------------------------------------------------------------
// 6. Export Receipts & Statements (CSV & PDF)
// -------------------------------------------------------------------------------------------------
export function sanitizeCsvCell(value: string | number | null | undefined): string {
  const raw = value === null || value === undefined ? "" : String(value);
  const safe = /^[=+\-@\t\r]/.test(raw) ? `'${raw}` : raw;
  return `"${safe.replace(/"/g, '""')}"`;
}

export type StatementRange = "all" | "30d" | "90d" | "ytd";

export function filterOperationsByRange<T extends { createdAt: string }>(
  operations: T[],
  range: StatementRange | string,
  now: Date = new Date(),
): T[] {
  if (range === "all") return operations;
  const nowMs = now.getTime();
  let cutoffMs: number;
  if (range === "30d") {
    cutoffMs = nowMs - 30 * 24 * 60 * 60 * 1000;
  } else if (range === "90d") {
    cutoffMs = nowMs - 90 * 24 * 60 * 60 * 1000;
  } else if (range === "ytd") {
    cutoffMs = new Date(now.getFullYear(), 0, 1).getTime();
  } else {
    return operations;
  }
  return operations.filter((op) => {
    const t = Date.parse(op.createdAt);
    return Number.isFinite(t) && t >= cutoffMs;
  });
}

function ExportStatements({ operations }: { operations: Operation[] }) {
  const [range, setRange] = useState<StatementRange>("all");
  const currentYear = new Date().getFullYear();

  function exportCsv() {
    const filtered = filterOperationsByRange(operations, range);
    const headers = "Reference,Type,Counterparty,Amount (Pesewas),Amount (GHS),Status,CreatedAt\n";
    const rows = filtered.map((o) =>
      [
        o.reference,
        o.kind,
        o.counterparty ?? "",
        o.amount,
        (o.amount / 100).toFixed(2),
        o.status,
        o.createdAt,
      ].map(sanitizeCsvCell).join(",")
    ).join("\n");

    const blob = new Blob([headers + rows], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.setAttribute("download", `elepay_statement_${new Date().toISOString().substring(0, 10)}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  }

  return (
    <section className="panel">
      <h2>Export Account Statements &amp; Receipts</h2>
      <p className="muted small">Download bank-grade financial statements for tax filing, loan verification, or accounting records.</p>

      <div className="flex-row" style={{ margin: "1rem 0" }}>
        <label className="choice">Date Range:
          <select value={range} onChange={(e) => setRange(e.target.value as StatementRange)} style={{ width: "auto" }}>
            <option value="all">All Available Records</option>
            <option value="30d">Last 30 Days</option>
            <option value="90d">Last 90 Days</option>
            <option value="ytd">Year-to-Date {currentYear}</option>
          </select>
        </label>
      </div>

      <div className="flex-row">
        <button onClick={() => exportCsv()}>📥 Download Statement (CSV)</button>
        <button className="secondary" onClick={() => window.print()}>🖨️ Printable PDF Statement</button>
      </div>
    </section>
  );
}

function downloadTransactionSummary(o: Operation) {
  const content = `ELEPAY TRANSACTION SUMMARY — UNVERIFIED COPY
--------------------------------------
Reference: ${o.reference}
Type: ${o.kind}
Counterparty: ${o.counterparty ?? "ELEPAY Customer"}
Amount: ${formatMoney(o.amount)}
Status: ${o.status.toUpperCase()}
Date: ${new Date(o.createdAt).toLocaleString()}
--------------------------------------
This is a wallet-history export, NOT a certified bank or network receipt.
Verify payment completion using the transaction reference and authoritative backend records.
`;
  const blob = new Blob([content], { type: "text/plain;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.setAttribute("download", `transaction_summary_${o.reference}.txt`);
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}
