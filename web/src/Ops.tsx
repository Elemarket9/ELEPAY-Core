import { createContext, useContext, useEffect, useState } from "react";
import { api } from "./api";
import { formatMoney } from "./money";

interface Item { id: string; kind: string; severity: string; subject: string; details: string; createdAt: string }
interface MerchantView { id: string; legalName: string; tradingName: string; kind: string; status: string }
interface Integrity { accountsWithDrift: number; netByCurrency: Record<string, number>; unbalancedTransactions: number }

export interface OpsPermissions {
  canApproveMakerChecker: boolean;
  canFreezeRisk: boolean;
  canManageFinance: boolean;
  canOnboardMerchants: boolean;
  canManageSupport: boolean;
}
interface OpsMe {
  subject: string;
  roles: string[];
  primaryRole: string;
  permissions: OpsPermissions;
}


interface PromptRequest {
  title: string;
  defaultValue?: string;
  resolve: (value: string | null) => void;
}
const OpsPromptContext = createContext<(title: string, defaultValue?: string) => Promise<string | null>>(
  async () => null,
);
function useOpsPrompt() {
  return useContext(OpsPromptContext);
}

const ALL_TABS = [
  "Overview", "Merchant Controls", "Maker-Checker", "Kill Switches", "Custodian Trust",
  "Onboarding", "Disputes", "Reconciliation", "Exceptions", "System Health"
] as const;
export type OpsTab = (typeof ALL_TABS)[number];

export function visibleTabsFor(perms: OpsPermissions | null): OpsTab[] {
  if (!perms) return [];
  return ALL_TABS.filter((t) => {
    if (t === "Overview" || t === "Merchant Controls" || t === "Exceptions" || t === "Disputes" || t === "System Health") return true;
    if (t === "Maker-Checker") return perms.canApproveMakerChecker || perms.canFreezeRisk || perms.canManageFinance;
    if (t === "Kill Switches") return perms.canFreezeRisk;
    if (t === "Custodian Trust" || t === "Reconciliation") return perms.canManageFinance || perms.canApproveMakerChecker;
    if (t === "Onboarding") return perms.canOnboardMerchants;
    return true;
  });
}

