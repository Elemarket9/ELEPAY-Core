import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
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
  "Onboarding", "Disputes", "Reconciliation", "Exceptions", "Risk & Audit", "System Health"
] as const;
export type OpsTab = (typeof ALL_TABS)[number];

export function visibleTabsFor(perms: OpsPermissions | null): OpsTab[] {
  if (!perms) return [];
  return ALL_TABS.filter((t) => {
    if (t === "Overview" || t === "Merchant Controls" || t === "Exceptions" || t === "Disputes" || t === "System Health") return true;
    if (t === "Maker-Checker") return perms.canApproveMakerChecker || perms.canFreezeRisk || perms.canManageFinance;
    if (t === "Kill Switches" || t === "Risk & Audit") return perms.canFreezeRisk;
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
  const [globalFrozen, setGlobalFrozen] = useState<boolean | null>(null);
  const [killSwitchError, setKillSwitchError] = useState<string | null>(null);
  const [bannerMsg, setBannerMsg] = useState<string | null>(null);
  const [promptReq, setPromptReq] = useState<PromptRequest | null>(null);
  const [promptInput, setPromptInput] = useState("");
  const promptLock = useRef<PromptRequest | null>(null);
  const freezeLock = useRef(false);
  const askPrompt = useCallback((title: string, defaultValue = ""): Promise<string | null> => {
    if (promptLock.current) return Promise.resolve(null);
    return new Promise(resolve => { const request = {title,defaultValue,resolve}; promptLock.current=request;setPromptInput(defaultValue);setPromptReq(request); });
  }, []);
  function closePrompt(value:string|null){const r=promptLock.current;promptLock.current=null;setPromptReq(null);r?.resolve(value);}
  useEffect(()=>()=>{promptLock.current?.resolve(null);promptLock.current=null;},[]);
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [freezingGlobal, setFreezingGlobal] = useState<boolean>(false);

  const accessSequence=useRef(0);
  const freezeSequence=useRef(0);
  const refreshFreeze=useCallback(async()=>{
    const seq=++freezeSequence.current;setGlobalFrozen(null);setKillSwitchError(null);
    try{const s=await api<KillSwitchStatus>("/v1/ops/kill-switch/status",{signal:AbortSignal.timeout(20000)});if(typeof s?.globalFrozen!=="boolean")throw Error("Unverified freeze state");if(seq===freezeSequence.current)setGlobalFrozen(s.globalFrozen);}
    catch(e){if(seq===freezeSequence.current)setKillSwitchError(e instanceof Error?e.message:"Freeze state unavailable");}
  },[]);
  const refreshAccess=useCallback(async()=>{
    const seq=++accessSequence.current;setMeLoading(true);setMeError(null);setMe(null);
    try{const m=await api<OpsMe>("/v1/ops/me",{signal:AbortSignal.timeout(20000)});if(!m?.subject||!m.permissions||!Array.isArray(m.roles))throw Error("Operator permissions unavailable");if(seq===accessSequence.current){setMe(m);void refreshFreeze();}}
    catch(e){if(seq===accessSequence.current){setMeError(e instanceof Error?e.message:"Operator access unavailable");setGlobalFrozen(null);}}
    finally{if(seq===accessSequence.current)setMeLoading(false);}
  },[refreshFreeze]);
  useEffect(()=>{void refreshAccess();return()=>{accessSequence.current++;freezeSequence.current++;};},[refreshAccess]);

  const tabs = visibleTabsFor(me?.permissions ?? null);

  async function quickEmergencyFreeze() {
    if(freezeLock.current||!me?.permissions.canFreezeRisk)return;
    const rawReason = await askPrompt("Reason for emergency global payout freeze:");
    if (rawReason === null) return;
    const reason = rawReason.trim();
    if (!reason) {
      setBannerError("A non-empty reason is required for emergency global payout freeze.");
      return;
    }
    if(freezeLock.current)return;freezeLock.current=true;
    setFreezingGlobal(true);
    setBannerMsg(null);
    setBannerError(null);
    try {
      const res = await api<Record<string, unknown>>("/v1/ops/kill-switch/global", {
        body: { frozen: true, reason },
      });
      setBannerMsg(formatDualControlOutcome(res, res.globalFrozen===true?"Global payout freeze request confirmed.":"Freeze request submitted; verify current state."));
      await refreshFreeze();
    } catch (e) {
      setBannerError("Freeze outcome not confirmed. "+(e instanceof Error ? e.message : String(e)));await refreshFreeze();
    } finally {
      freezeLock.current=false;setFreezingGlobal(false);
    }
  }

  return (
    <OpsPromptContext.Provider value={askPrompt}>
    <main className="ops-workspace">
      <div className="flex-between" style={{ gap: "1rem", flexWrap: "wrap" }}>
        <div>
          <p className="eyebrow">ELEPAY CONTROL ROOM</p><h1 style={{ marginBottom: "0.25rem" }}>Operations console</h1>
          {me && (
            <p className="small muted" style={{ margin: 0 }}>
              Signed in as <strong>{me.subject}</strong> · Role: <span className="tag active">{me.primaryRole}</span>
            </p>
          )}
        </div>
        <button className="secondary" disabled={meLoading||freezingGlobal||!!promptReq} onClick={()=>void refreshAccess()}>Refresh access</button>
        {(me?.permissions.canFreezeRisk ?? false) && globalFrozen !== true && (
          <button
            style={{ background: "#b91c1c", color: "#fff" }}
            disabled={freezingGlobal}
            onClick={() => void quickEmergencyFreeze()}
          >
            {freezingGlobal ? "Freezing…" : "Emergency Freeze All Payouts"}
          </button>
        )}
      </div>

      {killSwitchError && <p className="error" role="alert">Kill switch status error: {killSwitchError}</p>}
      {me && <div className="panel ops-freeze-state"><strong>Global payout controls</strong><span className={"tag "+(globalFrozen===true?"failed":globalFrozen===false?"active":"pending")}>{globalFrozen===true?"FROZEN":globalFrozen===false?"NOT FROZEN":"UNKNOWN"}</span><button className="secondary small" disabled={freezingGlobal} onClick={()=>void refreshFreeze()}>Verify state</button></div>}
      {globalFrozen === true && (
        <p className="notice warn" style={{ marginTop: "0.75rem" }}>
          <strong>GLOBAL PAYOUT FREEZE ACTIVE</strong> — All merchant payouts are paused. Unfreezing requires Maker-Checker dual approval.
        </p>
      )}
      {bannerError && <p className="error" role="alert">{bannerError}</p>}
      {bannerMsg && <p className="notice ok">{bannerMsg}</p>}

      {meLoading && <p className="muted">Loading operator permissions…</p>}
      {meError && <p className="error" role="alert">Failed to load operator permissions: {meError}</p>}

      {!meLoading && !meError && me && (
        <>
          <div className="merchant-shell"><aside className="panel merchant-sidebar"><nav aria-label="Operations sections">{tabs.map(t=><button key={t} aria-pressed={tab===t} className={t===tab?"active":""} onClick={()=>{if(!promptLock.current)setTab(t);}}>{t}</button>)}</nav></aside><div className="merchant-content ops-content" key={me.subject+":"+tab}>

          {tabs.includes(tab) && (
            <>
              {tab === "Overview" && <Overview perms={me.permissions} />}
              {tab === "Merchant Controls" && <MerchantControls perms={me.permissions} />}
              {tab === "Maker-Checker" && <MakerChecker canApprove={me.permissions.canApproveMakerChecker ?? false} subject={me.subject} onChanged={refreshFreeze} />}
              {tab === "Kill Switches" && <KillSwitches canRestore={me.permissions.canApproveMakerChecker} onChanged={refreshFreeze} />}
              {tab === "Custodian Trust" && <CustodianTrust />}
              {tab === "System Health" && <SystemHealth />}
              {tab === "Exceptions" && <Exceptions perms={me.permissions} />}
              {tab === "Risk & Audit" && <RiskAudit />}
              {tab === "Onboarding" && <Onboarding />}
              {tab === "Disputes" && <Disputes perms={me.permissions} />}
              {tab === "Reconciliation" && <><Reconciliation /><SuspenseMonitor /></>}
            </>
          )}</div></div>
        </>
      )}
      {promptReq && (
        <div role="dialog" aria-labelledby="ops-prompt-title" aria-modal="true" style={{ position: "fixed", inset: 0, background: "rgba(15,23,42,0.55)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 1000 }}>
          <form className="panel" style={{ width: "min(440px, 92vw)", background: "#fff", padding: "1.25rem", borderRadius: "8px" }}
            onSubmit={(e) => { e.preventDefault(); closePrompt(promptInput); }} onKeyDown={e=>{if(e.key==="Escape"){e.preventDefault();closePrompt(null);}}}>
            <h4 id="ops-prompt-title" style={{ marginTop: 0 }}>{promptReq.title}</h4>
            <input aria-labelledby="ops-prompt-title" maxLength={1000} autoFocus value={promptInput} onChange={(e) => setPromptInput(e.target.value)} style={{ width: "100%", marginBottom: "1rem" }} />
            <div className="flex-row" style={{ justifyContent: "flex-end", gap: "0.5rem" }}>
              <button type="button" className="secondary" onClick={() => closePrompt(null)}>Cancel</button>
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

function Overview({perms}:{perms:OpsPermissions}) {
  const [o, setO] = useState<Record<string, number> | null>(null);
  const [integrity, setIntegrity] = useState<Integrity | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState<boolean>(false);

  const load = async () => {
    setLoading(true);setO(null);setIntegrity(null);setError(null);
    try {
      const [overviewData, integrityData] = await Promise.all([
        api<Record<string, number>>("/v1/ops/overview"),
        api<Integrity>("/v1/ops/ledger/integrity"),
      ]);
      if(!overviewData||!integrityData||!Number.isSafeInteger(integrityData.accountsWithDrift)||!Number.isSafeInteger(integrityData.unbalancedTransactions)||!integrityData.netByCurrency)throw Error("Invalid overview response");
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

  const runLock=useRef(false);
  const askPrompt=useOpsPrompt();
  const runAction = async (path: string, body: unknown, okMessage: string) => {
    if(runLock.current||!perms.canManageFinance)return;
    const answer=await askPrompt("Type RUN to confirm: "+okMessage);
    if(answer!=="RUN"||runLock.current)return;runLock.current=true;
    setBusy(true);
    setMsg(null);
    setError(null);
    try {
      await api(path, body === undefined ? { method: "POST" } : { body });
      setMsg(okMessage+" — request returned. Inspect current records for the actual outcome.");
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      runLock.current=false;setBusy(false);
    }
  };

  return (
    <>
      <div className="page-title"><div><h2>Financial and operational overview</h2><p className="muted small">Server-reported activity, not a live infrastructure availability guarantee.</p></div><button className="secondary" disabled={loading||busy} onClick={()=>void load()}>Refresh overview</button></div>
      {error && <p className="error" role="alert">{error}</p>}
      {msg && <p className="notice" role="status">{msg}</p>}
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
        <p className={ledgerIntegrityClear(integrity) ? "notice ok" : "notice warn"}>
          Ledger integrity: {integrity.accountsWithDrift} accounts with drift, {integrity.unbalancedTransactions} unbalanced transactions,
          net by currency {Object.entries(integrity.netByCurrency).map(([c, v]) => `${c} ${v}`).join(", ")}
        </p>
      )}
      {perms.canManageFinance&&<><button disabled={busy||loading} onClick={() => void runAction("/v1/ops/rails/recover", undefined, "Rail recovery run")}>
        {busy ? "Running…" : "Recover pending rail results"}
      </button>
      <button disabled={busy||loading} onClick={() => void runAction("/v1/ops/settlements/run", {}, "Run due settlements")}>
        {busy ? "Running…" : "Run due settlements"}
      </button></>}
    </>
  );
}

function Exceptions({perms}:{perms:OpsPermissions}) {
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
    if(busyId||!items.some(i=>i.id===id&&canResolveException(i,perms)))return;
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
      const response=await api<{status:string}>(`/v1/ops/queue/${id}/resolve`, { body: { resolution } });
      if(response?.status!=="resolved")throw Error("Resolution not confirmed");
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
      {error && <p className="error" role="alert">{error}</p>}
      {msg && <p className="notice" role="status">{msg}</p>}
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
                <button className="small" disabled={!!busyId||!canResolveException(i,perms)} onClick={() => void resolveItem(i.id)}>
                  {busyId === i.id ? "Resolving…" : "Resolve"}
                </button>
              </td>
            </tr>
          ))}
          {!loading && items.length === 0 && (
            <tr><td colSpan={6} className="muted">{error?"Exception records unavailable.":"No open exceptions."}</td></tr>
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
    if(busyId)return;
    if (decision === "approve") {
      const rawFee = await askPrompt("Agreed fee in basis points (100 = 1%)");
      if (rawFee === null) return;
      const trimmed = rawFee.trim();
      if (!trimmed) {
        setError("Fee in basis points is required.");
        return;
      }
      feeBps = Number(trimmed);
      if (!Number.isSafeInteger(feeBps) || feeBps < 0 || feeBps > 10000) {
        setError("Fee must be an integer between 0 and 10,000 basis points.");
        return;
      }
    }
    const note=await askPrompt("Evidence-based merchant review note for "+m.tradingName);
    if(!note?.trim()||busyId)return;
    setBusyId(m.id);
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>(`/v1/ops/merchants/${m.id}/review`, {
        body: { decision, feeBps, note: note.trim() },
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
      {error && <p className="error" role="alert">{error}</p>}
      {msg && <p className="notice" role="status">{msg}</p>}
      {loading && merchants.length === 0 && <p className="muted">Loading merchants…</p>}
      <table className="grid">
        <thead><tr><th>Trading name</th><th>Legal name</th><th>Type</th><th>Status</th><th /></tr></thead>
        <tbody>
          {merchants.map((m) => {
            const canReview = m.status === "pending_review";
            const busy = !!busyId;
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
            <tr><td colSpan={5} className="muted">{error?"Merchant records unavailable.":"No merchants found for onboarding review."}</td></tr>
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

function Disputes({perms}:{perms:OpsPermissions}) {
  const askPrompt=useOpsPrompt();
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
    if(busyId||!perms.canManageSupport)return;
    const note=await askPrompt("Evidence and reason for "+outcome+" on dispute "+id);
    if(!note?.trim()||busyId)return;
    setBusyId(id);
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>(`/v1/ops/disputes/${id}/resolve`, {
        body: { outcome, note: note.trim() },
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
      {error && <p className="error" role="alert">{error}</p>}
      {msg && <p className="notice" role="status">{msg}</p>}
      {loading && rows.length === 0 && <p className="muted">Loading disputes…</p>}
      <table className="grid">
        <thead><tr><th>Checkout</th><th>Merchant</th><th className="num">Amount</th><th>Reason</th><th /></tr></thead>
        <tbody>
          {rows.map((d) => {
            const id = String(d.id);
            const busy = !!busyId || !perms.canManageSupport;
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
            <tr><td colSpan={5} className="muted">{error?"Dispute records unavailable.":"No open disputes."}</td></tr>
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
      {error && <p className="error" role="alert">{error}</p>}
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
  payload: Record<string,unknown>;
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

function MakerChecker({ canApprove,subject,onChanged }: { canApprove: boolean;subject:string;onChanged:()=>Promise<void> }) {
  const [selected,setSelected]=useState<MakerCheckerProposal|null>(null);
  const sequence=useRef(0);
  const reviewLock=useRef(false);
  const askPrompt = useOpsPrompt();
  const [proposals, setProposals] = useState<MakerCheckerProposal[]>([]);
  const [statusFilter, setStatusFilter] = useState<string>("PENDING");
  const [loading, setLoading] = useState<boolean>(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    const n=++sequence.current;setLoading(true);setProposals([]);setSelected(null);setError(null);
    const q = statusFilter === "ALL" ? "" : `?status=${encodeURIComponent(statusFilter)}`;
    try {
      const res = await api<MakerCheckerProposal[]>(`/v1/ops/maker-checker/proposals${q}`);
      if(n===sequence.current){setProposals(res);setError(null);}
    } catch (e) {
      if(n===sequence.current)setError(e instanceof Error ? e.message : String(e));
    } finally {
      if(n===sequence.current)setLoading(false);
    }
  };

  useEffect(() => { void load();return()=>{sequence.current++;}; }, [statusFilter]);

  async function review(id: string, action: "approve" | "reject") {
    if(reviewLock.current||!selected||selected.id!==id||!canReviewProposal(selected,subject,canApprove))return;
    const rawNote = await askPrompt(action === "approve" ? "Independent review evidence / approval note" : "Rejection reason");
    if (rawNote === null) return;
    const note = rawNote.trim();
    if (!note) {
      setError("A non-empty review note is required.");
      return;
    }
    if(reviewLock.current)return;reviewLock.current=true;
    setBusyId(id);
    setMsg(null);
    setError(null);
    try {
      const latest=await api<MakerCheckerProposal>(`/v1/ops/maker-checker/proposals/${id}`);
      if(latest.id!==id||!canReviewProposal(latest,subject,canApprove)||latest.actionType!==selected.actionType||latest.resourceId!==selected.resourceId||latest.resourceType!==selected.resourceType||latest.merchantId!==selected.merchantId||JSON.stringify(latest.payload)!==JSON.stringify(selected.payload))throw Error("Proposal changed or expired. Refresh and review again.");
      const res = await api<MakerCheckerProposal>(`/v1/ops/maker-checker/proposals/${id}/${action}`, {
        body: { note },
      });
      if(res.id!==id||res.status!==(action==="approve"?"APPROVED":"REJECTED"))throw Error("Proposal outcome not confirmed");
      setMsg(`Proposal ${res.id} ${res.status.toLowerCase()}.`);
      await load();await onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      reviewLock.current=false;setBusyId(null);
    }
  }

  return (
    <div>
      <div className="flex-between">
        <h3>Maker-Checker Dual Control</h3>
        <label className="choice">
          Status:
          <select disabled={!!busyId} value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} style={{ width: "auto" }}>
            <option value="PENDING">Pending</option>
            <option value="APPROVED">Approved</option>
            <option value="REJECTED">Rejected</option>
            <option value="EXPIRED">Expired</option>
            <option value="ALL">All</option>
          </select>
        </label>
      </div>
      {!canApprove && <p className="notice warn">View-only: Only an Operations Administrator (ops_admin) can approve dual-control proposals.</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {msg && <p className="notice" role="status">{msg}</p>}
      {loading && proposals.length === 0 && <p className="muted">Loading proposals…</p>}
      <button className="secondary" disabled={loading||!!busyId} onClick={()=>void load()}>Refresh proposals</button>
      {selected&&<section className="panel ops-proposal"><h3>Review {selected.actionType}</h3><p>{selected.resourceType}: {selected.resourceId}</p><p>Maker: {selected.proposedBy} · Expires: {opsDate(selected.expiresAt)} GMT</p><p>{selected.reason??"No reason supplied"}</p><h4>Proposed changes</h4><pre className="code-box">{JSON.stringify(selected.payload,null,2)}</pre><button disabled={!!busyId||!canReviewProposal(selected,subject,canApprove)} onClick={()=>void review(selected.id,"approve")}>Approve reviewed proposal</button><button className="secondary" disabled={!!busyId||!canReviewProposal(selected,subject,canApprove)} onClick={()=>void review(selected.id,"reject")}>Reject reviewed proposal</button><button className="secondary" disabled={!!busyId} onClick={()=>setSelected(null)}>Close</button></section>}
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
                <button className="small secondary" disabled={!!busyId} onClick={()=>setSelected(p)}>Inspect</button>
                {p.status==="PENDING"&&!canReviewProposal(p,subject,canApprove)&&<span className="small muted"> {p.proposedBy===subject?"Own proposal":!canApprove?"Independent admin required":"Expired or invalid"}</span>}
              </td>
            </tr>
          ))}
          {!loading && proposals.length === 0 && (
            <tr><td colSpan={6} className="muted">{error?"Proposal records unavailable.":"No maker-checker proposals found."}</td></tr>
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

function KillSwitches({canRestore,onChanged}:{canRestore:boolean;onChanged:()=>Promise<void>}) {
  const lock=useRef(false);
  const askPrompt=useOpsPrompt();
  async function permit(action:string,restore:boolean){if(lock.current||!reason.trim()||(restore&&!canRestore)){setError("An audit reason and appropriate permission are required.");return false;}const answer=await askPrompt("Type CONFIRM to "+action);if(answer!=="CONFIRM"||lock.current)return false;lock.current=true;return true;}
  const [status, setStatus] = useState<KillSwitchStatus | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [busy, setBusy] = useState<boolean>(false);
  const [merchantId, setMerchantId] = useState("");
  const [reason, setReason] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);setStatus(null);
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
    if(!await permit(frozen?"freeze global payouts":"request global payout restoration",!frozen))return;
    setBusy(true);
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>("/v1/ops/kill-switch/global", {
        body: { frozen, reason: reason.trim() || undefined },
      });
      setMsg(formatDualControlOutcome(res, res.globalFrozen===frozen?"Global payout state change confirmed.":"Request returned; verify global payout state."));
      await load();await onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      lock.current=false;setBusy(false);
    }
  }

  async function toggleMerchant(id: string, frozen: boolean) {
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id.trim())) { setError("Enter a valid merchant UUID."); return; }
    if(!await permit((frozen?"freeze ":"request restoration for ")+id,!frozen))return;
    setBusy(true);
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>(`/v1/ops/kill-switch/merchants/${id.trim()}`, {
        body: { frozen, reason: reason.trim() || undefined },
      });
      setMsg(formatDualControlOutcome(res, res.payoutsFrozen===frozen?"Merchant payout state change confirmed.":"Request returned; verify merchant payout state."));
      await load();await onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      lock.current=false;setBusy(false);
    }
  }

  async function toggleModule(moduleKey: string, enabled: boolean) {
    if(!await permit((enabled?"enable ":"disable ")+moduleKey,enabled))return;
    setBusy(true);
    setMsg(null);
    setError(null);
    try {
      const res = await api<Record<string, unknown>>(`/v1/ops/kill-switch/modules/${moduleKey}`, {
        body: { enabled, reason: reason.trim() || undefined },
      });
      setMsg(formatDualControlOutcome(res, res.enabled===enabled?"Module state change confirmed.":"Request returned; verify module state."));
      await load();await onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      lock.current=false;setBusy(false);
    }
  }

  const moduleEntries = status ? Object.entries(status.modules) : [];

  return (
    <div>
      <h3>Emergency controls</h3>
      {error && <p className="error" role="alert">{error}</p>}
      {msg && <p className="notice" role="status">{msg}</p>}

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
            <button disabled={busy||!reason.trim()||(status.globalFrozen&&!canRestore)} onClick={() => void toggleGlobal(!status.globalFrozen)}>
              {status.globalFrozen ? "Unfreeze Global Payouts" : "Freeze Global Payouts"}
            </button>
          </section>

          <section className="panel" style={{ marginTop: "1rem" }}>
            <h4>Merchant Payout Kill Switch</h4>
            <div className="flex-row">
              <input value={merchantId} onChange={(e) => setMerchantId(e.target.value)} placeholder="Merchant UUID" />
              <button disabled={busy||!reason.trim()} onClick={() => void toggleMerchant(merchantId, true)}>Freeze Merchant</button>
              <button className="secondary" disabled={busy||!reason.trim()||!canRestore} onClick={() => void toggleMerchant(merchantId, false)}>Unfreeze Merchant</button>
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
                      <button className="small secondary" disabled={busy||!reason.trim()||(!enabled&&!canRestore)} onClick={() => void toggleModule(mod, !enabled)}>
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
      {error && <p className="error" role="alert">{error}</p>}
      {loading && !report && <p className="muted">Loading custodian safeguarding report…</p>}
      {report && (
        <>
          <p className={report.reconciledBankBalancePesewas!==null&&report.safeguardingSatisfied ? "notice ok" : "notice warn"}>
            Trust Safeguarding ({report.trustAccountCode} / {report.currency}):{" "}
            <strong>{report.reconciledBankBalancePesewas===null?"BANK BALANCE UNVERIFIED":report.safeguardingSatisfied?"SATISFIED PER SERVER REPORT":"DEFICIT / REVIEW REQUIRED"}</strong>
            {report.outflowFrozen && " — OUTFLOW FROZEN"}
          </p>
          <p className="muted small">Reconciled bank balance: {report.reconciledBankBalancePesewas===null?"Unavailable":formatMoney(report.reconciledBankBalancePesewas)} · This report is not independent bank confirmation.</p>
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
            <dt>Bank / Account</dt><dd className="mono">{config.bankCode} — ending {config.bankAccountNumber.slice(-4)}</dd>
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

  const searchSeq=useRef(0),detailSeq=useRef(0);
  const search = async (q: string) => {
    const n=++searchSeq.current;setSearching(true);setResults([]);
    try {
      const res = await api<MerchantSearchRow[]>(`/v1/ops/merchants/search?q=${encodeURIComponent(q)}`);
      if(n===searchSeq.current){setResults(res);setError(null);}
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSearching(false);
    }
  };

  useEffect(() => { void search(""); }, []);

  const openMerchant = async (id: string) => {
    const n=++detailSeq.current;setSelected(null);setLoadingDetail(true);
    try {
      const res = await api<MerchantControlsData>(`/v1/ops/merchants/${id}/controls`);
      if(n===detailSeq.current){if(res?.merchant?.id!==id)throw Error("Merchant identity mismatch");setSelected(res);setError(null);}
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoadingDetail(false);
    }
  };

  async function toggleFreeze(id: string, frozen: boolean) {
    if(busy||!perms?.canFreezeRisk||(!frozen&&!perms.canApproveMakerChecker))return;
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
      setMsg(formatDualControlOutcome(res, res.payoutsFrozen===frozen?"Merchant payout state change confirmed.":"Request returned; verify merchant payout state."));
      await openMerchant(id);
      await search(query);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function revokeKey(keyId: string, merchantId: string) {
    if(busy||!perms?.canFreezeRisk)return;
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
      const result=await api<{status:string}>(`/v1/ops/kill-switch/api-keys/${keyId}`, { body: { active: false, reason } });
      if(!["REVOKED","ALREADY_REVOKED"].includes(result?.status))throw Error("Revocation not verified");
      setMsg(`API key ${keyId}: ${result.status}.`);
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
      {error && <p className="error" role="alert">{error}</p>}
      {msg && <p className="notice" role="status">{msg}</p>}

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
          {!searching && results.length === 0 && <tr><td colSpan={6} className="muted">{error?"Merchant search unavailable.":"No merchants matched."}</td></tr>}
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
                disabled={busy||(selected.merchant.frozen&&!perms?.canApproveMakerChecker)}
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
                <div className="big">{b.currency==="GHS"?formatMoney(b.balance):`${b.currency} ${b.balance} minor units`}</div>
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

export function ledgerIntegrityClear(i:Integrity):boolean {return i.accountsWithDrift===0&&i.unbalancedTransactions===0&&Object.values(i.netByCurrency).every(v=>Number.isSafeInteger(v)&&v===0);}
export function canReviewProposal(p:Pick<MakerCheckerProposal,"status"|"proposedBy"|"expiresAt">,subject:string,allowed:boolean,now=Date.now()):boolean {const expiry=Date.parse(p.expiresAt);return allowed&&p.status==="PENDING"&&Boolean(subject)&&Boolean(p.proposedBy)&&p.proposedBy!==subject&&Number.isFinite(expiry)&&expiry>now;}
export function canResolveException(i:Pick<Item,"severity"|"kind">,perms:OpsPermissions):boolean {const sensitive=i.severity.toLowerCase()==="high"||/recon|rail|payout|custodian|security|fraud|ledger|audit/i.test(i.kind);return !sensitive||perms.canManageFinance||perms.canFreezeRisk;}
function opsDate(v:string){return Number.isFinite(Date.parse(v))?new Intl.DateTimeFormat("en-GH",{dateStyle:"medium",timeStyle:"short",timeZone:"Africa/Accra"}).format(new Date(v)):"Unavailable";}
function useOpsRead<T>(path:string|null){const [data,setData]=useState<T|null>(null),[error,setError]=useState<string|null>(null),[busy,setBusy]=useState(false),[checked,setChecked]=useState<string|null>(null);const seq=useRef(0);const load=useCallback(async()=>{const n=++seq.current;setData(null);setError(null);setChecked(null);if(!path){setBusy(false);return;}setBusy(true);try{const v=await api<T>(path,{signal:AbortSignal.timeout(20000)});if(v==null)throw Error("No server data returned");if(n===seq.current){setData(v);setChecked(new Date().toISOString());}}catch(e){if(n===seq.current)setError(e instanceof Error?e.message:"Records unavailable");}finally{if(n===seq.current)setBusy(false);}},[path]);useEffect(()=>{void load();return()=>{seq.current++;};},[load]);return {data,error,busy,checked,load};}
function RiskAudit(){const [subject,setSubject]=useState(""),[submitted,setSubmitted]=useState("");const data=useOpsRead<Record<string,unknown>[]>(submitted?"/v1/ops/audit?subject="+encodeURIComponent(submitted):null);return <section className="panel"><h2>Risk and audit evidence</h2><p className="muted">Search the server's audit records by exact subject. Fraud model telemetry and alert scores are not available here; use the exception queue for recorded risk alerts.</p><form className="wallet-filter" onSubmit={e=>{e.preventDefault();const s=subject.trim();if(s===submitted)void data.load();else setSubmitted(s);}}><label>Audit subject<input value={subject} required maxLength={200} onChange={e=>setSubject(e.target.value)} placeholder="merchant:UUID or customer:ID"/></label><button disabled={data.busy||!subject.trim()}>Search audit</button></form>{data.error&&<p className="error" role="alert">{data.error}</p>}{data.checked&&<p className="small muted">Subject: {submitted} · checked {opsDate(data.checked)} GMT · latest 200 records</p>}{data.data&&<div className="table-scroll" tabIndex={0} role="region" aria-label="Audit records"><table className="grid"><thead><tr><th>Time GMT</th><th>Actor</th><th>Action</th><th>Subject</th><th>Details</th></tr></thead><tbody>{data.data.map((r,i)=><tr key={i}><td>{opsDate(String(r.created_at))}</td><td>{String(r.actor??"—")}</td><td>{String(r.action??"—")}</td><td>{String(r.subject??"—")}</td><td className="small mono">{String(r.details??"—")}</td></tr>)}{!data.data.length&&<tr><td colSpan={5}>No matching audit records.</td></tr>}</tbody></table></div>}</section>;}
interface SuspenseRow{id:string;source:string;amountPesewas:number;currency:string;reason:string;status:string;createdAt:string;agingBucket:string}
function SuspenseMonitor(){const [filter,setFilter]=useState("OPEN");const rows=useOpsRead<SuspenseRow[]>("/v1/ops/suspense?limit=500&status="+filter);const aging=useOpsRead<{bucket:string;count:number;totalAmountPesewas:number}[]>("/v1/ops/suspense/aging");return <section className="panel"><div className="page-title"><div><h2>Unresolved reconciliation funds</h2><p className="muted small">Read-only suspense monitoring. Clearing requires separately authorized review.</p></div><button className="secondary" disabled={rows.busy||aging.busy} onClick={()=>{void rows.load();void aging.load();}}>Refresh</button></div>{aging.error&&<p className="error" role="alert">{aging.error}</p>}{aging.data&&<div className="cards">{aging.data.map(a=><div className="card" key={a.bucket}><div className="muted">{a.bucket}</div><strong>{a.count} items</strong><p className="small">{a.totalAmountPesewas} minor units (server aggregate; currencies not separated)</p></div>)}</div>}<label>Status<select value={filter} onChange={e=>setFilter(e.target.value)}>{["OPEN","PROPOSED","ALL"].map(s=><option key={s}>{s}</option>)}</select></label>{rows.error&&<p className="error" role="alert">{rows.error}</p>}{rows.data&&<div className="table-scroll" tabIndex={0} role="region" aria-label="Suspense items"><table className="grid"><thead><tr><th>Reference</th><th>Source</th><th>Currency</th><th>Minor units</th><th>Reason</th><th>Status</th><th>Aging</th></tr></thead><tbody>{rows.data.map(r=><tr key={r.id}><td className="mono small">{r.id}</td><td>{r.source}</td><td>{r.currency}</td><td>{r.amountPesewas}</td><td>{r.reason}</td><td>{r.status}</td><td>{r.agingBucket}</td></tr>)}{!rows.data.length&&<tr><td colSpan={7}>No matching suspense records.</td></tr>}</tbody></table></div>}</section>;}
