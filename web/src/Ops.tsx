import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { api } from "./api";
import { formatMoney, parseMoney } from "./money";
import {
  IconActivity,
  IconAlertTriangle,
  IconArrowLeftRight,
  IconLandmark,
  IconLayoutGrid,
  IconPower,
  IconRefresh,
  IconSearch,
  IconShieldAlert,
  IconShieldCheck,
  IconSliders,
  IconUserCheck,
  IconX,
} from "./ui/Icons";

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

const TAB_ICONS: Record<OpsTab, ReactNode> = {
  Overview: <IconLayoutGrid size={15} />,
  "Merchant Controls": <IconSliders size={15} />,
  "Maker-Checker": <IconShieldCheck size={15} />,
  "Kill Switches": <IconPower size={15} />,
  "Custodian Trust": <IconLandmark size={15} />,
  Onboarding: <IconUserCheck size={15} />,
  Disputes: <IconShieldAlert size={15} />,
  Reconciliation: <IconArrowLeftRight size={15} />,
  Exceptions: <IconAlertTriangle size={15} />,
  "System Health": <IconActivity size={15} />,
};

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
      <main className="workspace-main">
        {meLoading && (
          <div className="panel" style={{ maxWidth: "480px", margin: "2rem auto", textAlign: "center" }}>
            <p className="muted" style={{ margin: 0 }}>Loading operator permissions…</p>
          </div>
        )}
        {meError && <p className="notice error" role="alert">Failed to load operator permissions: {meError}</p>}

        {!meLoading && !meError && me && (
          <div className="workspace-shell">
            <aside className="workspace-sidebar" aria-label="Operations navigation">
              <div className="sidebar-context">
                <div className="sidebar-context-title">Operations Console</div>
                <div className="sidebar-context-meta">
                  <span className="tag active">{me.primaryRole}</span>
                  <span className="mono" style={{ fontSize: "0.72rem" }}>{me.subject}</span>
                </div>
              </div>

              <div className="sidebar-group">
                <div className="sidebar-group-label">Command Modules</div>
                <nav className="sidebar-nav">
                  {tabs.map((t) => (
                    <button
                      key={t}
                      type="button"
                      className={`sidebar-btn ${t === tab ? "active" : ""}`}
                      onClick={() => setTab(t)}
                    >
                      {TAB_ICONS[t]}
                      <span>{t}</span>
                    </button>
                  ))}
                </nav>
              </div>
            </aside>

            <section className="workspace-content">
              <div className="page-header">
                <div>
                  <h1 style={{ marginBottom: "0.15rem" }}>{tab}</h1>
                  <p className="page-header-subtitle">
                    Operator <strong>{me.subject}</strong> · Role: <strong>{me.primaryRole}</strong>
                  </p>
                </div>
                {(me.permissions.canFreezeRisk ?? false) && !globalFrozen && (
                  <button
                    type="button"
                    className="btn-danger"
                    disabled={freezingGlobal}
                    onClick={() => void quickEmergencyFreeze()}
                  >
                    <IconPower size={15} />
                    {freezingGlobal ? "Freezing…" : "Emergency Freeze All Payouts"}
                  </button>
                )}
              </div>

              {killSwitchError && <p className="notice error">Kill switch status error: {killSwitchError}</p>}
              {globalFrozen && (
                <div className="ops-freeze-banner" role="alert">
                  <IconAlertTriangle size={20} />
                  <div>
                    <strong>GLOBAL PAYOUT FREEZE ACTIVE</strong> — All merchant payouts are paused. Unfreezing requires Maker-Checker dual approval.
                  </div>
                </div>
              )}
              {bannerError && <p className="notice error" role="alert">{bannerError}</p>}
              {bannerMsg && <p className="notice ok">{bannerMsg}</p>}

              {tabs.includes(tab) && (
                <>
                  {tab === "Overview" && <Overview canManageFinance={me.permissions.canManageFinance ?? false} />}
                  {tab === "Merchant Controls" && <MerchantControls perms={me.permissions} />}
                  {tab === "Maker-Checker" && <MakerChecker canApprove={me.permissions.canApproveMakerChecker ?? false} />}
                  {tab === "Kill Switches" && <KillSwitches />}
                  {tab === "Custodian Trust" && <CustodianTrust canManageFinance={me.permissions.canManageFinance ?? false} />}
                  {tab === "System Health" && <SystemHealth />}
                  {tab === "Exceptions" && <Exceptions />}
                  {tab === "Onboarding" && <Onboarding />}
                  {tab === "Disputes" && (
                    <Disputes
                      canOpen={me.permissions.canManageSupport ?? false}
                      canResolve={me.permissions.canManageFinance ?? false}
                    />
                  )}
                  {tab === "Reconciliation" && <Reconciliation />}
                </>
              )}
            </section>
          </div>
        )}

        {promptReq && (
          <div role="dialog" aria-modal="true" className="modal-backdrop">
            <form
              className="modal-content"
              style={{ maxWidth: "460px" }}
              onSubmit={(e) => { e.preventDefault(); const r = promptReq; setPromptReq(null); r.resolve(promptInput); }}
            >
              <div className="flex-between" style={{ marginBottom: "0.75rem" }}>
                <h3 style={{ margin: 0 }}>{promptReq.title}</h3>
                <button
                  type="button"
                  className="small ghost"
                  aria-label="Cancel"
                  onClick={() => { const r = promptReq; setPromptReq(null); r.resolve(null); }}
                >
                  <IconX size={16} />
                </button>
              </div>
              <input
                autoFocus
                value={promptInput}
                onChange={(e) => setPromptInput(e.target.value)}
                style={{ marginBottom: "1.1rem" }}
              />
              <div className="flex-row" style={{ justifyContent: "flex-end" }}>
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
// System Health Status
// -------------------------------------------------------------------------------------------------
interface RailHealth {
  rail: string;
  circuitState: string;
  totalRequests: number;
  successfulRequests: number;
  failedRequests: number;
  timeoutRequests: number;
  errorRate: number;
  averageLatencyMs: number;
}

const MONITORED_RAILS = ["mtn", "telecel", "airteltigo", "ghqr", "card"] as const;

function SystemHealth() {
  const [rails, setRails] = useState<RailHealth[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const settled = await Promise.allSettled(
        MONITORED_RAILS.map((r) => api<RailHealth>(`/v1/ops/rails/health/${r}`)),
      );
      const okRails: RailHealth[] = [];
      const failedRails: string[] = [];
      settled.forEach((res, idx) => {
        if (res.status === "fulfilled") {
          okRails.push(res.value);
        } else {
          failedRails.push(`${MONITORED_RAILS[idx].toUpperCase()}: ${res.reason instanceof Error ? res.reason.message : String(res.reason)}`);
        }
      });
      setRails(okRails);
      if (failedRails.length > 0) {
        setError(`Rail telemetry errors — ${failedRails.join(" · ")}`);
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  return (
    <div className="panel">
      <div className="flex-between" style={{ marginBottom: "0.75rem" }}>
        <div>
          <h3 style={{ margin: 0 }}>Payment Rail Health &amp; Circuit Breakers</h3>
          <p className="small muted" style={{ margin: "0.2rem 0 0" }}>
            Live circuit state, error rate, timeout count, and average latency from NetworkHealthDetector.
          </p>
        </div>
        <button type="button" className="secondary small" disabled={loading} onClick={() => void load()}>
          <IconRefresh size={14} /> Refresh
        </button>
      </div>
      {error && <p className="notice error" role="alert">{error}</p>}
      <div className="table-wrap" style={{ margin: 0 }}>
        <table className="grid">
          <thead>
            <tr>
              <th>Rail</th>
              <th>Circuit State</th>
              <th className="num">Requests</th>
              <th className="num">Failed / Timeouts</th>
              <th className="num">Error Rate</th>
              <th className="num">Avg Latency</th>
            </tr>
          </thead>
          <tbody>
            {rails.map((r) => {
              const state = String(r.circuitState).toUpperCase();
              const tone = state === "CLOSED" ? "active" : state === "HALF_OPEN" ? "pending" : "failed";
              return (
                <tr key={r.rail}>
                  <td><strong>{r.rail.toUpperCase()}</strong></td>
                  <td><span className={`tag ${tone}`}>{state}</span></td>
                  <td className="num mono">{r.totalRequests}</td>
                  <td className="num mono">{r.failedRequests} / {r.timeoutRequests}</td>
                  <td className="num mono">{(Number(r.errorRate || 0) * 100).toFixed(1)}%</td>
                  <td className="num mono">{Math.round(Number(r.averageLatencyMs || 0))} ms</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// Overview
// -------------------------------------------------------------------------------------------------
interface OpenRailTx {
  id: string;
  rail: string;
  direction: string;
  reference: string;
  amount: number;
  currency: string;
  msisdn: string | null;
  status: string;
  railReference: string | null;
  failureReason: string | null;
  createdAt: string;
}

function Overview({ canManageFinance }: { canManageFinance: boolean }) {
  const [o, setO] = useState<Record<string, number> | null>(null);
  const [integrity, setIntegrity] = useState<Integrity | null>(null);
  const [openRails, setOpenRails] = useState<OpenRailTx[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState<boolean>(false);

  const load = async () => {
    setLoading(true);
    try {
      const [overviewData, integrityData, openRailData] = await Promise.all([
        api<Record<string, number>>("/v1/ops/overview"),
        api<Integrity>("/v1/ops/ledger/integrity"),
        api<OpenRailTx[]>("/v1/ops/rails/open").catch(() => [] as OpenRailTx[]),
      ]);
      setO(overviewData);
      setIntegrity(integrityData);
      setOpenRails(openRailData);
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
      const res = await api<Record<string, unknown>>(path, body === undefined ? { method: "POST" } : { body });
      const resolved = typeof res?.resolved === "number" ? ` (${res.resolved} resolved)` : "";
      setMsg(`${okMessage}${resolved}`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const ledgerHealthy = integrity
    ? integrity.accountsWithDrift === 0
      && integrity.unbalancedTransactions === 0
      && Object.values(integrity.netByCurrency ?? {}).every((v) => v === 0)
    : null;

  return (
    <>
      {error && <p className="notice error" role="alert">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}
      {loading && !o && <p className="muted">Loading overview…</p>}
      {o && (
        <div className="kpi-grid">
          <div className="kpi-card primary">
            <div className="kpi-label">Paid Last 24h</div>
            <div className="kpi-value">{formatMoney(Number(o.paid_24h))}</div>
            <div className="kpi-meta">{o.checkouts_24h} completed checkouts</div>
          </div>
          <div className="kpi-card">
            <div className="kpi-label">Open Exceptions</div>
            <div className="kpi-value">{o.open_items}</div>
            <div className="kpi-meta">Requires operator triage</div>
          </div>
          <div className="kpi-card">
            <div className="kpi-label">Pending Rail Results</div>
            <div className="kpi-value">{o.rail_open}</div>
            <div className="kpi-meta">Awaiting upstream confirmation</div>
          </div>
          <div className="kpi-card">
            <div className="kpi-label">Outbox / Dead Webhooks</div>
            <div className="kpi-value">{o.outbox_backlog} / {o.webhooks_dead}</div>
            <div className="kpi-meta">Delivery queue status</div>
          </div>
          <div className="kpi-card">
            <div className="kpi-label">Reviews / Disputes</div>
            <div className="kpi-value">{o.merchants_pending_review} / {o.disputes_open}</div>
            <div className="kpi-meta">Onboarding &amp; chargebacks</div>
          </div>
        </div>
      )}
      {integrity && (
        <div className={ledgerHealthy ? "notice ok" : "notice warn"}>
          <strong>{ledgerHealthy ? "Ledger Integrity Verified" : "Ledger Integrity Alert"}</strong>
          <span>
            {integrity.accountsWithDrift} accounts with drift · {integrity.unbalancedTransactions} unbalanced transactions ·
            Net by currency: <span className="mono">{Object.entries(integrity.netByCurrency).map(([c, v]) => `${c} ${v}`).join(", ") || "—"}</span>
          </span>
        </div>
      )}
      {canManageFinance && (
        <div className="panel" style={{ marginTop: "1rem" }}>
          <h3>Operational Recovery &amp; Batch Jobs</h3>
          <div className="ops-action-bar" style={{ marginTop: "0.5rem" }}>
            <button disabled={busy} onClick={() => void runAction("/v1/ops/rails/recover", undefined, "Rail recovery run")}>
              <IconRefresh size={15} />
              {busy ? "Running…" : "Recover Pending Rail Results"}
            </button>
            <button className="secondary" disabled={busy} onClick={() => void runAction("/v1/ops/settlements/run", {}, "Settlement run complete")}>
              <IconLandmark size={15} />
              {busy ? "Running…" : "Run Due Settlements"}
            </button>
          </div>
        </div>
      )}

      <div className="panel" style={{ marginTop: "1rem" }}>
        <div className="flex-between" style={{ marginBottom: "0.65rem" }}>
          <h3 style={{ margin: 0 }}>Open / Stuck Rail Transactions ({openRails.length})</h3>
          <button type="button" className="secondary small" disabled={loading} onClick={() => void load()}>
            Refresh
          </button>
        </div>
        <div className="table-wrap" style={{ margin: 0 }}>
          <table className="grid">
            <thead>
              <tr>
                <th>Rail</th>
                <th>Direction</th>
                <th>Reference</th>
                <th>MSISDN</th>
                <th className="num">Amount</th>
                <th>Status</th>
                <th>Notes</th>
              </tr>
            </thead>
            <tbody>
              {openRails.map((tx) => (
                <tr key={tx.id}>
                  <td><strong>{tx.rail.toUpperCase()}</strong></td>
                  <td className="small">{tx.direction}</td>
                  <td className="mono small">{tx.reference}</td>
                  <td className="mono small">{tx.msisdn ?? "—"}</td>
                  <td className="num mono">{formatMoney(tx.amount, tx.currency)}</td>
                  <td><span className={`tag ${tx.status === "unknown" ? "failed" : "pending"}`}>{tx.status}</span></td>
                  <td className="small muted">{tx.failureReason ?? "Awaiting customer approval / rail confirmation"}</td>
                </tr>
              ))}
              {openRails.length === 0 && (
                <tr><td colSpan={7} className="muted" style={{ textAlign: "center", padding: "1.25rem" }}>No open or stuck rail transactions.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

// -------------------------------------------------------------------------------------------------
// Exceptions
// -------------------------------------------------------------------------------------------------
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
      {error && <p className="notice error" role="alert">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}
      {loading && items.length === 0 && <p className="muted">Loading exceptions…</p>}
      <div className="table-wrap">
        <table className="grid">
          <thead><tr><th>Severity</th><th>Kind</th><th>Subject</th><th>Details</th><th>Opened</th><th /></tr></thead>
          <tbody>
            {items.map((i) => (
              <tr key={i.id}>
                <td><span className={`sev-${i.severity}`}>{i.severity}</span></td>
                <td><strong>{i.kind}</strong></td>
                <td>{i.subject}</td>
                <td className="small mono">{i.details}</td>
                <td className="small muted">{new Date(i.createdAt).toLocaleString()}</td>
                <td className="num">
                  <button className="small" disabled={busyId === i.id} onClick={() => void resolveItem(i.id)}>
                    {busyId === i.id ? "Resolving…" : "Resolve"}
                  </button>
                </td>
              </tr>
            ))}
            {!loading && items.length === 0 && (
              <tr><td colSpan={6} className="muted" style={{ textAlign: "center", padding: "1.5rem" }}>No open exceptions.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}

// -------------------------------------------------------------------------------------------------
// Onboarding
// -------------------------------------------------------------------------------------------------
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
      {error && <p className="notice error" role="alert">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}
      {loading && merchants.length === 0 && <p className="muted">Loading merchants…</p>}
      <div className="table-wrap">
        <table className="grid">
          <thead><tr><th>Trading name</th><th>Legal name</th><th>Type</th><th>Status</th><th /></tr></thead>
          <tbody>
            {merchants.map((m) => {
              const canReview = m.status === "pending_review";
              const busy = busyId === m.id;
              return (
                <tr key={m.id}>
                  <td><strong>{m.tradingName}</strong></td>
                  <td className="muted">{m.legalName}</td>
                  <td>{m.kind}</td>
                  <td><span className={`tag ${m.status === "active" ? "active" : m.status === "pending_review" ? "pending" : "failed"}`}>{m.status}</span></td>
                  <td className="num" style={{ whiteSpace: "nowrap" }}>
                    <button className="small" disabled={!canReview || busy} onClick={() => void review(m, "approve")}>Approve</button>
                    <button className="small secondary" disabled={!canReview || busy} onClick={() => void review(m, "reject")}>Reject</button>
                  </td>
                </tr>
              );
            })}
            {!loading && merchants.length === 0 && (
              <tr><td colSpan={5} className="muted" style={{ textAlign: "center", padding: "1.5rem" }}>No merchants found for onboarding review.</td></tr>
            )}
          </tbody>
        </table>
      </div>
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

// -------------------------------------------------------------------------------------------------
// Disputes
// -------------------------------------------------------------------------------------------------
function Disputes({ canOpen, canResolve }: { canOpen: boolean; canResolve: boolean }) {
  const [rows, setRows] = useState<Record<string, string | number>[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [opening, setOpening] = useState<boolean>(false);
  const [submittingOpen, setSubmittingOpen] = useState<boolean>(false);
  const [form, setForm] = useState({ checkout: "", line: "0", amount: "", reason: "", caseReference: "" });

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

  async function openDispute(e: React.FormEvent) {
    e.preventDefault();
    setMsg(null);
    setError(null);
    const amount = parseMoney(form.amount);
    const line = Number(form.line);
    if (!form.checkout.trim() || !amount || amount <= 0 || !Number.isInteger(line) || line < 0 || !form.reason.trim()) {
      setError("Enter a checkout reference, non-negative allocation line, valid GH₵ amount, and reason.");
      return;
    }
    setSubmittingOpen(true);
    try {
      await api("/v1/ops/disputes", {
        body: {
          checkout: form.checkout.trim(),
          line,
          amount,
          reason: form.reason.trim(),
          caseReference: form.caseReference.trim() || null,
        },
      });
      setForm({ checkout: "", line: "0", amount: "", reason: "", caseReference: "" });
      setOpening(false);
      setMsg("Dispute opened and merchant reserve held.");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSubmittingOpen(false);
    }
  }

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
      <div className="flex-between" style={{ marginBottom: "0.75rem" }}>
        <span className="small muted">{rows.length} open dispute{rows.length === 1 ? "" : "s"}</span>
        {canOpen && (
          <button type="button" className="small" onClick={() => setOpening(!opening)}>
            {opening ? "Close Form" : "+ Open Dispute"}
          </button>
        )}
      </div>
      {canOpen && opening && (
        <form className="panel" style={{ marginBottom: "1rem" }} onSubmit={(e) => void openDispute(e)}>
          <h4 style={{ marginTop: 0 }}>Open Chargeback / Dispute</h4>
          <div className="grid-2">
            <label>Checkout Reference<input className="mono" value={form.checkout} onChange={(e) => setForm({ ...form, checkout: e.target.value })} placeholder="chk_..." required /></label>
            <label>Allocation Line #<input inputMode="numeric" value={form.line} onChange={(e) => setForm({ ...form, line: e.target.value })} required /></label>
            <label>Disputed Amount (GH₵)<input className="mono" inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} placeholder="50.00" required /></label>
            <label>Bank / MTN Case Ref (optional)<input className="mono" value={form.caseReference} onChange={(e) => setForm({ ...form, caseReference: e.target.value })} placeholder="MML-CASE-001" /></label>
          </div>
          <label>Reason<input value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} placeholder="Customer reported unauthorized debit or non-delivery" required /></label>
          <button type="submit" disabled={submittingOpen}>{submittingOpen ? "Opening…" : "Open Dispute"}</button>
        </form>
      )}
      {error && <p className="notice error" role="alert">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}
      {loading && rows.length === 0 && <p className="muted">Loading disputes…</p>}
      <div className="table-wrap">
        <table className="grid">
          <thead><tr><th>Checkout</th><th>Merchant</th><th className="num">Amount</th><th>Reason</th><th /></tr></thead>
          <tbody>
            {rows.map((d) => {
              const id = String(d.id);
              const busy = busyId === id;
              return (
                <tr key={id}>
                  <td className="mono small">{d.checkout} #{d.line}</td>
                  <td><strong>{d.merchant}</strong></td>
                  <td className="num mono">{formatMoney(Number(d.amount))}</td>
                  <td>{d.reason}</td>
                  <td className="num" style={{ whiteSpace: "nowrap" }}>
                    {canResolve ? (
                      <>
                        <button className="small" disabled={busy} onClick={() => void resolve(id, "merchant_won")}>Merchant won</button>
                        <button className="small secondary" disabled={busy} onClick={() => void resolve(id, "customer_won")}>Customer won</button>
                      </>
                    ) : (
                      <span className="small muted">Finance required</span>
                    )}
                  </td>
                </tr>
              );
            })}
            {!loading && rows.length === 0 && (
              <tr><td colSpan={5} className="muted" style={{ textAlign: "center", padding: "1.5rem" }}>No open disputes.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}

// -------------------------------------------------------------------------------------------------
// Reconciliation
// -------------------------------------------------------------------------------------------------
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
      {error && <p className="notice error" role="alert">{error}</p>}
      {loading && runs.length === 0 && <p className="muted">Loading reconciliation runs…</p>}
      <div className="table-wrap">
        <table className="grid">
          <thead><tr><th>Source</th><th>Period</th><th>Summary</th><th>By</th><th>When</th></tr></thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id}>
                <td><strong>{r.rail}</strong></td>
                <td className="mono small">{r.period}</td>
                <td className="mono small">{r.summary}</td>
                <td>{r.imported_by}</td>
                <td className="small muted">{new Date(r.created_at).toLocaleString()}</td>
              </tr>
            ))}
            {!loading && !error && runs.length === 0 && (
              <tr><td colSpan={5} className="muted" style={{ textAlign: "center", padding: "1.5rem" }}>No reconciliation runs found.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}

// -------------------------------------------------------------------------------------------------
// Maker-Checker Dual Control
// -------------------------------------------------------------------------------------------------
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
      <div className="panel" style={{ padding: "0.85rem 1rem", marginBottom: "0.85rem" }}>
        <div className="flex-between">
          <div>
            <strong style={{ fontSize: "0.92rem" }}>Dual-Control Approval Queue</strong>
            <div className="small muted">Sensitive actions require a second independent ops_admin approval.</div>
          </div>
          <select aria-label="Filter proposal status" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} style={{ width: "auto", margin: 0 }}>
            <option value="PENDING">Pending</option>
            <option value="APPROVED">Approved</option>
            <option value="REJECTED">Rejected</option>
            <option value="ALL">All</option>
          </select>
        </div>
      </div>

      {!canApprove && <p className="notice warn">View-only: Only an Operations Administrator (ops_admin) can approve dual-control proposals.</p>}
      {error && <p className="notice error" role="alert">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}
      {loading && proposals.length === 0 && <p className="muted">Loading proposals…</p>}

      <div className="table-wrap">
        <table className="grid">
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
                <td className="num" style={{ whiteSpace: "nowrap" }}>
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
              <tr><td colSpan={6} className="muted" style={{ textAlign: "center", padding: "1.5rem" }}>No maker-checker proposals found.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// Kill Switches
// -------------------------------------------------------------------------------------------------
export interface ModuleSwitchRow {
  key: string;
  label: string;
  description: string;
  enabled: boolean;
}

const PAYMENT_SWITCHES: Record<string, [string, string]> = {
  "card-payments": ["Card payments", "New card payments and card tokenisation (online checkout and in-store)."],
  "mtn-collections": ["MTN MoMo payments in", "Customers paying by MTN Mobile Money."],
  "mtn-payouts": ["MTN MoMo payouts", "Paying merchants and customers out to MTN Mobile Money."],
};

const FEATURE_SWITCHES: Record<string, [string, string]> = {
  checkout: ["Online checkout", "Creating and paying checkouts for online stores and marketplaces."],
  restaurant: ["Restaurant ordering", "Floor plans, tables, orders and kitchen screens."],
  pos: ["Point of sale (physical stores)", "POS terminals, tap-to-pay and offline sales."],
  "consumer-wallet": ["Customer wallet", "Wallet balances, wallet-to-wallet transfers and paying with the wallet."],
  ussd: ["USSD", "Dial-in wallet menu (needs a short code)."],
  mandates: ["Recurring payments", "Customer mandates for subscriptions and repeat charges."],
  fx: ["FX conversion", "Foreign-currency quotes and conversions."],
};

const NOT_IN_BUILD: Record<string, [string, string]> = {
  lending: ["Lending and credit", "Loans, overdrafts, earned-wage access and cash advances."],
  "virtual-cards": ["Virtual card issuing", "Issuing cards to customers (card acceptance is a payment switch above)."],
  "bill-payments": ["Bill payments and airtime", "Utilities and airtime purchases."],
  "agent-banking": ["Agent banking", "Cash-in and cash-out agents."],
  "fx-trading": ["FX trading", "Trading foreign currency."],
};

/** Groups the raw switch map into labelled rows; consumer-w2w is the same switch as consumer-wallet. */
export function groupModuleSwitches(modules: Record<string, boolean>) {
  const row = (map: Record<string, [string, string]>, key: string): ModuleSwitchRow =>
    ({ key, label: map[key][0], description: map[key][1], enabled: modules[key] ?? true });
  const payments = Object.keys(PAYMENT_SWITCHES).filter((k) => k in modules).map((k) => row(PAYMENT_SWITCHES, k));
  const features = Object.keys(FEATURE_SWITCHES).filter((k) => k in modules).map((k) => row(FEATURE_SWITCHES, k));
  const notInBuild = Object.keys(NOT_IN_BUILD).filter((k) => k in modules).map((k) => row(NOT_IN_BUILD, k));
  const known = new Set([...Object.keys(PAYMENT_SWITCHES), ...Object.keys(FEATURE_SWITCHES),
    ...Object.keys(NOT_IN_BUILD), "consumer-w2w"]);
  for (const key of Object.keys(modules)) {
    if (!known.has(key)) features.push({ key, label: key, description: "", enabled: modules[key] });
  }
  return { payments, features, notInBuild };
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
  const [confirmOff, setConfirmOff] = useState<string | null>(null);

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
    const trimmed = id.trim();
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(trimmed)) {
      setError("Enter a valid merchant UUID.");
      return;
    }
    setBusy(true);
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>(`/v1/ops/kill-switch/merchants/${encodeURIComponent(trimmed)}`, {
        body: { frozen, reason: reason.trim() || undefined },
      });
      setMsg(formatDualControlOutcome(res, `Merchant ${trimmed} ${frozen ? "frozen" : "unfrozen"}.`));
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
  const grouped = groupModuleSwitches(status?.modules ?? {});

  function renderSwitchRows(rows: ModuleSwitchRow[], confirmBeforeOff: boolean) {
    return rows.map((row) => (
      <tr key={row.key}>
        <td>
          <strong>{row.label}</strong>
          <div className="small muted">{row.description}</div>
        </td>
        <td><span className={`tag ${row.enabled ? "active" : "failed"}`}>{row.enabled ? "ON" : "OFF"}</span></td>
        <td className="num">
          {row.enabled && confirmBeforeOff && confirmOff !== row.key && (
            <button className="small secondary" disabled={busy} onClick={() => setConfirmOff(row.key)}>Turn off</button>
          )}
          {row.enabled && confirmBeforeOff && confirmOff === row.key && (
            <span className="flex-row">
              <button className="small btn-danger" disabled={busy}
                onClick={() => { setConfirmOff(null); void toggleModule(row.key, false); }}>Confirm turn off</button>
              <button className="small secondary" disabled={busy} onClick={() => setConfirmOff(null)}>Cancel</button>
            </span>
          )}
          {row.enabled && !confirmBeforeOff && (
            <button className="small secondary" disabled={busy} onClick={() => void toggleModule(row.key, false)}>Turn off</button>
          )}
          {!row.enabled && (
            <button className="small" disabled={busy} onClick={() => void toggleModule(row.key, true)}>Turn on</button>
          )}
        </td>
      </tr>
    ));
  }

  return (
    <div>
      {error && <p className="notice error" role="alert">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}

      <div className="panel" style={{ marginBottom: "1rem" }}>
        <label style={{ margin: 0 }}>
          Audit Reason / Incident Note
          <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Incident ticket or maintenance reason (attached to all actions below)" />
        </label>
      </div>

      {loading && !status && <p className="muted">Loading kill switch status…</p>}

      {status && (
        <>
          <div className="grid-2" style={{ marginBottom: "1rem" }}>
            <section className="panel">
              <div className="flex-between" style={{ marginBottom: "0.65rem" }}>
                <h3 style={{ margin: 0 }}>Global Payout Kill Switch</h3>
                <span className={`tag ${status.globalFrozen ? "failed" : "active"}`}>
                  {status.globalFrozen ? "FROZEN" : "ACTIVE"}
                </span>
              </div>
              {status.globalFreezeReason && <p className="small muted">Reason: {status.globalFreezeReason}</p>}
              <button
                className={status.globalFrozen ? "secondary" : "btn-danger"}
                disabled={busy}
                onClick={() => void toggleGlobal(!status.globalFrozen)}
              >
                <IconPower size={15} />
                {status.globalFrozen ? "Unfreeze Global Payouts" : "Freeze Global Payouts"}
              </button>
            </section>

            <section className="panel">
              <h3 style={{ marginBottom: "0.65rem" }}>Merchant Payout Kill Switch</h3>
              <div className="flex-row">
                <input
                  className="mono"
                  value={merchantId}
                  onChange={(e) => setMerchantId(e.target.value)}
                  placeholder="Merchant UUID"
                  style={{ flex: "1 1 180px", margin: 0 }}
                />
                <button className="btn-danger small" style={{ margin: 0 }} disabled={busy} onClick={() => void toggleMerchant(merchantId, true)}>Freeze</button>
                <button className="secondary small" style={{ margin: 0 }} disabled={busy} onClick={() => void toggleMerchant(merchantId, false)}>Unfreeze</button>
              </div>
              {status.frozenMerchants.length > 0 && (
                <p className="small muted mono" style={{ marginTop: "0.65rem", marginBottom: 0 }}>
                  Frozen: {status.frozenMerchants.join(", ")}
                </p>
              )}
            </section>
          </div>

          <section className="panel" style={{ marginBottom: "1rem" }}>
            <h3>Payment Switches</h3>
            <p className="small muted">
              Turning a payment switch off stops new payments only. Payments already in progress keep being
              checked and settled, and refunds keep working. Turning a switch back on needs a second admin's approval.
            </p>
            <div className="table-wrap" style={{ margin: 0 }}>
              <table className="grid">
                <thead><tr><th>Payment method</th><th>State</th><th /></tr></thead>
                <tbody>{renderSwitchRows(grouped.payments, true)}</tbody>
              </table>
            </div>
          </section>

          <section className="panel">
            <h3>Feature Switches</h3>
            <div className="table-wrap" style={{ margin: 0 }}>
              <table className="grid">
                <thead><tr><th>Feature</th><th>State</th><th /></tr></thead>
                <tbody>
                  {renderSwitchRows(grouped.features, true)}
                  {moduleEntries.length === 0 && (
                    <tr><td colSpan={3} className="muted" style={{ textAlign: "center", padding: "1.25rem" }}>No switches configured.</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </section>

          {grouped.notInBuild.length > 0 && (
            <section className="panel" style={{ marginTop: "1rem" }}>
              <h4>Not in this launch</h4>
              <p className="small muted">
                These are not part of the launch build, so they cannot be turned on here. Bringing them in needs a
                release with ELEPAY_DORMANT_MODULES_ENABLED=true and, for lending, a Bank of Ghana digital credit licence.
              </p>
              <ul className="small">
                {grouped.notInBuild.map((row) => <li key={row.key}><strong>{row.label}</strong> — {row.description}</li>)}
              </ul>
            </section>
          )}
        </>
      )}
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// Custodian Trust Safeguarding
// -------------------------------------------------------------------------------------------------
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
  custodianConfigured: boolean;
  safeguardingSatisfied: boolean;
}

interface TrustAccountConfig {
  accountCode: string;
  bankCode: string | null;
  bankAccountNumber: string | null;
  currency: string;
  dailyOutflowCapPesewas: number;
  perPayoutCapPesewas: number;
  minReserveFloorPesewas: number;
  outflowFrozen: boolean;
}

function CustodianTrust({ canManageFinance }: { canManageFinance: boolean }) {
  const [report, setReport] = useState<CustodianSafeguardReport | null>(null);
  const [config, setConfig] = useState<TrustAccountConfig | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const [r, c] = await Promise.all([
        api<CustodianSafeguardReport>("/v1/ops/custodian/safeguard-report"),
        api<TrustAccountConfig>("/v1/ops/custodian/config"),
      ]);
      setReport(r);
      setConfig(c);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const outflowPct = report && report.dailyOutflowCapPesewas > 0
    ? Math.min(100, Math.round((report.todayTotalOutflowPesewas / report.dailyOutflowCapPesewas) * 100))
    : 0;

  return (
    <div>
      {error && <p className="notice error" role="alert">{error}</p>}
      {loading && !report && <p className="muted">Loading custodian safeguarding report…</p>}
      {report && (
        <>
          <div className={report.safeguardingSatisfied ? "notice ok" : "notice warn"}>
            <strong>
              Trust Safeguarding ({report.trustAccountCode} / {report.currency}):{" "}
              {!report.custodianConfigured ? "NO CUSTODIAN CONFIGURED" : report.safeguardingSatisfied ? "SATISFIED" : "DEFICIT / REVIEW REQUIRED"}
              {report.outflowFrozen && " — OUTFLOW FROZEN"}
            </strong>
          </div>
          <div className="kpi-grid">
            <div className="kpi-card primary">
              <div className="kpi-label">Total Trust Assets</div>
              <div className="kpi-value">{report.custodianConfigured ? formatMoney(report.totalTrustAssetsPesewas) : "—"}</div>
              <div className="kpi-meta">{report.custodianConfigured ? "Reconciled bank & clearing" : "No custodian account"}</div>
            </div>
            <div className="kpi-card">
              <div className="kpi-label">Safeguarded Liabilities</div>
              <div className="kpi-value">{formatMoney(report.totalSafeguardedLiabilitiesPesewas)}</div>
              <div className="kpi-meta">Merchant escrow, available &amp; reserved</div>
            </div>
            <div className="kpi-card">
              <div className="kpi-label">Daily Outflow Utilization</div>
              <div className="kpi-value">{outflowPct}%</div>
              <div className="progress-bar" style={{ margin: "0.35rem 0" }}>
                <div className={`progress-fill ${outflowPct >= 90 ? "danger" : outflowPct >= 75 ? "warn" : ""}`} style={{ width: `${outflowPct}%` }} />
              </div>
              <div className="kpi-meta mono">{formatMoney(report.todayTotalOutflowPesewas)} / {formatMoney(report.dailyOutflowCapPesewas)}</div>
            </div>
            <div className="kpi-card">
              <div className="kpi-label">Rejected Outflows Today</div>
              <div className="kpi-value">{report.todayRejectedOutflowCount}</div>
              <div className="kpi-meta">Blocked by cap or floor rules</div>
            </div>
          </div>
        </>
      )}
      {config && (
        <section className="panel" style={{ marginTop: "1rem" }}>
          <h3>Primary Trust Account Configuration</h3>
          <dl className="ops-dl-grid">
            <div className="ops-dl-item">
              <dt>Account Code</dt>
              <dd className="mono">{config.accountCode}</dd>
            </div>
            <div className="ops-dl-item">
              <dt>Bank / Account</dt>
              <dd className="mono">{config.bankCode && config.bankAccountNumber ? `${config.bankCode} — ${config.bankAccountNumber}` : "Custodian bank not configured"}</dd>
            </div>
            <div className="ops-dl-item">
              <dt>Per-Payout Cap</dt>
              <dd className="mono">{formatMoney(config.perPayoutCapPesewas)}</dd>
            </div>
            <div className="ops-dl-item">
              <dt>Min Reserve Floor</dt>
              <dd className="mono">{formatMoney(config.minReserveFloorPesewas)}</dd>
            </div>
          </dl>
          {canManageFinance && <CustodianConfigEditor config={config} onUpdated={load} />}
        </section>
      )}

      <MtnCompliancePanel />
    </div>
  );
}

function CustodianConfigEditor({ config, onUpdated }: { config: TrustAccountConfig; onUpdated: () => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [dailyCap, setDailyCap] = useState((config.dailyOutflowCapPesewas / 100).toFixed(2));
  const [perPayoutCap, setPerPayoutCap] = useState((config.perPayoutCapPesewas / 100).toFixed(2));
  const [minReserve, setMinReserve] = useState((config.minReserveFloorPesewas / 100).toFixed(2));
  const [bankBalance, setBankBalance] = useState("");
  const [outflowFrozen, setOutflowFrozen] = useState(config.outflowFrozen);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    setDailyCap((config.dailyOutflowCapPesewas / 100).toFixed(2));
    setPerPayoutCap((config.perPayoutCapPesewas / 100).toFixed(2));
    setMinReserve((config.minReserveFloorPesewas / 100).toFixed(2));
    setOutflowFrozen(config.outflowFrozen);
  }, [config]);

  async function connectCustodian() {
    setBusy(true);
    setErr(null);
    setMsg(null);
    try {
      const result = await api<Record<string, unknown>>("/v1/ops/custodian/connect", { method: "POST" });
      setMsg(formatDualControlOutcome(result, "Custodian account connected."));
      await onUpdated();
    } catch (error) {
      setErr(error instanceof Error ? error.message : String(error));
    } finally {
      setBusy(false);
    }
  }

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setMsg(null);
    setErr(null);
    const dCap = parseMoney(dailyCap);
    const pCap = parseMoney(perPayoutCap);
    const mRes = parseMoney(minReserve);
    const bBal = bankBalance.trim() ? parseMoney(bankBalance) : null;
    if (dCap === null || pCap === null || mRes === null || (bankBalance.trim() && bBal === null)) {
      setErr("Enter valid GH₵ amounts for all cap/floor fields.");
      return;
    }
    if (dCap <= 0 || pCap <= 0) {
      setErr("Daily and per-payout caps must be greater than GH₵ 0.00 (use the Outflow Frozen toggle to halt payouts).");
      return;
    }
    if (pCap > dCap) {
      setErr("Per-payout cap cannot exceed the daily outflow cap.");
      return;
    }
    setBusy(true);
    try {
      const res = await api<Record<string, unknown>>("/v1/ops/custodian/config", {
        method: "PUT",
        body: {
          dailyOutflowCapPesewas: dCap,
          perPayoutCapPesewas: pCap,
          minReserveFloorPesewas: mRes,
          lastReconciledBankBalancePesewas: bBal,
          outflowFrozen,
        },
      });
      setMsg(formatDualControlOutcome(res, "Custodian trust configuration updated."));
      setBankBalance("");
      setOpen(false);
      await onUpdated();
    } catch (e2) {
      setErr(e2 instanceof Error ? e2.message : String(e2));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ marginTop: "0.9rem" }}>
      {!config.bankCode && (
        <button type="button" className="secondary small" disabled={busy} onClick={() => void connectCustodian()}>
          Connect custodian via API
        </button>
      )}
      <button type="button" className="secondary small" onClick={() => setOpen(!open)}>
        {open ? "Hide Config Editor" : "Edit Caps / Reconciled Bank Balance"}
      </button>
      {msg && <p className="notice ok" style={{ marginTop: "0.6rem" }}>{msg}</p>}
      {err && <p className="notice error" role="alert" style={{ marginTop: "0.6rem" }}>{err}</p>}
      {open && (
        <form onSubmit={(e) => void save(e)} style={{ marginTop: "0.75rem", paddingTop: "0.75rem", borderTop: "1px solid var(--line)" }}>
          <div className="grid-2">
            <label>Daily Outflow Cap (GH₵)<input className="mono" inputMode="decimal" value={dailyCap} onChange={(e) => setDailyCap(e.target.value)} required /></label>
            <label>Per-Payout Cap (GH₵)<input className="mono" inputMode="decimal" value={perPayoutCap} onChange={(e) => setPerPayoutCap(e.target.value)} required /></label>
            <label>Min Reserve Floor (GH₵)<input className="mono" inputMode="decimal" value={minReserve} onChange={(e) => setMinReserve(e.target.value)} required /></label>
            <label>Reconciled Bank Balance (GH₵, optional)<input className="mono" inputMode="decimal" value={bankBalance} onChange={(e) => setBankBalance(e.target.value)} placeholder="Leave blank to keep" /></label>
          </div>
          <label className="choice" style={{ marginTop: "0.4rem" }}>
            <input type="checkbox" checked={outflowFrozen} onChange={(e) => setOutflowFrozen(e.target.checked)} />
            Freeze Custodian Trust Outflows
          </label>
          <div style={{ marginTop: "0.65rem" }}>
            <button type="submit" className="small" disabled={busy}>{busy ? "Submitting…" : "Save Custodian Config (Dual-Control)"}</button>
          </div>
        </form>
      )}
    </div>
  );
}

interface MtnBalanceResponse {
  available: boolean;
  availableBalancePesewas: number;
  currency: string;
  message: string;
}

interface MtnAccountValidationResponse {
  checked: boolean;
  active: boolean;
  msisdn: string;
  message: string;
}

interface MmlTransactionRow {
  transaction_id: string;
  mtn_financial_id: string | null;
  transaction_date_time: string;
  direction: string;
  amount_pesewas: number;
  currency: string;
  transaction_status: string;
  credit_customer_name: string;
  debit_customer_name: string;
  failure_reason: string | null;
}

function csvSafeCell(v: unknown): string {
  const raw = v === null || v === undefined ? "" : String(v);
  const safe = /^[=+\-@\t\r]/.test(raw) ? `'${raw}` : raw;
  return `"${safe.replace(/"/g, '""')}"`;
}

function MtnCompliancePanel() {
  const [balMsg, setBalMsg] = useState<string | null>(null);
  const [msisdn, setMsisdn] = useState("");
  const [valResult, setValResult] = useState<MtnAccountValidationResponse | null>(null);
  const [exportMsg, setExportMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function checkBalance(direction: "charge" | "payout") {
    setBusy(true);
    setBalMsg(null);
    try {
      const res = await api<MtnBalanceResponse>(`/v1/ops/mtn/balance?direction=${direction}`);
      setBalMsg(
        res.available
          ? `MTN ${direction === "charge" ? "Collection" : "Disbursement"} Float: ${formatMoney(res.availableBalancePesewas, res.currency)}`
          : res.message,
      );
    } catch (e) {
      setBalMsg(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function validateMsisdn(e: React.FormEvent) {
    e.preventDefault();
    if (!msisdn.trim()) return;
    setBusy(true);
    setValResult(null);
    try {
      const res = await api<MtnAccountValidationResponse>(
        `/v1/ops/mtn/validate-account?direction=payout&msisdn=${encodeURIComponent(msisdn.trim())}`,
      );
      setValResult(res);
    } catch (err) {
      setValResult({
        checked: false,
        active: false,
        msisdn: msisdn.trim(),
        message: err instanceof Error ? err.message : String(err),
      });
    } finally {
      setBusy(false);
    }
  }

  async function downloadMmlCsv() {
    setBusy(true);
    setExportMsg(null);
    try {
      const rows = await api<MmlTransactionRow[]>("/v1/ops/compliance/mml-transactions?days=30&limit=5000");
      const header = [
        "Transaction ID",
        "MTN Financial ID",
        "Transaction Date/Time",
        "Direction",
        "Transaction Amount (GHS)",
        "Transaction Status",
        "Credit Customer Name",
        "Debit Customer Name",
        "Failure Reason",
      ];
      const csvLines = [
        header.map(csvSafeCell).join(","),
        ...rows.map((r) =>
          [
            r.transaction_id,
            r.mtn_financial_id ?? "",
            r.transaction_date_time,
            r.direction,
            (Number(r.amount_pesewas) / 100).toFixed(2),
            r.transaction_status,
            r.credit_customer_name,
            r.debit_customer_name,
            r.failure_reason ?? "",
          ]
            .map(csvSafeCell)
            .join(","),
        ),
      ];
      const blob = new Blob([csvLines.join("\n")], { type: "text/csv;charset=utf-8;" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `elepay_mml_aml_transactions_${new Date().toISOString().slice(0, 10)}.csv`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      setExportMsg(`Exported ${rows.length} MTN transactions in MML AML §6C format.`);
    } catch (err) {
      setExportMsg(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel" style={{ marginTop: "1rem" }}>
      <h3>MTN Ghana Rail &amp; MML AML Compliance Tools</h3>
      <p className="small muted">
        Live MTN MoMo OpenAPI float checks, subscriber account validation (UAT Sheets 4–5), and MML AML §6C transaction extract.
      </p>
      <div className="flex-row" style={{ marginBottom: "0.75rem" }}>
        <button type="button" className="secondary small" disabled={busy} onClick={() => void checkBalance("charge")}>
          Check MTN Collection Float
        </button>
        <button type="button" className="secondary small" disabled={busy} onClick={() => void checkBalance("payout")}>
          Check MTN Disbursement Float
        </button>
        <button type="button" className="small" disabled={busy} onClick={() => void downloadMmlCsv()}>
          Export MML AML Transactions (CSV)
        </button>
      </div>
      {balMsg && <p className="notice" style={{ marginTop: 0 }}>{balMsg}</p>}
      {exportMsg && <p className="notice ok" style={{ marginTop: 0 }}>{exportMsg}</p>}

      <form onSubmit={(e) => void validateMsisdn(e)} className="flex-row" style={{ marginTop: "0.75rem" }}>
        <input
          className="mono"
          value={msisdn}
          onChange={(e) => setMsisdn(e.target.value)}
          placeholder="Validate MTN MSISDN (e.g. 0241234567)"
          style={{ maxWidth: "280px", margin: 0 }}
        />
        <button type="submit" className="secondary small" style={{ margin: 0 }} disabled={busy}>
          Validate Account
        </button>
      </form>
      {valResult && (
        <p className={`notice ${valResult.checked && valResult.active ? "ok" : "warn"}`} style={{ marginTop: "0.65rem" }}>
          <strong className="mono">{valResult.msisdn}</strong> — {valResult.message}
        </p>
      )}
    </section>
  );
}

// -------------------------------------------------------------------------------------------------
// Merchant Controls & Overrides
// -------------------------------------------------------------------------------------------------
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
      const res = await api<{ revoked?: boolean; status?: string }>(`/v1/ops/kill-switch/api-keys/${keyId}`, { body: { active: false, reason } });
      setMsg(res.status === "ALREADY_REVOKED" || res.revoked === false
        ? `API key ${keyId} was already revoked.`
        : `API key ${keyId} revoked immediately.`);
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
      <form
        className="panel"
        style={{ padding: "0.85rem 1rem", marginBottom: "0.85rem" }}
        onSubmit={(e) => {
          e.preventDefault();
          void search(query);
        }}
      >
        <div className="flex-row">
          <div className="pos-search-wrap" style={{ margin: 0, flex: "1 1 260px" }}>
            <IconSearch size={15} />
            <input
              aria-label="Search merchants by name, ID, email, or phone"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search merchant by name, phone, code, or UUID"
            />
          </div>
          <button type="submit" style={{ margin: 0 }} disabled={searching}>{searching ? "Searching…" : "Search"}</button>
        </div>
      </form>
      {error && <p className="notice error" role="alert">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}

      <div className="table-wrap">
        <table className="grid">
          <thead>
            <tr><th>Merchant</th><th>Phone</th><th>Status</th><th>Payouts</th><th className="num">Fee</th><th /></tr>
          </thead>
          <tbody>
            {results.map((m) => (
              <tr key={m.id}>
                <td><strong>{m.trading_name}</strong><div className="small muted">{m.legal_name}</div></td>
                <td className="small mono">{m.contact_phone ?? "—"}</td>
                <td><span className={`tag ${m.status === "active" ? "active" : "pending"}`}>{m.status}</span></td>
                <td><span className={`tag ${m.frozen ? "failed" : "active"}`}>{m.frozen ? "FROZEN" : "ACTIVE"}</span></td>
                <td className="num mono small">{(m.fee_bps / 100).toFixed(2)}%</td>
                <td className="num"><button className="small secondary" disabled={loadingDetail} onClick={() => void openMerchant(m.id)}>Inspect</button></td>
              </tr>
            ))}
            {!searching && results.length === 0 && <tr><td colSpan={6} className="muted" style={{ textAlign: "center", padding: "1.5rem" }}>No merchants matched.</td></tr>}
          </tbody>
        </table>
      </div>

      {loadingDetail && <p className="muted">Loading merchant controls…</p>}

      {selected && (
        <section className="panel" style={{ marginTop: "1.25rem" }}>
          <div className="flex-between" style={{ paddingBottom: "0.75rem", borderBottom: "1px solid var(--line)" }}>
            <div>
              <h3 style={{ margin: 0 }}>{selected.merchant.trading_name}</h3>
              <p className="small muted" style={{ margin: "0.15rem 0 0" }}>
                {selected.merchant.legal_name} · Status: <strong>{selected.merchant.status}</strong>
              </p>
            </div>
            <div className="flex-row">
              {canFreeze && (
                <button
                  className={selected.merchant.frozen ? "secondary" : "btn-danger"}
                  disabled={busy}
                  onClick={() => void toggleFreeze(selected.merchant.id, !selected.merchant.frozen)}
                >
                  <IconPower size={15} />
                  {selected.merchant.frozen ? "Unfreeze Merchant Payouts" : "Freeze Merchant Payouts"}
                </button>
              )}
              <button type="button" className="ghost small" aria-label="Close inspection" onClick={() => setSelected(null)}>
                <IconX size={16} />
              </button>
            </div>
          </div>

          <div className="kpi-grid" style={{ marginTop: "1rem" }}>
            {selected.balances.map((b) => (
              <div className="kpi-card" key={`${b.currency}-${b.bucket}`}>
                <div className="kpi-label">{b.currency} {b.bucket}</div>
                <div className="kpi-value">{formatMoney(b.balance, b.currency)}</div>
              </div>
            ))}
            {selected.balances.length === 0 && <p className="small muted">No ledger accounts opened yet.</p>}
          </div>

          <h4 style={{ marginTop: "1.25rem" }}>API Keys</h4>
          <div className="table-wrap">
            <table className="grid">
              <thead><tr><th>Prefix</th><th>Tier</th><th>Label</th><th>Status</th><th /></tr></thead>
              <tbody>
                {selected.apiKeys.map((k) => (
                  <tr key={k.id}>
                    <td className="mono small">{k.key_prefix}…</td>
                    <td className="small">{k.key_type}</td>
                    <td className="small">{k.label ?? "—"}</td>
                    <td><span className={`tag ${k.status === "active" ? "active" : "failed"}`}>{k.status}</span></td>
                    <td className="num">
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
          </div>

          <h4 style={{ marginTop: "1.25rem" }}>POS Terminals</h4>
          <div className="table-wrap" style={{ marginBottom: 0 }}>
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
          </div>
        </section>
      )}

      {canFreeze && <AuditTrailPanel />}
    </div>
  );
}

interface AuditEntry {
  actor: string;
  action: string;
  subject: string;
  details: string;
  created_at: string;
}

function AuditTrailPanel() {
  const [subject, setSubject] = useState("");
  const [entries, setEntries] = useState<AuditEntry[]>([]);
  const [searched, setSearched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function lookup(e: React.FormEvent) {
    e.preventDefault();
    if (!subject.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const res = await api<AuditEntry[]>(`/v1/ops/audit?subject=${encodeURIComponent(subject.trim())}`);
      setEntries(res);
      setSearched(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel" style={{ marginTop: "1.25rem" }}>
      <h3>Immutable Audit Trail Lookup</h3>
      <p className="small muted">Inspect append-only compliance &amp; admin audit events by merchant, customer, or reference subject.</p>
      <form onSubmit={(e) => void lookup(e)} className="flex-row" style={{ marginBottom: "0.75rem" }}>
        <input
          className="mono"
          value={subject}
          onChange={(e) => setSubject(e.target.value)}
          placeholder="Subject (e.g. merchant:UUID or customer:UUID)"
          style={{ flex: "1 1 260px", margin: 0 }}
        />
        <button type="submit" className="secondary small" style={{ margin: 0 }} disabled={busy}>
          {busy ? "Querying…" : "Search Audit Log"}
        </button>
      </form>
      {error && <p className="notice error" role="alert">{error}</p>}
      {searched && (
        <div className="table-wrap" style={{ margin: 0 }}>
          <table className="grid">
            <thead><tr><th>When</th><th>Actor</th><th>Action</th><th>Subject</th><th>Details</th></tr></thead>
            <tbody>
              {entries.map((row, idx) => (
                <tr key={idx}>
                  <td className="small muted">{new Date(row.created_at).toLocaleString()}</td>
                  <td className="small"><strong>{row.actor}</strong></td>
                  <td className="mono small">{row.action}</td>
                  <td className="mono small">{row.subject}</td>
                  <td className="mono small">{row.details}</td>
                </tr>
              ))}
              {entries.length === 0 && (
                <tr><td colSpan={5} className="muted" style={{ textAlign: "center", padding: "1.25rem" }}>No audit events found for this subject.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
