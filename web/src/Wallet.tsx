import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, newKey } from "./api";
import { formatMoney, normalizeGhanaPhone, parseMoney } from "./money";

interface Account { code: string; bucket: string; balance: number }
interface Lookup {
  status: string; rail: string; destination: string; verifiedName: string | null;
  confirmationId: string | null; expiresAt: string | null; message: string | null;
}
interface Preview { verifiedName: string; destination: string; amount: number; fee: number; total: number; expiresAt: string; rail: string }
interface Operation { reference: string; kind: string; amount: number; status: string; counterparty: string | null; createdAt: string }
interface LedgerEntry { transactionId: string; line: number; currency: string; amount: number; kind: string; postedAt: string; runningBalance: number; description: string | null }
interface TransferReceipt { reference: string; status: string; amount: number; fee: number; total: number }
export interface PendingTransfer { key: string; confirmationId: string; amount: number }

export function decodePendingTransfer(raw: string | null): PendingTransfer | null {
  if (raw === null) return null;
  const p: unknown = JSON.parse(raw);
  if (!p || typeof p !== "object") throw new Error("Invalid saved transfer");
  const v = p as Record<string, unknown>;
  if (typeof v.key !== "string" || !v.key || v.key.length > 200 || typeof v.confirmationId !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v.confirmationId) || typeof v.amount !== "number" || !Number.isSafeInteger(v.amount) || v.amount <= 0) throw new Error("Invalid saved transfer");
  return { key: v.key, confirmationId: v.confirmationId, amount: v.amount };
}

export function confirmationExpired(expiresAt: string | null, now = Date.now()): boolean {
  if (!expiresAt) return true;
  const expiry = Date.parse(expiresAt);
  return !Number.isFinite(expiry) || expiry <= now;
}

export function formatWalletDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Unavailable" : new Intl.DateTimeFormat("en-GH", { dateStyle: "medium", timeStyle: "short", timeZone: "Africa/Accra" }).format(date);
}

export function searchWalletHistory<T extends Operation>(rows: T[], query: string, status: string): T[] {
  const q = query.trim().toLowerCase();
  return rows.filter(o => (status === "all" || transactionStatusClass(o.status) === status) && (!q || [o.reference, o.kind, o.counterparty ?? "", o.status].some(v => v.toLowerCase().includes(q))));
}

const WALLET_TABS = ["Send Money", "Export Statements", "Other Services"] as const;

/**
 * Customer web wallet with Send Money, Airtime/Data top-ups, Bill payments,
 * Virtual card controls, Savings goals, and Receipt/Statement exports.
 */
