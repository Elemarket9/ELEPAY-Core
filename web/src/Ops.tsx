import { useEffect, useState } from "react";
import { api } from "./api";
import { formatMoney } from "./money";

interface Item { id: string; kind: string; severity: string; subject: string; details: string; createdAt: string }
interface MerchantView { id: string; legalName: string; tradingName: string; kind: string; status: string }
interface Integrity { accountsWithDrift: number; netByCurrency: Record<string, number>; unbalancedTransactions: number }

const TABS = [
  "Overview", "Agent Banking", "Lending Portfolio", "System Health",
  "Exceptions", "Onboarding", "Disputes", "Reconciliation"
] as const;

/** Operations console: agent banking, lending review, system health, exceptions, and reconciliation. */
export function OpsConsole() {
  const [tab, setTab] = useState<(typeof TABS)[number]>("Overview");
  return (
    <main>
      <h1>Operations console</h1>
      <nav className="tabs">{TABS.map((t) => <button key={t} className={t === tab ? "active" : ""} onClick={() => setTab(t)}>{t}</button>)}</nav>

      {tab === "Overview" && <Overview />}
      {tab === "Agent Banking" && <AgentBanking />}
      {tab === "Lending Portfolio" && <LendingPortfolio />}
      {tab === "System Health" && <SystemHealth />}
      {tab === "Exceptions" && <Exceptions />}
      {tab === "Onboarding" && <Onboarding />}
      {tab === "Disputes" && <Disputes />}
      {tab === "Reconciliation" && <Reconciliation />}
    </main>
  );
}

function useAction() {
  const [msg, setMsg] = useState<string | null>(null);
  const run = async (fn: () => Promise<unknown>, ok: string) => {
    try { await fn(); setMsg(ok); } catch (e) { setMsg(e instanceof Error ? e.message : String(e)); }
  };
  return { msg, run };
}

