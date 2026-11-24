import { useEffect, useState } from "react";
import { api } from "./api";
import { formatMoney } from "./money";

interface Item { id: string; kind: string; severity: string; subject: string; details: string; createdAt: string }
interface MerchantView { id: string; legalName: string; tradingName: string; kind: string; status: string }
interface Integrity { accountsWithDrift: number; netByCurrency: Record<string, number>; unbalancedTransactions: number }

interface OpsPermissions {
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

const ALL_TABS = [
  "Overview", "Merchant Controls", "Maker-Checker", "Kill Switches", "Custodian Trust",
  "Onboarding", "Disputes", "Reconciliation", "Exceptions", "System Health"
] as const;
type OpsTab = (typeof ALL_TABS)[number];

function visibleTabsFor(perms: OpsPermissions | null): OpsTab[] {
  if (!perms) return [...ALL_TABS];
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
  const [globalFrozen, setGlobalFrozen] = useState<boolean>(false);
  const [bannerMsg, setBannerMsg] = useState<string | null>(null);

  useEffect(() => {
    api<OpsMe>("/v1/ops/me").then(setMe).catch(() => {});
    api<KillSwitchStatus>("/v1/ops/kill-switch/status").then((s) => setGlobalFrozen(s.globalFrozen)).catch(() => {});
  }, []);

  const tabs = visibleTabsFor(me?.permissions ?? null);

  async function quickEmergencyFreeze() {
    const reason = prompt("Reason for emergency global payout freeze:");
    if (!reason) return;
    try {
      const res = await api<Record<string, unknown>>("/v1/ops/kill-switch/global", {
        body: { frozen: true, reason },
      });
      setGlobalFrozen(true);
      setBannerMsg(formatDualControlOutcome(res, "Global payout freeze activated."));
    } catch (e) {
      setBannerMsg(e instanceof Error ? e.message : String(e));
    }
  }

  return (
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
        {(me?.permissions.canFreezeRisk ?? true) && !globalFrozen && (
          <button style={{ background: "#b91c1c", color: "#fff" }} onClick={() => void quickEmergencyFreeze()}>
            Emergency Freeze All Payouts
          </button>
        )}
      </div>

      {globalFrozen && (
        <p className="notice warn" style={{ marginTop: "0.75rem" }}>
          <strong>GLOBAL PAYOUT FREEZE ACTIVE</strong> — All merchant payouts are paused. Unfreezing requires Maker-Checker dual approval.
        </p>
      )}
      {bannerMsg && <p className="notice ok">{bannerMsg}</p>}

      <nav className="tabs" style={{ marginTop: "1rem" }}>
        {tabs.map((t) => <button key={t} className={t === tab ? "active" : ""} onClick={() => setTab(t)}>{t}</button>)}
      </nav>

      {tab === "Overview" && <Overview />}
      {tab === "Merchant Controls" && <MerchantControls perms={me?.permissions ?? null} />}
      {tab === "Maker-Checker" && <MakerChecker canApprove={me?.permissions.canApproveMakerChecker ?? true} />}
      {tab === "Kill Switches" && <KillSwitches />}
      {tab === "Custodian Trust" && <CustodianTrust />}
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
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = () => api<Record<string, string | number>[]>("/v1/ops/disputes").then(setRows);
  useEffect(() => { void load(); }, []);
  async function resolve(id: string, outcome: string) {
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
    }
  }
  return (
    <>
      {error && <p className="error">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}
      <table className="grid">
        <thead><tr><th>Checkout</th><th>Merchant</th><th className="num">Amount</th><th>Reason</th><th /></tr></thead>
        <tbody>{rows.map((d) => (
          <tr key={String(d.id)}><td>{d.checkout} #{d.line}</td><td>{d.merchant}</td><td className="num">{formatMoney(Number(d.amount))}</td>
            <td>{d.reason}</td><td><button className="small" onClick={() => void resolve(String(d.id), "merchant_won")}>Merchant won</button>
              <button className="small secondary" onClick={() => void resolve(String(d.id), "customer_won")}>Customer won</button></td></tr>
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

const ACTION_LABELS: Record<string, string> = {
  "kill_switch.global_unfreeze": "Lift Global Payout Freeze",
  "kill_switch.merchant_unfreeze": "Unfreeze Merchant Payouts",
  "kill_switch.module_enable": "Enable Platform Module",
  "suspense.write_off": "Approve Suspense Write-Off",
};

function MakerChecker({ canApprove }: { canApprove: boolean }) {
  const [proposals, setProposals] = useState<MakerCheckerProposal[]>([]);
  const [statusFilter, setStatusFilter] = useState<string>("PENDING");
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    const q = statusFilter === "ALL" ? "" : `?status=${encodeURIComponent(statusFilter)}`;
    api<MakerCheckerProposal[]>(`/v1/ops/maker-checker/proposals${q}`)
      .then((res) => { setProposals(res); setError(null); })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  };

  useEffect(() => { load(); }, [statusFilter]);

  async function review(id: string, action: "approve" | "reject") {
    const note = prompt(action === "approve" ? "Approval note (optional)" : "Rejection reason") ?? "";
    setMsg(null);
    setError(null);
    try {
      const res = await api<MakerCheckerProposal>(`/v1/ops/maker-checker/proposals/${id}/${action}`, {
        body: { note },
      });
      setMsg(`Proposal ${res.id} ${res.status.toLowerCase()}.`);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
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
                      <button className="small" onClick={() => void review(p.id, "approve")}>Approve</button>
                      <button className="small secondary" onClick={() => void review(p.id, "reject")}>Reject</button>
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
          {proposals.length === 0 && (
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
  const [merchantId, setMerchantId] = useState("");
  const [reason, setReason] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    api<KillSwitchStatus>("/v1/ops/kill-switch/status")
      .then((res) => { setStatus(res); setError(null); })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  };

  useEffect(() => { load(); }, []);

  async function toggleGlobal(frozen: boolean) {
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>("/v1/ops/kill-switch/global", {
        body: { frozen, reason: reason || undefined },
      });
      setMsg(formatDualControlOutcome(res, frozen ? "Global payout freeze activated." : "Global payout freeze lifted."));
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  async function toggleMerchant(id: string, frozen: boolean) {
    if (!id.trim()) { setError("Enter a merchant UUID."); return; }
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>(`/v1/ops/kill-switch/merchants/${id.trim()}`, {
        body: { frozen, reason: reason || undefined },
      });
      setMsg(formatDualControlOutcome(res, `Merchant ${id.trim()} ${frozen ? "frozen" : "unfrozen"}.`));
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  async function toggleModule(moduleKey: string, enabled: boolean) {
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>(`/v1/ops/kill-switch/modules/${moduleKey}`, {
        body: { enabled, reason: reason || undefined },
      });
      setMsg(formatDualControlOutcome(res, `Module ${moduleKey} ${enabled ? "enabled" : "disabled"}.`));
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div>
      <h3>Stage 1 Kill Switches</h3>
      {error && <p className="error">{error}</p>}
      {msg && <p className="notice ok">{msg}</p>}

      <label>Reason / Audit Note
        <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Incident or maintenance reason" />
      </label>

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
            <button onClick={() => void toggleGlobal(!status.globalFrozen)}>
              {status.globalFrozen ? "Unfreeze Global Payouts" : "Freeze Global Payouts"}
            </button>
          </section>

          <section className="panel" style={{ marginTop: "1rem" }}>
            <h4>Merchant Payout Kill Switch</h4>
            <div className="flex-row">
              <input value={merchantId} onChange={(e) => setMerchantId(e.target.value)} placeholder="Merchant UUID" />
              <button onClick={() => void toggleMerchant(merchantId, true)}>Freeze Merchant</button>
              <button className="secondary" onClick={() => void toggleMerchant(merchantId, false)}>Unfreeze Merchant</button>
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
                {Object.entries(status.modules).map(([mod, enabled]) => (
                  <tr key={mod}>
                    <td className="mono small">{mod}</td>
                    <td><span className={`tag ${enabled ? "active" : "failed"}`}>{enabled ? "ENABLED" : "DISABLED"}</span></td>
                    <td>
                      <button className="small secondary" onClick={() => void toggleModule(mod, !enabled)}>
                        {enabled ? "Disable" : "Enable"}
                      </button>
                    </td>
                  </tr>
                ))}
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
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      api<CustodianSafeguardReport>("/v1/ops/custodian/safeguard-report"),
      api<TrustAccountConfig>("/v1/ops/custodian/config"),
    ])
      .then(([r, c]) => { setReport(r); setConfig(c); setError(null); })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  return (
    <div>
      <h3>Custodian Trust Safeguarding</h3>
      {error && <p className="error">{error}</p>}
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
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<MerchantSearchRow[]>([]);
  const [selected, setSelected] = useState<MerchantControlsData | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const search = (q: string) => {
    api<MerchantSearchRow[]>(`/v1/ops/merchants/search?q=${encodeURIComponent(q)}`)
      .then((res) => { setResults(res); setError(null); })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  };

  useEffect(() => { search(""); }, []);

  const openMerchant = (id: string) => {
    api<MerchantControlsData>(`/v1/ops/merchants/${id}/controls`)
      .then((res) => { setSelected(res); setError(null); })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  };

  async function toggleFreeze(id: string, frozen: boolean) {
    const reason = prompt(frozen ? "Reason for freezing merchant payouts:" : "Reason for unfreezing merchant payouts:");
    if (!reason) return;
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>(`/v1/ops/kill-switch/merchants/${id}`, {
        body: { frozen, reason },
      });
      setMsg(formatDualControlOutcome(res, frozen ? "Merchant payouts frozen." : "Merchant payouts unfrozen."));
      openMerchant(id);
      search(query);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  async function revokeKey(keyId: string, merchantId: string) {
    const reason = prompt("Reason for revoking this API key:");
    if (!reason) return;
    setMsg(null);
    setError(null);
    try {
      await api(`/v1/ops/kill-switch/api-keys/${keyId}`, { body: { active: false, reason } });
      setMsg(`API key ${keyId} revoked immediately.`);
      openMerchant(merchantId);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div>
      <h3>Merchant Controls &amp; Overrides</h3>
      <div className="flex-row" style={{ marginBottom: "1rem" }}>
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search merchant by name, phone, code, or UUID"
        />
        <button onClick={() => search(query)}>Search</button>
      </div>
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
              <td><span className={`tag ${m.status === "approved" ? "active" : "pending"}`}>{m.status}</span></td>
              <td><span className={`tag ${m.frozen ? "failed" : "active"}`}>{m.frozen ? "FROZEN" : "ACTIVE"}</span></td>
              <td className="small">{(m.fee_bps / 100).toFixed(2)}%</td>
              <td><button className="small" onClick={() => openMerchant(m.id)}>Inspect</button></td>
            </tr>
          ))}
          {results.length === 0 && <tr><td colSpan={6} className="muted">No merchants matched.</td></tr>}
        </tbody>
      </table>

      {selected && (
        <section className="panel" style={{ marginTop: "1.25rem" }}>
          <div className="flex-between">
            <h4>{selected.merchant.trading_name} ({selected.merchant.status})</h4>
            {(perms?.canFreezeRisk ?? true) && (
              <button
                className={selected.merchant.frozen ? "secondary" : ""}
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
                    {k.status === "active" && (perms?.canFreezeRisk ?? true) && (
                      <button className="small secondary" onClick={() => void revokeKey(k.id, selected.merchant.id)}>
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