export function Wallet() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [operations, setOperations] = useState<Operation[]>([]);
  const [customerId, setCustomerId] = useState<string | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [updatedAt, setUpdatedAt] = useState<string | null>(null);
  const [hidden, setHidden] = useState(false);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("all");
  const [page, setPage] = useState(1);
  const [receiptRef, setReceiptRef] = useState("");
  const [receipt, setReceipt] = useState<Operation | null>(null);
  const [receiptError, setReceiptError] = useState<string | null>(null);
  const [receiptBusy, setReceiptBusy] = useState(false);
  const receiptSequence = useRef(0);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [moduleDisabled, setModuleDisabled] = useState(false);
  const loadSequence = useRef(0);
  const [activeTab, setActiveTab] = useState<(typeof WALLET_TABS)[number]>("Send Money");

  const load = useCallback(async () => {
    const sequence = ++loadSequence.current;
    setError(null); setHistoryError(null); setLoading(true);
    // A failed account refresh must not leave a previous receipt visible.
    receiptSequence.current++; setReceiptBusy(false); setReceipt(null); setReceiptError(null);
    try {
      const w = await api<{ customerId: string; accounts: Account[] }>("/v1/wallet");
      if (sequence !== loadSequence.current) return;
      if (!w.customerId || !Array.isArray(w.accounts)) throw new Error("Invalid wallet response");
      setCustomerId(w.customerId); setAccounts(w.accounts); setUpdatedAt(new Date().toISOString()); setModuleDisabled(false);
      try {
        const history = await api<Operation[]>("/v1/wallet/operations?limit=200");
        if (sequence === loadSequence.current) setOperations(history);
      } catch {
        if (sequence === loadSequence.current) { setHistoryError("Transaction history could not be refreshed."); setOperations([]); }
      }
    } catch (e) {
      if (sequence !== loadSequence.current) return;
      setCustomerId(null); setAccounts([]); setOperations([]); setUpdatedAt(null); setReceipt(null);
      setModuleDisabled(e instanceof ApiError && e.code === "FEATURE_NOT_ENABLED");
      setError(e instanceof ApiError && e.status === 404 ? "No ELEPAY wallet is linked to this sign-in yet." : e instanceof Error ? e.message : "Wallet could not be loaded.");
    } finally {
      if (sequence === loadSequence.current) setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); return () => { loadSequence.current++; receiptSequence.current++; }; }, [load]);

  const bal = (bucket: string): string => {
    if (hidden) return "••••••";
    const account = accounts.find(a => a.bucket === bucket);
    return account ? formatMoney(account.balance) : loading ? "Loading…" : "Unavailable";
  };
  const filtered = searchWalletHistory(operations, query, statusFilter);
  const pageCount = Math.max(1, Math.ceil(filtered.length / 10));
  const visiblePage = Math.min(page, pageCount);
  const visible = filtered.slice((visiblePage - 1) * 10, visiblePage * 10);

  async function lookupReceipt(reference: string) {
    const value = reference.trim();
    if (!value || !customerId) return;
    const seq = ++receiptSequence.current;
    setReceiptRef(value); setReceipt(null); setReceiptError(null); setReceiptBusy(true);
    try {
      const result = await api<Operation>("/v1/wallet/receipts/" + encodeURIComponent(value));
      if (seq === receiptSequence.current) setReceipt(result);
    } catch (e) {
      if (seq === receiptSequence.current) setReceiptError(e instanceof ApiError && e.status === 404 ? "No matching receipt in this wallet." : "Could not retrieve the latest transaction status.");
    } finally { if (seq === receiptSequence.current) setReceiptBusy(false); }
  }

  return (
    <main className="wallet-workspace">
      <div className="page-title"><div><p className="eyebrow">PERSONAL WALLET</p><h1>My wallet</h1><p className="muted">Your balances and transaction activity.</p></div><button className="secondary" disabled={loading} onClick={() => void load()}>{loading ? "Refreshing…" : "Refresh balances"}</button></div>
      {moduleDisabled && (
        <p className="notice warn">Consumer wallet module is disabled in this environment (Stage 1).</p>
      )}
      {error && <p className="error" role="alert">{error}</p>}
      <div className="wallet-balance-toolbar"><p className="muted small" role="status">{updatedAt ? "Balances updated " + formatWalletDate(updatedAt) + " GMT" : "No verified balance loaded"}</p><button className="secondary small" aria-pressed={hidden} onClick={() => setHidden(!hidden)}>{hidden ? "Show balances" : "Hide balances"}</button></div>
      <div className="cards wallet-balances" aria-busy={loading}>
        <div className="card balance-primary"><div className="muted">Available balance</div><div className="big">{bal("available")}</div><p className="small muted">Funds available to use, subject to limits.</p></div>
        <div className="card"><div className="muted">Pending</div><div className="big">{bal("pending")}</div><p className="small muted">Funds awaiting confirmation.</p></div>
        <div className="card"><div className="muted">Reserved</div><div className="big">{bal("reserved")}</div><p className="small muted">Funds held for existing obligations.</p></div>
      </div>

      <nav className="tabs" aria-label="Wallet services">
        {WALLET_TABS.map((t) => (
          <button key={t} aria-pressed={t === activeTab} className={t === activeTab ? "active" : ""} onClick={() => setActiveTab(t)}>
            {t}
          </button>
        ))}
      </nav>

      {customerId && !moduleDisabled && <div hidden={activeTab !== "Send Money"}><SendMoney key={customerId} customerId={customerId} onSent={() => void load()} /></div>}
      {activeTab === "Export Statements" && customerId && <ExportStatements key={customerId} />}
      {activeTab === "Other Services" && <section className="panel"><h2>Other services</h2><p className="muted">Airtime, bill payments, virtual cards and savings goals are not currently available through this wallet. No simulated financial actions are offered.</p></section>}

      <section className="panel wallet-activity"><div className="page-title"><div><h2>Transaction activity</h2><p className="muted small">Up to 200 recent records. For full ledger activity, use statements.</p></div></div>
      {historyError && <p role="alert" className="error">{historyError}</p>}
      <div className="wallet-filter"><label>Search transactions<input value={query} onChange={e => { setQuery(e.target.value); setPage(1); }} placeholder="Reference, name or type" /></label><label>Status<select value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setPage(1); }}><option value="all">All statuses</option><option value="active">Completed</option><option value="pending">Pending / unknown</option><option value="failed">Failed / returned</option></select></label></div>
      <div className="table-scroll" tabIndex={0} role="region" aria-label="Recent wallet transactions"><table className="grid">
        <thead>
          <tr>
            <th>Reference</th>
            <th>Type</th>
            <th>Counterparty</th>
            <th>Date (GMT)</th>
            <th className="num">Amount</th>
            <th>Status</th>
            <th>Action</th>
          </tr>
        </thead>
        <tbody>
          {visible.map((o) => (
            <tr key={o.reference}>
              <td className="mono small">{o.reference}</td>
              <td>{o.kind}</td>
              <td>{o.counterparty ?? "—"}</td>
              <td>{formatWalletDate(o.createdAt)}</td>
              <td className="num">{formatMoney(o.amount)}</td>
              <td><span className={`tag ${transactionStatusClass(o.status)}`}>{o.status}</span></td>
              <td>
                <button
                  className="small secondary"
                  onClick={() => void lookupReceipt(o.reference)}
                >
                  View status
                </button>
              </td>
            </tr>
          ))}
          {visible.length === 0 && (
            <tr><td colSpan={7} className="muted">{loading ? "Loading…" : error || historyError ? "Transaction data unavailable." : "No matching recent transactions."}</td></tr>
          )}
        </tbody>
      </table></div>
      <div className="wallet-pagination"><span className="small muted">{filtered.length} matching records · Page {visiblePage} of {pageCount}</span><button className="secondary small" disabled={visiblePage <= 1} onClick={() => setPage(visiblePage - 1)}>Previous</button><button className="secondary small" disabled={visiblePage >= pageCount} onClick={() => setPage(visiblePage + 1)}>Next</button></div>
      {customerId && <form className="wallet-filter" onSubmit={e => { e.preventDefault(); void lookupReceipt(receiptRef); }}><label>Find a transaction by reference<input value={receiptRef} maxLength={120} onChange={e => { receiptSequence.current++; setReceiptBusy(false); setReceiptRef(e.target.value); setReceipt(null); setReceiptError(null); }} required /></label><button type="submit" disabled={receiptBusy}>{receiptBusy ? "Checking…" : "Check status"}</button></form>}
      {receiptError && <p className="error" role="alert">{receiptError}</p>}
      {receipt && <div className="receipt-detail" role="status"><h3>Transaction {receipt.reference}</h3><p><span className={`tag ${transactionStatusClass(receipt.status)}`}>{receipt.status}</span> · {formatMoney(receipt.amount)}</p><p className="small muted">{formatWalletDate(receipt.createdAt)} GMT · {receipt.kind}</p><button className="secondary small" onClick={() => void lookupReceipt(receipt.reference)}>Refresh status</button><button className="secondary small" onClick={() => downloadTransactionSummary(receipt)}>Download summary</button></div>}
      </section>
    </main>
  );
}