// -------------------------------------------------------------------------------------------------
// 1. Agent Banking Oversight
// -------------------------------------------------------------------------------------------------
function AgentBanking() {
  return (
    <div>
      <h3>Agent banking</h3>
      <p className="muted">Verified agent-status and float-management APIs are not connected to this view. Agent state and funds cannot be changed locally.</p>
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// 2. Lending Portfolio Review
// -------------------------------------------------------------------------------------------------
function LendingPortfolio() {
  return (
    <div>
      <h3>Lending portfolio</h3>
      <p className="muted">No verified loan portfolio source is connected to this view. Principal, arrears, impairment and NPL figures are unavailable rather than fabricated.</p>
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// 4. System Health Status
// -------------------------------------------------------------------------------------------------
function SystemHealth() {
  return (
    <div>
      <h3>Infrastructure and payment rails</h3>
      <p className="muted">No authenticated live telemetry is connected to this screen. Rail uptime, ledger integrity, database health, queue backlog and webhook reliability are unavailable rather than assumed healthy.</p>
      <p className="small muted">For operational decisions, use verified runtime health metrics and reconciliation evidence.</p>
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// Existing Ops Views
// -------------------------------------------------------------------------------------------------

function Overview() {
  const [o, setO] = useState<Record<string, number> | null>(null);
  const [integrity, setIntegrity] = useState<Integrity | null>(null);
  const { msg, run } = useAction();
  const load = () => Promise.all([api<Record<string, number>>("/v1/ops/overview").then(setO),
    api<Integrity>("/v1/ops/ledger/integrity").then(setIntegrity)]);
  useEffect(() => { void load(); }, []);
  return (
    <>
      {o && (
        <div className="cards">
          <div className="card"><div className="muted">Paid last 24h</div><div className="big">{formatMoney(Number(o.paid_24h))}</div>
            <div className="small">{o.checkouts_24h} checkouts</div></div>
          <div className="card"><div className="muted">Open exceptions</div><div className="big">{o.open_items}</div></div>
          <div className="card"><div className="muted">Rail transactions awaiting result</div><div className="big">{o.rail_open}</div></div>
          <div className="card"><div className="muted">Outbox backlog / dead webhooks</div><div className="big">{o.outbox_backlog} / {o.webhooks_dead}</div></div>
          <div className="card"><div className="muted">Merchants to review / open disputes</div><div className="big">{o.merchants_pending_review} / {o.disputes_open}</div></div>
        </div>
      )}
      {integrity && (
        <p className={integrity.accountsWithDrift === 0 && integrity.unbalancedTransactions === 0 ? "notice ok" : "notice warn"}>
          Ledger integrity: {integrity.accountsWithDrift} accounts with drift, {integrity.unbalancedTransactions} unbalanced transactions,
          net by currency {Object.entries(integrity.netByCurrency).map(([c, v]) => `${c} ${v}`).join(", ")}
        </p>
      )}
      <button onClick={() => void run(() => api("/v1/ops/rails/recover", { method: "POST" }).then(load), "Rail recovery run")}>Recover pending rail results</button>
      <button onClick={() => void run(() => api("/v1/ops/settlements/run", { body: {} }).then(load), "Settlement run complete")}>Run due settlements</button>
      {msg && <p className="small">{msg}</p>}
    </>
  );
}

function Exceptions() {
  const [items, setItems] = useState<Item[]>([]);
  const { msg, run } = useAction();
  const load = () => api<Item[]>("/v1/ops/queue").then(setItems);
  useEffect(() => { void load(); }, []);
  return (
    <>
      {msg && <p className="small">{msg}</p>}
      <table className="grid">
        <thead><tr><th>Severity</th><th>Kind</th><th>Subject</th><th>Details</th><th>Opened</th><th /></tr></thead>
        <tbody>{items.map((i) => (
          <tr key={i.id}><td className={`sev-${i.severity}`}>{i.severity}</td><td>{i.kind}</td><td>{i.subject}</td>
            <td className="small mono">{i.details}</td><td className="small">{new Date(i.createdAt).toLocaleString()}</td>
            <td><button className="small" onClick={() => {
              const resolution = prompt("Resolution note");
              if (resolution) void run(() => api(`/v1/ops/queue/${i.id}/resolve`, { body: { resolution } }).then(load), "Resolved");
            }}>Resolve</button></td></tr>
        ))}</tbody>
      </table>
    </>
  );
}

function Onboarding() {
  const [merchants, setMerchants] = useState<MerchantView[]>([]);
  const { msg, run } = useAction();
  const load = () => api<MerchantView[]>("/v1/ops/merchants").then(setMerchants);
  useEffect(() => { void load(); }, []);
  const review = (m: MerchantView, decision: string) => {
    const feeBps = decision === "approve" ? Number(prompt("Agreed fee in basis points (100 = 1%)", "100")) : undefined;
    void run(() => api(`/v1/ops/merchants/${m.id}/review`, { body: { decision, feeBps, note: "Reviewed in console" } }).then(load),
      `${m.tradingName}: ${decision}`);
  };
  return (
    <>
      {msg && <p className="small">{msg}</p>}
      <table className="grid">
        <thead><tr><th>Trading name</th><th>Legal name</th><th>Type</th><th /></tr></thead>
        <tbody>{merchants.map((m) => (
          <tr key={m.id}><td>{m.tradingName}</td><td>{m.legalName}</td><td>{m.kind}</td>
            <td><button className="small" onClick={() => review(m, "approve")}>Approve</button>
              <button className="small secondary" onClick={() => review(m, "reject")}>Reject</button></td></tr>
        ))}</tbody>
      </table>
    </>
  );
}

function Disputes() {
  const [rows, setRows] = useState<Record<string, string | number>[]>([]);
  const { msg, run } = useAction();
  const load = () => api<Record<string, string | number>[]>("/v1/ops/disputes").then(setRows);
  useEffect(() => { void load(); }, []);
  const resolve = (id: string, outcome: string) =>
    void run(() => api(`/v1/ops/disputes/${id}/resolve`, { body: { outcome, note: "Resolved in console" } }).then(load), "Dispute resolved");
  return (
    <>
      {msg && <p className="small">{msg}</p>}
      <table className="grid">
        <thead><tr><th>Checkout</th><th>Merchant</th><th className="num">Amount</th><th>Reason</th><th /></tr></thead>
        <tbody>{rows.map((d) => (
          <tr key={String(d.id)}><td>{d.checkout} #{d.line}</td><td>{d.merchant}</td><td className="num">{formatMoney(Number(d.amount))}</td>
            <td>{d.reason}</td><td><button className="small" onClick={() => resolve(String(d.id), "merchant_won")}>Merchant won</button>
              <button className="small secondary" onClick={() => resolve(String(d.id), "customer_won")}>Customer won</button></td></tr>
        ))}</tbody>
      </table>
    </>
  );
}

function Reconciliation() {
  const [runs, setRuns] = useState<Record<string, string>[]>([]);
  useEffect(() => { api<Record<string, string>[]>("/v1/ops/reconciliation/runs").then(setRuns).catch(() => setRuns([])); }, []);
  return (
    <table className="grid">
      <thead><tr><th>Source</th><th>Period</th><th>Summary</th><th>By</th><th>When</th></tr></thead>
      <tbody>{runs.map((r) => <tr key={r.id}><td>{r.rail}</td><td>{r.period}</td><td className="mono small">{r.summary}</td>
        <td>{r.imported_by}</td><td className="small">{new Date(r.created_at).toLocaleString()}</td></tr>)}</tbody>
    </table>
  );
}