/** Operations console: role-scoped controls, merchant overrides, maker-checker, kill switches, custodian trust, and reconciliation. */
export function OpsConsole() {
  const [tab, setTab] = useState<OpsTab>("Overview");
  const [me, setMe] = useState<OpsMe | null>(null);
  const [meLoading, setMeLoading] = useState<boolean>(true);
  const [meError, setMeError] = useState<string | null>(null);
  const [globalFrozen, setGlobalFrozen] = useState<boolean>(false);
  const [killSwitchError, setKillSwitchError] = useState<string | null>(null);
  const [bannerMsg, setBannerMsg] = useState<string | null>(null);
  const [promptReq, setPromptReq] = useState<PromptRequest | null>(null);
  const [promptInput, setPromptInput] = useState("");
  const askPrompt = (title: string, defaultValue = ""): Promise<string | null> =>
    new Promise((resolve) => {
      setPromptInput(defaultValue);
      setPromptReq({ title, defaultValue, resolve });
    });
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [freezingGlobal, setFreezingGlobal] = useState<boolean>(false);

  useEffect(() => {
    setMeLoading(true);
    setMeError(null);
    api<OpsMe>("/v1/ops/me")
      .then((res) => { setMe(res); setMeError(null); })
      .catch((e) => { setMe(null); setMeError(e instanceof Error ? e.message : String(e)); })
      .finally(() => setMeLoading(false));

    api<KillSwitchStatus>("/v1/ops/kill-switch/status")
      .then((s) => { setGlobalFrozen(s.globalFrozen); setKillSwitchError(null); })
      .catch((e) => setKillSwitchError(e instanceof Error ? e.message : String(e)));
  }, []);

  const tabs = visibleTabsFor(me?.permissions ?? null);

  async function quickEmergencyFreeze() {
    const rawReason = await askPrompt("Reason for emergency global payout freeze:");
    if (rawReason === null) return;
    const reason = rawReason.trim();
    if (!reason) {
      setBannerError("A non-empty reason is required for emergency global payout freeze.");
      return;
    }
    setFreezingGlobal(true);
    setBannerMsg(null);
    setBannerError(null);
    try {
      const res = await api<Record<string, unknown>>("/v1/ops/kill-switch/global", {
        body: { frozen: true, reason },
      });
      setGlobalFrozen(true);
      setBannerMsg(formatDualControlOutcome(res, "Global payout freeze activated."));
    } catch (e) {
      setBannerError(e instanceof Error ? e.message : String(e));
    } finally {
      setFreezingGlobal(false);
    }
  }

  return (
    <OpsPromptContext.Provider value={askPrompt}>
    <main>
      <div className="flex-between" style={{ gap: "1rem", flexWrap: "wrap" }}>
        <div>
          <h1 style={{ marginBottom: "0.25rem" }}>Operations console</h1>
          {me && (
            <p className="small muted" style={{ margin: 0 }}>
              Signed in as <strong>{me.subject}</strong> · Role: <span className="tag active">{me.primaryRole}</span>
            </p>
          )}
        </div>
        {(me?.permissions.canFreezeRisk ?? false) && !globalFrozen && (
          <button
            style={{ background: "#b91c1c", color: "#fff" }}
            disabled={freezingGlobal}
            onClick={() => void quickEmergencyFreeze()}
          >
            {freezingGlobal ? "Freezing…" : "Emergency Freeze All Payouts"}
          </button>
        )}
      </div>

      {killSwitchError && <p className="error">Kill switch status error: {killSwitchError}</p>}
      {globalFrozen && (
        <p className="notice warn" style={{ marginTop: "0.75rem" }}>
          <strong>GLOBAL PAYOUT FREEZE ACTIVE</strong> — All merchant payouts are paused. Unfreezing requires Maker-Checker dual approval.
        </p>
      )}
      {bannerError && <p className="error">{bannerError}</p>}
      {bannerMsg && <p className="notice ok">{bannerMsg}</p>}

      {meLoading && <p className="muted">Loading operator permissions…</p>}
      {meError && <p className="error">Failed to load operator permissions: {meError}</p>}

      {!meLoading && !meError && me && (
        <>
          <nav className="tabs" style={{ marginTop: "1rem" }}>
            {tabs.map((t) => <button key={t} className={t === tab ? "active" : ""} onClick={() => setTab(t)}>{t}</button>)}
          </nav>

          {tabs.includes(tab) && (
            <>
              {tab === "Overview" && <Overview />}
              {tab === "Merchant Controls" && <MerchantControls perms={me.permissions} />}
              {tab === "Maker-Checker" && <MakerChecker canApprove={me.permissions.canApproveMakerChecker ?? false} />}
              {tab === "Kill Switches" && <KillSwitches />}
              {tab === "Custodian Trust" && <CustodianTrust />}
              {tab === "System Health" && <SystemHealth />}
              {tab === "Exceptions" && <Exceptions />}
              {tab === "Onboarding" && <Onboarding />}
              {tab === "Disputes" && <Disputes />}
              {tab === "Reconciliation" && <Reconciliation />}
            </>
          )}
        </>
      )}
      {promptReq && (
        <div role="dialog" aria-modal="true" style={{ position: "fixed", inset: 0, background: "rgba(15,23,42,0.55)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 1000 }}>
          <form className="panel" style={{ width: "min(440px, 92vw)", background: "#fff", padding: "1.25rem", borderRadius: "8px" }}
            onSubmit={(e) => { e.preventDefault(); const r = promptReq; setPromptReq(null); r.resolve(promptInput); }}>
            <h4 style={{ marginTop: 0 }}>{promptReq.title}</h4>
            <input autoFocus value={promptInput} onChange={(e) => setPromptInput(e.target.value)} style={{ width: "100%", marginBottom: "1rem" }} />
            <div className="flex-row" style={{ justifyContent: "flex-end", gap: "0.5rem" }}>
              <button type="button" className="secondary" onClick={() => { const r = promptReq; setPromptReq(null); r.resolve(null); }}>Cancel</button>
              <button type="submit">Confirm</button>
            </div>
          </form>
        </div>
      )}
    </main>
    </OpsPromptContext.Provider>
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
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState<boolean>(false);

  const load = async () => {
    setLoading(true);
    try {
      const [overviewData, integrityData] = await Promise.all([
        api<Record<string, number>>("/v1/ops/overview"),
        api<Integrity>("/v1/ops/ledger/integrity"),
      ]);
      setO(overviewData);
      setIntegrity(integrityData);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const runAction = async (path: string, body: unknown, okMessage: string) => {
    setBusy(true);
    setMsg(null);
    setError(null);
    try {
      await api(path, body === undefined ? { method: "POST" } : { body });
      setMsg(okMessage);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {error && <p className="error">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}
      {loading && !o && <p className="muted">Loading overview…</p>}
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
      <button disabled={busy} onClick={() => void runAction("/v1/ops/rails/recover", undefined, "Rail recovery run")}>
        {busy ? "Running…" : "Recover pending rail results"}
      </button>
      <button disabled={busy} onClick={() => void runAction("/v1/ops/settlements/run", {}, "Settlement run complete")}>
        {busy ? "Running…" : "Run due settlements"}
      </button>
    </>
  );
}

function Exceptions() {
  const askPrompt = useOpsPrompt();
  const [items, setItems] = useState<Item[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const res = await api<Item[]>("/v1/ops/queue");
      setItems(res);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  async function resolveItem(id: string) {
    const raw = await askPrompt("Resolution note");
    if (raw === null) return;
    const resolution = raw.trim();
    if (!resolution) {
      setError("A non-empty resolution note is required.");
      return;
    }
    setBusyId(id);
    setMsg(null);
    setError(null);
    try {
      await api(`/v1/ops/queue/${id}/resolve`, { body: { resolution } });
      setMsg("Exception resolved.");
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      {error && <p className="error">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}
      {loading && items.length === 0 && <p className="muted">Loading exceptions…</p>}
      <table className="grid">
        <thead><tr><th>Severity</th><th>Kind</th><th>Subject</th><th>Details</th><th>Opened</th><th /></tr></thead>
        <tbody>
          {items.map((i) => (
            <tr key={i.id}>
              <td className={`sev-${i.severity}`}>{i.severity}</td>
              <td>{i.kind}</td>
              <td>{i.subject}</td>
              <td className="small mono">{i.details}</td>
              <td className="small">{new Date(i.createdAt).toLocaleString()}</td>
              <td>
                <button className="small" disabled={busyId === i.id} onClick={() => void resolveItem(i.id)}>
                  {busyId === i.id ? "Resolving…" : "Resolve"}
                </button>
              </td>
            </tr>
          ))}
          {!loading && items.length === 0 && (
            <tr><td colSpan={6} className="muted">No open exceptions.</td></tr>
          )}
        </tbody>
      </table>
    </>
  );
}

function Onboarding() {
  const askPrompt = useOpsPrompt();
  const [merchants, setMerchants] = useState<MerchantView[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const res = await api<MerchantView[]>("/v1/ops/merchants");
      setMerchants(res);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  async function review(m: MerchantView, decision: "approve" | "reject") {
    let feeBps: number | undefined;
    if (decision === "approve") {
      const rawFee = await askPrompt("Agreed fee in basis points (100 = 1%)", "100");
      if (rawFee === null) return;
      const trimmed = rawFee.trim();
      if (!trimmed) {
        setError("Fee in basis points is required.");
        return;
      }
      feeBps = Number(trimmed);
      if (!Number.isFinite(feeBps) || feeBps < 0 || !Number.isInteger(feeBps)) {
        setError("Fee must be a non-negative integer in basis points.");
        return;
      }
    }
    setBusyId(m.id);
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>(`/v1/ops/merchants/${m.id}/review`, {
        body: { decision, feeBps, note: "Reviewed in console" },
      });
      setMsg(formatDualControlOutcome(res, `${m.tradingName}: ${decision}`));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      {error && <p className="error">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}
      {loading && merchants.length === 0 && <p className="muted">Loading merchants…</p>}
      <table className="grid">
        <thead><tr><th>Trading name</th><th>Legal name</th><th>Type</th><th>Status</th><th /></tr></thead>
        <tbody>
          {merchants.map((m) => {
            const canReview = m.status === "pending_review";
            const busy = busyId === m.id;
            return (
              <tr key={m.id}>
                <td>{m.tradingName}</td>
                <td>{m.legalName}</td>
                <td>{m.kind}</td>
                <td><span className={`tag ${m.status === "active" ? "active" : m.status === "pending_review" ? "pending" : "failed"}`}>{m.status}</span></td>
                <td>
                  <button className="small" disabled={!canReview || busy} onClick={() => void review(m, "approve")}>Approve</button>
                  <button className="small secondary" disabled={!canReview || busy} onClick={() => void review(m, "reject")}>Reject</button>
                </td>
              </tr>
            );
          })}
          {!loading && merchants.length === 0 && (
            <tr><td colSpan={5} className="muted">No merchants found for onboarding review.</td></tr>
          )}
        </tbody>
      </table>
    </>
  );
}

export function formatDualControlOutcome(res: unknown, completedMessage: string): string {
  if (res && typeof res === "object") {
    const record = res as Record<string, unknown>;
    if (record.code === "pending_approval" || record.status === "PENDING") {
      const detail = typeof record.detail === "string" && record.detail.trim()
        ? record.detail
        : "Awaiting second operator approval in Maker-Checker.";
      return `Pending dual-control approval: ${detail}`;
    }
  }
  return completedMessage;
}

function Disputes() {
  const [rows, setRows] = useState<Record<string, string | number>[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const res = await api<Record<string, string | number>[]>("/v1/ops/disputes");
      setRows(res);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  async function resolve(id: string, outcome: string) {
    setBusyId(id);
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>(`/v1/ops/disputes/${id}/resolve`, {
        body: { outcome, note: "Resolved in console" },
      });
      setMsg(formatDualControlOutcome(res, "Dispute resolved"));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      {error && <p className="error">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}
      {loading && rows.length === 0 && <p className="muted">Loading disputes…</p>}
      <table className="grid">
        <thead><tr><th>Checkout</th><th>Merchant</th><th className="num">Amount</th><th>Reason</th><th /></tr></thead>
        <tbody>
          {rows.map((d) => {
            const id = String(d.id);
            const busy = busyId === id;
            return (
              <tr key={id}>
                <td>{d.checkout} #{d.line}</td>
                <td>{d.merchant}</td>
                <td className="num">{formatMoney(Number(d.amount))}</td>
                <td>{d.reason}</td>
                <td>
                  <button className="small" disabled={busy} onClick={() => void resolve(id, "merchant_won")}>Merchant won</button>
                  <button className="small secondary" disabled={busy} onClick={() => void resolve(id, "customer_won")}>Customer won</button>
                </td>
              </tr>
            );
          })}
          {!loading && rows.length === 0 && (
            <tr><td colSpan={5} className="muted">No open disputes.</td></tr>
          )}
        </tbody>
      </table>
    </>
  );
}

function Reconciliation() {
  const [runs, setRuns] = useState<Record<string, string>[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    api<Record<string, string>[]>("/v1/ops/reconciliation/runs")
      .then((res) => { setRuns(res); setError(null); })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, []);

  return (
    <>
      {error && <p className="error">{error}</p>}
      {loading && runs.length === 0 && <p className="muted">Loading reconciliation runs…</p>}
      <table className="grid">
        <thead><tr><th>Source</th><th>Period</th><th>Summary</th><th>By</th><th>When</th></tr></thead>
        <tbody>
          {runs.map((r) => (
            <tr key={r.id}>
              <td>{r.rail}</td>
              <td>{r.period}</td>
              <td className="mono small">{r.summary}</td>
              <td>{r.imported_by}</td>
              <td className="small">{new Date(r.created_at).toLocaleString()}</td>
            </tr>
          ))}
          {!loading && !error && runs.length === 0 && (
            <tr><td colSpan={5} className="muted">No reconciliation runs found.</td></tr>
          )}
        </tbody>
      </table>
    </>
  );
}

interface MakerCheckerProposal {
  id: string;
  actionType: string;
  resourceType: string;
  resourceId: string;
  merchantId: string | null;
  reason: string | null;
  status: string;
  proposedBy: string;
  proposedAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  expiresAt: string;
}

export const ACTION_LABELS: Record<string, string> = {
  KILL_SWITCH_CHANGE: "Global Kill Switch Change",
  MERCHANT_UNFREEZE: "Unfreeze Merchant Payouts",
  MODULE_ENABLE: "Platform Module Toggle",
  DISPUTE_RESOLUTION: "Dispute Resolution",
  FEE_SCHEDULE_CHANGE: "Merchant Fee Schedule Change",
  CUSTODIAN_CONFIG: "Custodian Trust Configuration",
  LARGE_PAYOUT: "Large Payout Approval",
  ESCROW_RELEASE: "Escrow Release",
  FORCE_SETTLEMENT: "Force Settlement",
  SETTLEMENT_RULE_CHANGE: "Settlement Rule Change",
  DESTINATION_OPS_VERIFY: "Destination Ops Verification",
  VELOCITY_CEILING_CHANGE: "Velocity Ceiling Change",
  PHONE_CHANGE: "Phone Number Change",
};

function MakerChecker({ canApprove }: { canApprove: boolean }) {
  const askPrompt = useOpsPrompt();
  const [proposals, setProposals] = useState<MakerCheckerProposal[]>([]);
  const [statusFilter, setStatusFilter] = useState<string>("PENDING");
  const [loading, setLoading] = useState<boolean>(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    const q = statusFilter === "ALL" ? "" : `?status=${encodeURIComponent(statusFilter)}`;
    try {
      const res = await api<MakerCheckerProposal[]>(`/v1/ops/maker-checker/proposals${q}`);
      setProposals(res);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [statusFilter]);

  async function review(id: string, action: "approve" | "reject") {
    const rawNote = await askPrompt(action === "approve" ? "Approval note (optional)" : "Rejection reason");
    if (rawNote === null) return;
    const note = rawNote.trim();
    if (action === "reject" && !note) {
      setError("A non-empty rejection reason is required.");
      return;
    }
    setBusyId(id);
    setMsg(null);
    setError(null);
    try {
      const res = await api<MakerCheckerProposal>(`/v1/ops/maker-checker/proposals/${id}/${action}`, {
        body: { note },
      });
      setMsg(`Proposal ${res.id} ${res.status.toLowerCase()}.`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div>
      <div className="flex-between">
        <h3>Maker-Checker Dual Control</h3>
        <label className="choice">
          Status:
          <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} style={{ width: "auto" }}>
            <option value="PENDING">Pending</option>
            <option value="APPROVED">Approved</option>
            <option value="REJECTED">Rejected</option>
            <option value="ALL">All</option>
          </select>
        </label>
      </div>
      {!canApprove && <p className="notice warn">View-only: Only an Operations Administrator (ops_admin) can approve dual-control proposals.</p>}
      {error && <p className="error">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}
      {loading && proposals.length === 0 && <p className="muted">Loading proposals…</p>}
      <table className="grid" style={{ marginTop: "1rem" }}>
        <thead>
          <tr><th>Action</th><th>Resource</th><th>Reason</th><th>Proposed By</th><th>Status</th><th>Actions</th></tr>
        </thead>
        <tbody>
          {proposals.map((p) => (
            <tr key={p.id}>
              <td className="small"><strong>{ACTION_LABELS[p.actionType] ?? p.actionType}</strong></td>
              <td className="mono small">{p.resourceType}:{p.resourceId}</td>
              <td className="small">{p.reason ?? "—"}</td>
              <td className="small">{p.proposedBy}</td>
              <td><span className={`tag ${p.status === "APPROVED" ? "active" : p.status === "PENDING" ? "pending" : "failed"}`}>{p.status}</span></td>
              <td>
                {p.status === "PENDING" ? (
                  canApprove ? (
                    <>
                      <button className="small" disabled={busyId === p.id} onClick={() => void review(p.id, "approve")}>Approve</button>
                      <button className="small secondary" disabled={busyId === p.id} onClick={() => void review(p.id, "reject")}>Reject</button>
                    </>
                  ) : (
                    <span className="small muted">Awaiting ops_admin</span>
                  )
                ) : (
                  <span className="small muted">{p.reviewedBy ?? "—"}</span>
                )}
              </td>
            </tr>
          ))}
          {!loading && proposals.length === 0 && (
            <tr><td colSpan={6} className="muted">No maker-checker proposals found.</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

interface KillSwitchStatus {
  globalFrozen: boolean;
  globalFreezeReason: string | null;
  frozenMerchants: string[];
  modules: Record<string, boolean>;
}

function KillSwitches() {
  const [status, setStatus] = useState<KillSwitchStatus | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [busy, setBusy] = useState<boolean>(false);
  const [merchantId, setMerchantId] = useState("");
  const [reason, setReason] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const res = await api<KillSwitchStatus>("/v1/ops/kill-switch/status");
      setStatus(res);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  async function toggleGlobal(frozen: boolean) {
    setBusy(true);
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>("/v1/ops/kill-switch/global", {
        body: { frozen, reason: reason.trim() || undefined },
      });
      setMsg(formatDualControlOutcome(res, frozen ? "Global payout freeze activated." : "Global payout freeze lifted."));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function toggleMerchant(id: string, frozen: boolean) {
    if (!id.trim()) { setError("Enter a merchant UUID."); return; }
    setBusy(true);
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>(`/v1/ops/kill-switch/merchants/${id.trim()}`, {
        body: { frozen, reason: reason.trim() || undefined },
      });
      setMsg(formatDualControlOutcome(res, `Merchant ${id.trim()} ${frozen ? "frozen" : "unfrozen"}.`));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function toggleModule(moduleKey: string, enabled: boolean) {
    setBusy(true);
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>(`/v1/ops/kill-switch/modules/${moduleKey}`, {
        body: { enabled, reason: reason.trim() || undefined },
      });
      setMsg(formatDualControlOutcome(res, `Module ${moduleKey} ${enabled ? "enabled" : "disabled"}.`));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const moduleEntries = status ? Object.entries(status.modules) : [];

  return (
    <div>
      <h3>Stage 1 Kill Switches</h3>
      {error && <p className="error">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}

      <label>Reason / Audit Note
        <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Incident or maintenance reason" />
      </label>

      {loading && !status && <p className="muted">Loading kill switch status…</p>}

      {status && (
        <>
          <section className="panel" style={{ marginTop: "1rem" }}>
            <h4>Global Payout Kill Switch</h4>
            <p>
              Current state:{" "}
              <span className={`tag ${status.globalFrozen ? "failed" : "active"}`}>
                {status.globalFrozen ? "FROZEN" : "ACTIVE"}
              </span>
              {status.globalFreezeReason && <span className="small muted"> — {status.globalFreezeReason}</span>}
            </p>
            <button disabled={busy} onClick={() => void toggleGlobal(!status.globalFrozen)}>
              {status.globalFrozen ? "Unfreeze Global Payouts" : "Freeze Global Payouts"}
            </button>
          </section>

          <section className="panel" style={{ marginTop: "1rem" }}>
            <h4>Merchant Payout Kill Switch</h4>
            <div className="flex-row">
              <input value={merchantId} onChange={(e) => setMerchantId(e.target.value)} placeholder="Merchant UUID" />
              <button disabled={busy} onClick={() => void toggleMerchant(merchantId, true)}>Freeze Merchant</button>
              <button className="secondary" disabled={busy} onClick={() => void toggleMerchant(merchantId, false)}>Unfreeze Merchant</button>
            </div>
            {status.frozenMerchants.length > 0 && (
              <p className="small muted" style={{ marginTop: ".5rem" }}>
                Frozen merchants: {status.frozenMerchants.join(", ")}
              </p>
            )}
          </section>

          <section className="panel" style={{ marginTop: "1rem" }}>
            <h4>Module Route Kill Switches</h4>
            <table className="grid">
              <thead><tr><th>Module</th><th>State</th><th>Action</th></tr></thead>
              <tbody>
                {moduleEntries.map(([mod, enabled]) => (
                  <tr key={mod}>
                    <td className="mono small">{mod}</td>
                    <td><span className={`tag ${enabled ? "active" : "failed"}`}>{enabled ? "ENABLED" : "DISABLED"}</span></td>
                    <td>
                      <button className="small secondary" disabled={busy} onClick={() => void toggleModule(mod, !enabled)}>
                        {enabled ? "Disable" : "Enable"}
                      </button>
                    </td>
                  </tr>
                ))}
                {moduleEntries.length === 0 && (
                  <tr><td colSpan={3} className="muted">No module switches configured.</td></tr>
                )}
              </tbody>
            </table>
          </section>
        </>
      )}
    </div>
  );
}

interface CustodianSafeguardReport {
  trustAccountCode: string;
  currency: string;
  merchantPendingEscrowPesewas: number;
  merchantAvailablePesewas: number;
  merchantReservedPesewas: number;
  totalSafeguardedLiabilitiesPesewas: number;
  clearingReceivablesPesewas: number;
  reconciledBankBalancePesewas: number | null;
  totalTrustAssetsPesewas: number;
  dailyOutflowCapPesewas: number;
  todayTotalOutflowPesewas: number;
  todayRejectedOutflowCount: number;
  outflowFrozen: boolean;
  safeguardingSatisfied: boolean;
}

interface TrustAccountConfig {
  accountCode: string;
  bankCode: string;
  bankAccountNumber: string;
  currency: string;
  dailyOutflowCapPesewas: number;
  perPayoutCapPesewas: number;
  minReserveFloorPesewas: number;
  outflowFrozen: boolean;
}

function CustodianTrust() {
  const [report, setReport] = useState<CustodianSafeguardReport | null>(null);
  const [config, setConfig] = useState<TrustAccountConfig | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    Promise.all([
      api<CustodianSafeguardReport>("/v1/ops/custodian/safeguard-report"),
      api<TrustAccountConfig>("/v1/ops/custodian/config"),
    ])
      .then(([r, c]) => { setReport(r); setConfig(c); setError(null); })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, []);

  return (
    <div>
      <h3>Custodian Trust Safeguarding</h3>
      {error && <p className="error">{error}</p>}
      {loading && !report && <p className="muted">Loading custodian safeguarding report…</p>}
      {report && (
        <>
          <p className={report.safeguardingSatisfied ? "notice ok" : "notice warn"}>
            Trust Safeguarding ({report.trustAccountCode} / {report.currency}):{" "}
            <strong>{report.safeguardingSatisfied ? "SATISFIED" : "DEFICIT / REVIEW REQUIRED"}</strong>
            {report.outflowFrozen && " — OUTFLOW FROZEN"}
          </p>
          <div className="cards">
            <div className="card"><div className="muted">Total Trust Assets</div><div className="big">{formatMoney(report.totalTrustAssetsPesewas)}</div></div>
            <div className="card"><div className="muted">Safeguarded Liabilities</div><div className="big">{formatMoney(report.totalSafeguardedLiabilitiesPesewas)}</div></div>
            <div className="card"><div className="muted">Today's Outflow / Cap</div><div className="big">{formatMoney(report.todayTotalOutflowPesewas)} / {formatMoney(report.dailyOutflowCapPesewas)}</div></div>
            <div className="card"><div className="muted">Rejected Outflows Today</div><div className="big">{report.todayRejectedOutflowCount}</div></div>
          </div>
        </>
      )}
      {config && (
        <section className="panel" style={{ marginTop: "1rem" }}>
          <h4>Primary Trust Account Config</h4>
          <dl>
            <dt>Account Code</dt><dd className="mono">{config.accountCode}</dd>
            <dt>Bank / Account</dt><dd className="mono">{config.bankCode} — {config.bankAccountNumber}</dd>
            <dt>Per-Payout Cap</dt><dd>{formatMoney(config.perPayoutCapPesewas)}</dd>
            <dt>Min Reserve Floor</dt><dd>{formatMoney(config.minReserveFloorPesewas)}</dd>
          </dl>
        </section>
      )}
    </div>
  );
}

interface MerchantSearchRow {
  id: string;
  merchant_code: string | null;
  legal_name: string;
  trading_name: string;
  status: string;
  contact_phone: string | null;
  fee_bps: number;
  frozen: boolean;
  freeze_reason: string | null;
}

interface MerchantControlsData {
  merchant: MerchantSearchRow;
  balances: { bucket: string; currency: string; balance: number }[];
  apiKeys: { id: string; key_prefix: string; key_type: string; status: string; label: string | null; created_at: string }[];
  terminals: { id: string; serial_number: string; model: string; status: string }[];
}

function MerchantControls({ perms }: { perms: OpsPermissions | null }) {
  const askPrompt = useOpsPrompt();
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<MerchantSearchRow[]>([]);
  const [searching, setSearching] = useState<boolean>(true);
  const [selected, setSelected] = useState<MerchantControlsData | null>(null);
  const [loadingDetail, setLoadingDetail] = useState<boolean>(false);
  const [busy, setBusy] = useState<boolean>(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const search = async (q: string) => {
    setSearching(true);
    try {
      const res = await api<MerchantSearchRow[]>(`/v1/ops/merchants/search?q=${encodeURIComponent(q)}`);
      setResults(res);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSearching(false);
    }
  };

  useEffect(() => { void search(""); }, []);

  const openMerchant = async (id: string) => {
    setLoadingDetail(true);
    try {
      const res = await api<MerchantControlsData>(`/v1/ops/merchants/${id}/controls`);
      setSelected(res);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoadingDetail(false);
    }
  };

  async function toggleFreeze(id: string, frozen: boolean) {
    const rawReason = await askPrompt(frozen ? "Reason for freezing merchant payouts:" : "Reason for unfreezing merchant payouts:");
    if (rawReason === null) return;
    const reason = rawReason.trim();
    if (!reason) {
      setError("A non-empty reason is required.");
      return;
    }
    setBusy(true);
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>(`/v1/ops/kill-switch/merchants/${id}`, {
        body: { frozen, reason },
      });
      setMsg(formatDualControlOutcome(res, frozen ? "Merchant payouts frozen." : "Merchant payouts unfrozen."));
      await openMerchant(id);
      await search(query);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function revokeKey(keyId: string, merchantId: string) {
    const rawReason = await askPrompt("Reason for revoking this API key:");
    if (rawReason === null) return;
    const reason = rawReason.trim();
    if (!reason) {
      setError("A non-empty reason is required.");
      return;
    }
    setBusy(true);
    setMsg(null);
    setError(null);
    try {
      await api(`/v1/ops/kill-switch/api-keys/${keyId}`, { body: { active: false, reason } });
      setMsg(`API key ${keyId} revoked immediately.`);
      await openMerchant(merchantId);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const canFreeze = perms?.canFreezeRisk ?? false;

  return (
    <div>
      <h3>Merchant Controls &amp; Overrides</h3>
      <form
        className="flex-row"
        style={{ marginBottom: "1rem" }}
        onSubmit={(e) => {
          e.preventDefault();
          void search(query);
        }}
      >
        <input
          aria-label="Search merchants by name, ID, email, or phone"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search merchant by name, phone, code, or UUID"
        />
        <button type="submit" disabled={searching}>{searching ? "Searching…" : "Search"}</button>
      </form>
      {error && <p className="error">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}

      <table className="grid">
        <thead>
          <tr><th>Merchant</th><th>Phone</th><th>Status</th><th>Payouts</th><th>Fee</th><th></th></tr>
        </thead>
        <tbody>
          {results.map((m) => (
            <tr key={m.id}>
              <td><strong>{m.trading_name}</strong><div className="small muted">{m.legal_name}</div></td>
              <td className="small">{m.contact_phone ?? "—"}</td>
              <td><span className={`tag ${m.status === "active" ? "active" : "pending"}`}>{m.status}</span></td>
              <td><span className={`tag ${m.frozen ? "failed" : "active"}`}>{m.frozen ? "FROZEN" : "ACTIVE"}</span></td>
              <td className="small">{(m.fee_bps / 100).toFixed(2)}%</td>
              <td><button className="small" disabled={loadingDetail} onClick={() => void openMerchant(m.id)}>Inspect</button></td>
            </tr>
          ))}
          {!searching && results.length === 0 && <tr><td colSpan={6} className="muted">No merchants matched.</td></tr>}
        </tbody>
      </table>

      {loadingDetail && <p className="muted">Loading merchant controls…</p>}

      {selected && (
        <section className="panel" style={{ marginTop: "1.25rem" }}>
          <div className="flex-between">
            <h4>{selected.merchant.trading_name} ({selected.merchant.status})</h4>
            {canFreeze && (
              <button
                className={selected.merchant.frozen ? "secondary" : ""}
                disabled={busy}
                onClick={() => void toggleFreeze(selected.merchant.id, !selected.merchant.frozen)}
              >
                {selected.merchant.frozen ? "Unfreeze Merchant Payouts" : "Freeze Merchant Payouts"}
              </button>
            )}
          </div>

          <div className="cards" style={{ marginTop: "0.75rem" }}>
            {selected.balances.map((b) => (
              <div className="card" key={`${b.currency}-${b.bucket}`}>
                <div className="muted">{b.currency} {b.bucket}</div>
                <div className="big">{formatMoney(b.balance)}</div>
              </div>
            ))}
            {selected.balances.length === 0 && <p className="small muted">No ledger accounts opened yet.</p>}
          </div>

          <h4 style={{ marginTop: "1rem" }}>API Keys</h4>
          <table className="grid">
            <thead><tr><th>Prefix</th><th>Tier</th><th>Label</th><th>Status</th><th></th></tr></thead>
            <tbody>
              {selected.apiKeys.map((k) => (
                <tr key={k.id}>
                  <td className="mono small">{k.key_prefix}…</td>
                  <td className="small">{k.key_type}</td>
                  <td className="small">{k.label ?? "—"}</td>
                  <td><span className={`tag ${k.status === "active" ? "active" : "failed"}`}>{k.status}</span></td>
                  <td>
                    {k.status === "active" && canFreeze && (
                      <button className="small secondary" disabled={busy} onClick={() => void revokeKey(k.id, selected.merchant.id)}>
                        Revoke Key
                      </button>
                    )}
                  </td>
                </tr>
              ))}
              {selected.apiKeys.length === 0 && <tr><td colSpan={5} className="muted">No API keys.</td></tr>}
            </tbody>
          </table>

          <h4 style={{ marginTop: "1rem" }}>POS Terminals</h4>
          <table className="grid">
            <thead><tr><th>Serial</th><th>Model</th><th>Status</th></tr></thead>
            <tbody>
              {selected.terminals.map((t) => (
                <tr key={t.id}>
                  <td className="mono small">{t.serial_number}</td>
                  <td className="small">{t.model}</td>
                  <td><span className={`tag ${t.status === "ACTIVE" ? "active" : "pending"}`}>{t.status}</span></td>
                </tr>
              ))}
              {selected.terminals.length === 0 && <tr><td colSpan={3} className="muted">No POS terminals registered.</td></tr>}
            </tbody>
          </table>
        </section>
      )}
    </div>
  );
}