// -------------------------------------------------------------------------------------------------
// 1. Send Money (Core Wallet)
// -------------------------------------------------------------------------------------------------
export function transactionStatusClass(status: string): string {
  switch (status.toLowerCase()) {
    case "completed": case "success": case "succeeded": case "settled": case "paid": return "active";
    case "failed": case "declined": case "cancelled": case "expired": case "rejected": case "returned": return "failed";
    default: return "pending";
  }
}

function SendMoney({ customerId, onSent }: { customerId: string; onSent: () => void }) {
  const storageKey = "elepay.transfer.pending." + customerId;
  const [saved, setSaved] = useState<{ pending: PendingTransfer | null; blocked: boolean }>(() => {
    try { return { pending: decodePendingTransfer(sessionStorage.getItem(storageKey)), blocked: false }; }
    catch { return { pending: null, blocked: true }; }
  });
  const [rail, setRail] = useState("wallet");
  const [phone, setPhone] = useState("");
  const [network, setNetwork] = useState("mtn");
  const [bankCode, setBankCode] = useState("");
  const [accountNumber, setAccountNumber] = useState("");
  const [lookup, setLookup] = useState<Lookup | null>(null);
  const [amount, setAmount] = useState("");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [result, setResult] = useState<TransferReceipt | null>(null);
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(Date.now);
  const actionLock = useRef(false);
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  const locked = busy || Boolean(saved.pending) || saved.blocked;
  function resetVerification() { setLookup(null); setPreview(null); setMessage(null); setResult(null); }
  function setLocked(value: boolean) { actionLock.current = value; setBusy(value); }

  async function check() {
    if (actionLock.current || locked) return;
    const msisdn = normalizeGhanaPhone(phone);
    if (rail !== "bank" && !msisdn) { setMessage("Enter a complete Ghana phone number."); return; }
    if (rail === "bank" && (!bankCode.trim() || !accountNumber.trim())) { setMessage("Enter the bank code and account number."); return; }
    setLocked(true); resetVerification();
    try {
      setLookup(await api<Lookup>("/v1/wallet/recipients/lookup", { body: rail === "bank" ? { rail, bankCode: bankCode.trim(), accountNumber: accountNumber.trim() } : { rail, phone: msisdn, ...(rail === "momo" ? { network } : {}) } }));
    } catch (e) { setMessage(e instanceof Error ? e.message : "Recipient lookup failed."); }
    finally { setLocked(false); }
  }

  async function review() {
    if (actionLock.current || locked) return;
    const pesewas = parseMoney(amount);
    if (!lookup?.confirmationId || confirmationExpired(lookup.expiresAt)) { resetVerification(); setMessage("Check the recipient again. Verification expired."); return; }
    if (!pesewas || !Number.isSafeInteger(pesewas) || pesewas <= 0) { setMessage("Enter a valid positive amount."); return; }
    setLocked(true);
    try {
      setPreview(await api<Preview>("/v1/wallet/transfers/preview", { body: { confirmationId: lookup.confirmationId, amount: pesewas } }));
      setMessage(null);
    } catch (e) { setMessage(e instanceof Error ? e.message : "Could not review transfer."); }
    finally { setLocked(false); }
  }

  async function submit(pending: PendingTransfer) {
    if (actionLock.current || saved.blocked) return;
    setLocked(true); setMessage(null);
    try {
      // Save only the exact retry payload and key, scoped to this wallet in this browser tab.
      // If persistence fails, do not initiate a payment that cannot be safely recovered here.
      try { const prior=decodePendingTransfer(sessionStorage.getItem(storageKey));if(prior&&(prior.key!==pending.key||prior.confirmationId!==pending.confirmationId||prior.amount!==pending.amount)){setSaved({pending:prior,blocked:false});setMessage("A previous transfer must be resolved first.");return;}sessionStorage.setItem(storageKey, JSON.stringify(pending)); }
      catch { setMessage("Secure retry storage is unavailable. No transfer was initiated by this action."); return; }
      setSaved({ pending, blocked: false });
      let receipt: TransferReceipt;
      try {
        receipt = await api<TransferReceipt>("/v1/wallet/transfers/confirmed", {
          idempotencyKey: pending.key, body: { confirmationId: pending.confirmationId, amount: pending.amount },
        });
      } catch (e) {
        // Retain key and payload on uncertain outcomes; do not silently initiate a new transfer.
        // Expired or stale confirmation is a definitive response only after same-key lookup.
        if (e instanceof ApiError && ["confirmation_expired", "confirmation_stale"].includes(e.code) && e.status === 409) {
          try { sessionStorage.removeItem(storageKey); }
          catch { setMessage("The original request was rejected, but recovery storage could not be cleared. Contact support."); return; }
          setSaved({ pending: null, blocked: false }); resetVerification(); setMessage(e.message);
        } else {
          setMessage("Transfer outcome is not confirmed. Check activity or retry the original request with the same key. " + (e instanceof Error ? e.message : ""));
        }
        return;
      }
      if (!receipt || typeof receipt.reference !== "string" || !receipt.reference || typeof receipt.status !== "string") {
        setMessage("Transfer response could not be verified. Retry the original request."); return;
      }
      setResult(receipt);
      try { sessionStorage.removeItem(storageKey); }
      catch { setMessage("Transfer reference received. Saved recovery data could not be cleared; contact support before sending again."); onSent(); return; }
      setSaved({ pending: null, blocked: false }); setLookup(null); setPreview(null); setPhone(""); setAmount(""); setAccountNumber(""); setBankCode("");
      onSent();
    } finally { setLocked(false); }
  }

  function confirm() {
    if (locked || !preview || !lookup?.confirmationId) return;
    if (confirmationExpired(preview.expiresAt)) { resetVerification(); setMessage("The transfer quote expired. Verify the recipient again."); return; }
    void submit({ key: newKey(), confirmationId: lookup.confirmationId, amount: preview.amount });
  }

  const verified = lookup?.status === "verified" && !confirmationExpired(lookup.expiresAt, now);
  const expiry = preview?.expiresAt ?? lookup?.expiresAt;
  const seconds = expiry ? Math.max(0, Math.ceil((Date.parse(expiry) - now) / 1000)) : 0;

  return (
    <section className="panel wallet-transfer" aria-busy={busy}>
      <p className="eyebrow">TRANSFER MONEY</p><h2>Send money</h2>
      <div className="transfer-steps" aria-label="Transfer progress"><span className={!verified ? "current" : ""}>1. Recipient</span><span className={verified && !preview ? "current" : ""}>2. Amount</span><span className={preview ? "current" : ""}>3. Review</span></div>
      {saved.blocked ? <p className="notice warn" role="alert">Saved transfer recovery data is unavailable or invalid. Check transaction activity and contact support before initiating another transfer.</p> : saved.pending ? <div className="notice warn" role="status"><strong>Transfer recovery required</strong><p>Do not create another transfer while the original outcome is uncertain. Retry the exact original request or review your transaction history.</p><p>Original amount: {formatMoney(saved.pending.amount)}</p><button disabled={busy} onClick={() => saved.pending && void submit(saved.pending)}>{busy ? "Checking original transfer…" : "Retry original request"}</button></div> : <>
      <fieldset disabled={busy || Boolean(preview)} className="transfer-inputs">
      <legend>Recipient details</legend>
      <label>Send to<select value={rail} onChange={e => { setRail(e.target.value); resetVerification(); }}><option value="wallet">ELEPAY wallet</option><option value="momo">Mobile money</option><option value="bank">Bank account</option></select></label>
      {rail === "bank" ? <div className="grid-2"><label>Bank code<input value={bankCode} maxLength={20} onChange={e => { setBankCode(e.target.value); resetVerification(); }} /></label><label>Account number<input value={accountNumber} maxLength={34} autoComplete="off" onChange={e => { setAccountNumber(e.target.value); resetVerification(); }} /></label></div> : <label>Phone number<input inputMode="tel" autoComplete="tel" value={phone} onChange={e => { setPhone(e.target.value); resetVerification(); }} placeholder="024 123 4567" /></label>}
      {rail === "momo" && <label>Network<select value={network} onChange={e => { setNetwork(e.target.value); resetVerification(); }}><option value="mtn">MTN MoMo</option><option value="telecel">Telecel Cash</option><option value="airteltigo">AT Money</option></select></label>}
      </fieldset>
      {!verified && !preview && <button disabled={busy} onClick={() => void check()}>{busy ? "Checking…" : lookup ? "Check recipient again" : "Check recipient"}</button>}
      {lookup && !verified && <p className="notice warn">{lookup.status === "verified" ? "Recipient verification expired." : lookup.message ?? "Recipient could not be verified."}</p>}
      {verified && !preview && <><p className="notice ok">Verified recipient: <strong>{lookup?.verifiedName}</strong> ({lookup?.destination})</p><label>Amount (GH₵)<input inputMode="decimal" disabled={busy} value={amount} onChange={e => setAmount(e.target.value)} placeholder="0.00" /></label><button disabled={busy} onClick={() => void review()}>{busy ? "Reviewing…" : "Review transfer"}</button></>}
      {preview && <div className="confirm transfer-review"><h3>Review before sending</h3><dl><dt>Recipient</dt><dd><strong>{preview.verifiedName}</strong></dd><dt>Destination</dt><dd>{preview.destination}</dd><dt>Amount</dt><dd>{formatMoney(preview.amount)}</dd><dt>Fee</dt><dd>{formatMoney(preview.fee)}</dd><dt>Total debit</dt><dd><strong>{formatMoney(preview.total)}</strong></dd></dl><button disabled={busy || confirmationExpired(preview.expiresAt, now)} onClick={confirm}>{busy ? "Submitting…" : "Confirm " + formatMoney(preview.total)}</button><button className="secondary" disabled={busy} onClick={() => setPreview(null)}>Back</button></div>}
      {expiry && <p className="small muted">{seconds > 0 ? "Verification expires in " + Math.floor(seconds / 60) + ":" + String(seconds % 60).padStart(2, "0") : "Verification expired. Check the recipient again."}</p>}
      </>}
      {message && <p className="notice warn" role="status">{message}</p>}
      {result && <div className="receipt-detail" role="status"><h3>Transfer response</h3><p><span className={"tag " + transactionStatusClass(result.status)}>{result.status}</span> · {formatMoney(result.amount)}</p><p className="mono small">{result.reference}</p><p className="small muted">Check transaction activity for the latest status before sending again.</p></div>}
    </section>
  );
}

// Statement exports and individual transaction summaries.
export function sanitizeCsvCell(value: string | number | null | undefined): string {
  const raw = value === null || value === undefined ? "" : String(value);
  const safe = /^(?:[\t\r\n]|\s*[=+\-@])/.test(raw) ? `'${raw}` : raw;
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
    return Number.isFinite(t) && t >= cutoffMs && t <= nowMs;
  });
}

export function statementWindow(from: string, through: string): { from: string; to: string } | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(through)) return null;
  const a = new Date(from + "T00:00:00Z"), b = new Date(through + "T00:00:00Z");
  if (!Number.isFinite(a.getTime()) || !Number.isFinite(b.getTime()) || a.toISOString().slice(0,10) !== from || b.toISOString().slice(0,10) !== through) return null;
  const end = b.getTime() + 86400000;
  if (end <= a.getTime() || end - a.getTime() > 31 * 86400000) return null;
  return { from: a.toISOString(), to: new Date(end).toISOString() };
}

function ExportStatements() {
  const today = new Date().toISOString().slice(0,10);
  const [from, setFrom] = useState(() => new Date(Date.now() - 29 * 86400000).toISOString().slice(0,10));
  const [through, setThrough] = useState(today);
  const [entries, setEntries] = useState<LedgerEntry[] | null>(null);
  const [loadedRange, setLoadedRange] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const seq = useRef(0);
  function clear() { seq.current++; setEntries(null); setLoadedRange(""); setError(null); setBusy(false); }
  async function fetchStatement() {
    const window = statementWindow(from, through);
    if (!window || through > today) { setError("Choose a valid date range of up to 31 days, ending no later than today."); return; }
    const request = ++seq.current;
    setBusy(true); setEntries(null); setError(null);
    try {
      const data = await api<LedgerEntry[]>("/v1/wallet/statement?" + new URLSearchParams(window).toString());
      if (request === seq.current) { setEntries(data); setLoadedRange(from + " through " + through + " (GMT)"); }
    } catch (e) { if (request === seq.current) setError(e instanceof Error ? e.message : "Statement unavailable."); }
    finally { if (request === seq.current) setBusy(false); }
  }
  function exportCsv() {
    if (!entries) return;
    const header = "Transaction ID,Line,Currency,Amount (minor units),Running balance (minor units),Kind,Posted at,Description\r\n";
    const rows = entries.map(e => [e.transactionId,e.line,e.currency,e.amount,e.runningBalance,e.kind,e.postedAt,e.description].map(sanitizeCsvCell).join(",")).join("\r\n");
    const url = URL.createObjectURL(new Blob([header + rows], { type: "text/csv;charset=utf-8;" }));
    const link = document.createElement("a"); link.href = url; link.download = "elepay_ledger_statement_" + from + "_" + through + ".csv";
    document.body.appendChild(link); link.click(); link.remove(); URL.revokeObjectURL(url);
  }
  return <section className="panel wallet-statement">
    <p className="eyebrow">LEDGER RECORDS</p><h2>Account statement</h2><p className="muted">Available-account ledger entries directly from ELEPAY. Choose up to 31 days. This export is not a certified bank statement.</p>
    <form className="wallet-filter" onSubmit={e => { e.preventDefault(); void fetchStatement(); }}><label>From (GMT)<input type="date" value={from} max={through} required onChange={e => { clear(); setFrom(e.target.value); }} /></label><label>Through (GMT)<input type="date" value={through} min={from} max={today} required onChange={e => { clear(); setThrough(e.target.value); }} /></label><button disabled={busy} type="submit">{busy ? "Loading…" : "Load statement"}</button></form>
    {error && <p className="error" role="alert">{error}</p>}
    {entries && <><p className="small muted" role="status">{loadedRange} · {entries.length} ledger entries</p><button disabled={!entries.length} onClick={exportCsv}>Download CSV</button><div className="table-scroll" tabIndex={0} role="region" aria-label="Ledger statement"><table className="grid"><thead><tr><th>Posted (GMT)</th><th>Details</th><th>Currency</th><th className="num">Amount (minor units)</th><th className="num">Running balance (minor units)</th></tr></thead><tbody>{entries.map(e => <tr key={e.transactionId + ":" + e.line}><td>{formatWalletDate(e.postedAt)}</td><td>{e.description ?? e.kind}<div className="mono small muted">{e.transactionId}</div></td><td>{e.currency}</td><td className="num">{e.amount}</td><td className="num">{e.runningBalance}</td></tr>)}{!entries.length && <tr><td colSpan={5}>No ledger entries for this date range.</td></tr>}</tbody></table></div></>}
  </section>;
}

function downloadTransactionSummary(o: Operation) {
  const content = `ELEPAY TRANSACTION SUMMARY — UNVERIFIED COPY
--------------------------------------
Reference: ${o.reference}
Type: ${o.kind}
Counterparty: ${o.counterparty ?? "Not provided"}
Amount: ${formatMoney(o.amount)}
Status: ${o.status.toUpperCase()}
Date: ${formatWalletDate(o.createdAt)} GMT
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
