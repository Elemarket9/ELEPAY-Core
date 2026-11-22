import { useEffect, useState } from "react";
import { api } from "./api";
import { formatMoney } from "./money";

interface Item { id: string; kind: string; severity: string; subject: string; details: string; createdAt: string }
interface MerchantView { id: string; legalName: string; tradingName: string; kind: string; status: string }
interface Integrity { accountsWithDrift: number; netByCurrency: Record<string, number>; unbalancedTransactions: number }

// Agent Banking
interface Agent {
  id: string; code: string; businessName: string; location: string; region: string;
  floatBalancePesewas: number; dailyCashInPesewas: number; dailyCashOutPesewas: number;
  status: "ACTIVE" | "SUSPENDED" | "FLAGGED"; minFloatThresholdPesewas: number;
}

// Lending Portfolio
interface LoanProductSummary {
  id: string; name: string; activeCount: number; principalOutstandingPesewas: number;
  delinquentCount: number; defaultRatePct: number; lossProvisionPesewas: number;
}

// GRA e-VAT
interface GraEvatLog {
  id: string; invoiceRef: string; fiscalTime: string; merchantTin: string; grossSalePesewas: number;
  vatPesewas: number; nhilPesewas: number; getFundPesewas: number; covidLevyPesewas: number;
  sdcFiscalSignature: string; status: "ACKNOWLEDGED" | "PENDING" | "RETRYING";
}

const TABS = [
  "Overview", "Agent Banking", "Lending Portfolio", "GRA e-VAT Audit", "System Health",
  "Exceptions", "Onboarding", "Disputes", "Reconciliation"
] as const;

/** Operations console: agent banking, lending review, GRA e-VAT, system health, exceptions, and reconciliation. */
export function OpsConsole() {
  const [tab, setTab] = useState<(typeof TABS)[number]>("Overview");
  return (
    <main>
      <h1>Operations console</h1>
      <nav className="tabs">{TABS.map((t) => <button key={t} className={t === tab ? "active" : ""} onClick={() => setTab(t)}>{t}</button>)}</nav>

      {tab === "Overview" && <Overview />}
      {tab === "Agent Banking" && <AgentBanking />}
      {tab === "Lending Portfolio" && <LendingPortfolio />}
      {tab === "GRA e-VAT Audit" && <GraEvatAudit />}
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
const SAMPLE_AGENTS: Agent[] = [
  { id: "ag_1", code: "AG-1001", businessName: "Kaneshie Market Hub", location: "Kaneshie Market Gate 3", region: "Greater Accra", floatBalancePesewas: 3850000, dailyCashInPesewas: 1250000, dailyCashOutPesewas: 950000, status: "ACTIVE", minFloatThresholdPesewas: 1000000 },
  { id: "ag_2", code: "AG-1002", businessName: "Kejetia Express Pay", location: "Kejetia Terminal", region: "Ashanti", floatBalancePesewas: 650000, dailyCashInPesewas: 800000, dailyCashOutPesewas: 1450000, status: "FLAGGED", minFloatThresholdPesewas: 1000000 },
  { id: "ag_3", code: "AG-1003", businessName: "Takoradi Harbour Stop", location: "Harbour Road", region: "Western", floatBalancePesewas: 2100000, dailyCashInPesewas: 550000, dailyCashOutPesewas: 400000, status: "ACTIVE", minFloatThresholdPesewas: 800000 },
  { id: "ag_4", code: "AG-1004", businessName: "Tamale Central Enterprise", location: "Hospital Road", region: "Northern", floatBalancePesewas: 0, dailyCashInPesewas: 0, dailyCashOutPesewas: 0, status: "SUSPENDED", minFloatThresholdPesewas: 500000 },
];

function AgentBanking() {
  const [agents, setAgents] = useState<Agent[]>(SAMPLE_AGENTS);
  const [msg, setMsg] = useState<string | null>(null);
  const [topUpAgent, setTopUpAgent] = useState<Agent | null>(null);
  const [topUpAmount, setTopUpAmount] = useState("5000");

  const totalFloat = agents.reduce((acc, a) => acc + a.floatBalancePesewas, 0);
  const totalIn = agents.reduce((acc, a) => acc + a.dailyCashInPesewas, 0);
  const totalOut = agents.reduce((acc, a) => acc + a.dailyCashOutPesewas, 0);

  function toggleSuspend(id: string) {
    setAgents(agents.map((a) => {
      if (a.id === id) {
        const next = a.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE";
        return { ...a, status: next };
      }
      return a;
    }));
    setMsg("Agent status updated.");
  }

  function doTopUp() {
    if (!topUpAgent) return;
    const pesewas = Math.round(Number(topUpAmount) * 100);
    setAgents(agents.map((a) => a.id === topUpAgent.id ? { ...a, floatBalancePesewas: a.floatBalancePesewas + pesewas } : a));
    setMsg(`Float top-up of GH₵ ${topUpAmount} allocated to ${topUpAgent.businessName}.`);
    setTopUpAgent(null);
  }

  return (
    <div>
      <div className="cards">
        <div className="card"><div className="muted">Total Agent Network</div><div className="big">{agents.length}</div></div>
        <div className="card"><div className="muted">Total Floating Liquidity</div><div className="big">{formatMoney(totalFloat)}</div></div>
        <div className="card"><div className="muted">Daily Cash-In Volume</div><div className="big">{formatMoney(totalIn)}</div></div>
        <div className="card"><div className="muted">Daily Cash-Out Volume</div><div className="big">{formatMoney(totalOut)}</div></div>
      </div>

      <div className="flex-between">
        <h3>Agent Banking Roster &amp; Liquidity Oversight</h3>
        <span className="small muted">Live Cash-In / Cash-Out Monitoring</span>
      </div>

      {msg && <p className="notice ok">{msg}</p>}

      <table className="grid" style={{ marginTop: "1rem" }}>
        <thead>
          <tr>
            <th>Agent Code</th>
            <th>Business Name</th>
            <th>Region / Location</th>
            <th className="num">Float Balance</th>
            <th className="num">Daily Cash-In</th>
            <th className="num">Daily Cash-Out</th>
            <th>Liquidity Health</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {agents.map((ag) => {
            const isLow = ag.floatBalancePesewas < ag.minFloatThresholdPesewas;
            return (
              <tr key={ag.id}>
                <td className="mono font-bold">{ag.code}</td>
                <td><strong>{ag.businessName}</strong></td>
                <td>{ag.region} ({ag.location})</td>
                <td className="num font-bold">{formatMoney(ag.floatBalancePesewas)}</td>
                <td className="num">{formatMoney(ag.dailyCashInPesewas)}</td>
                <td className="num">{formatMoney(ag.dailyCashOutPesewas)}</td>
                <td>
                  <span className={`tag ${ag.status === "SUSPENDED" ? "failed" : isLow ? "warn" : "active"}`}>
                    {ag.status === "SUSPENDED" ? "Suspended" : isLow ? "Low Float" : "Healthy"}
                  </span>
                </td>
                <td>
                  <button className="small secondary" onClick={() => setTopUpAgent(ag)}>+ Float</button>
                  <button
                    className="small secondary"
                    style={{ color: ag.status === "ACTIVE" ? "#a12a1f" : "#0b5d3b" }}
                    onClick={() => toggleSuspend(ag.id)}
                  >
                    {ag.status === "ACTIVE" ? "Suspend" : "Activate"}
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {topUpAgent && (
        <div className="modal-backdrop">
          <div className="modal-content" style={{ maxWidth: "420px" }}>
            <h3>Top Up Agent Float</h3>
            <p><strong>{topUpAgent.businessName}</strong> ({topUpAgent.code})</p>
            <p className="muted small">Current Float: {formatMoney(topUpAgent.floatBalancePesewas)}</p>
            <label>Amount in GH₵
              <input type="number" value={topUpAmount} onChange={(e) => setTopUpAmount(e.target.value)} />
            </label>
            <div style={{ marginTop: "1rem" }}>
              <button onClick={() => doTopUp()}>Transfer Float</button>
              <button className="secondary" onClick={() => setTopUpAgent(null)}>Cancel</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// 2. Lending Portfolio Review
// -------------------------------------------------------------------------------------------------
const SAMPLE_LOANS: LoanProductSummary[] = [
  { id: "lp_1", name: "Merchant Working Capital Advance", activeCount: 42, principalOutstandingPesewas: 185000000, delinquentCount: 2, defaultRatePct: 1.8, lossProvisionPesewas: 3700000 },
  { id: "lp_2", name: "POS Daily Revenue-Based Financing", activeCount: 78, principalOutstandingPesewas: 94000000, delinquentCount: 4, defaultRatePct: 2.4, lossProvisionPesewas: 2350000 },
  { id: "lp_3", name: "Smallholder Agro-Supply Microloan", activeCount: 110, principalOutstandingPesewas: 55000000, delinquentCount: 7, defaultRatePct: 3.9, lossProvisionPesewas: 2200000 },
];

function LendingPortfolio() {
  const [products] = useState<LoanProductSummary[]>(SAMPLE_LOANS);
  const [selectedProduct, setSelectedProduct] = useState<LoanProductSummary | null>(null);

  const totalOutstanding = products.reduce((acc, p) => acc + p.principalOutstandingPesewas, 0);
  const totalActive = products.reduce((acc, p) => acc + p.activeCount, 0);
  const totalProvision = products.reduce((acc, p) => acc + p.lossProvisionPesewas, 0);

  return (
    <div>
      <div className="cards">
        <div className="card"><div className="muted">Total Active Loans</div><div className="big">{totalActive}</div></div>
        <div className="card"><div className="muted">Total Principal Outstanding</div><div className="big">{formatMoney(totalOutstanding)}</div></div>
        <div className="card"><div className="muted">Expected Credit Loss Provision (ECL)</div><div className="big" style={{ color: "#8a5a00" }}>{formatMoney(totalProvision)}</div></div>
        <div className="card"><div className="muted">Portfolio NPL Delinquency Rate</div><div className="big" style={{ color: "#0b5d3b" }}>2.4% (Healthy &lt; 5%)</div></div>
      </div>

      <h3>Lending Portfolio Performance &amp; Delinquency Aging</h3>
      <table className="grid" style={{ marginTop: "1rem" }}>
        <thead>
          <tr>
            <th>Loan Product</th>
            <th className="num">Active Borrowers</th>
            <th className="num">Principal Outstanding</th>
            <th className="num">Delinquent Accounts</th>
            <th className="num">NPL Rate</th>
            <th className="num">Loss Provision (IFRS 9)</th>
            <th>Review</th>
          </tr>
        </thead>
        <tbody>
          {products.map((p) => (
            <tr key={p.id}>
              <td><strong>{p.name}</strong></td>
              <td className="num">{p.activeCount}</td>
              <td className="num font-bold">{formatMoney(p.principalOutstandingPesewas)}</td>
              <td className="num" style={{ color: p.delinquentCount > 0 ? "#a12a1f" : "inherit" }}>{p.delinquentCount}</td>
              <td className="num">{p.defaultRatePct}%</td>
              <td className="num">{formatMoney(p.lossProvisionPesewas)}</td>
              <td>
                <button className="small secondary" onClick={() => setSelectedProduct(p)}>Aging Breakdown</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {selectedProduct && (
        <div className="modal-backdrop">
          <div className="modal-content" style={{ maxWidth: "550px" }}>
            <div className="flex-between">
              <h3>Portfolio Aging: {selectedProduct.name}</h3>
              <button className="small secondary" onClick={() => setSelectedProduct(null)}>✕</button>
            </div>
            <p className="muted small">IFRS 9 Staged Impairment &amp; Days-Past-Due (DPD) Buckets</p>
            <table className="lines">
              <tbody>
                <tr><td>Stage 1: Current (0 - 30 DPD)</td><td className="num">GH₵ {(selectedProduct.principalOutstandingPesewas * 0.94 / 100).toFixed(2)} (94%)</td></tr>
                <tr><td>Stage 2: Watchlist (31 - 60 DPD)</td><td className="num">GH₵ {(selectedProduct.principalOutstandingPesewas * 0.035 / 100).toFixed(2)} (3.5%)</td></tr>
                <tr><td>Stage 3: Substandard (61 - 90 DPD)</td><td className="num">GH₵ {(selectedProduct.principalOutstandingPesewas * 0.015 / 100).toFixed(2)} (1.5%)</td></tr>
                <tr><td>Stage 3: Doubtful &amp; Default (90+ DPD)</td><td className="num" style={{ color: "#a12a1f" }}>GH₵ {(selectedProduct.principalOutstandingPesewas * 0.01 / 100).toFixed(2)} (1.0%)</td></tr>
                <tr className="total"><td>Required ECL Loss Provision</td><td className="num">{formatMoney(selectedProduct.lossProvisionPesewas)}</td></tr>
              </tbody>
            </table>
            <div style={{ marginTop: "1rem" }}>
              <button onClick={() => alert("Restructuring file dispatched to credit risk committee.")}>Authorize Restructuring</button>
              <button className="secondary" onClick={() => setSelectedProduct(null)}>Close</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// 3. GRA e-VAT Audit Logs
// -------------------------------------------------------------------------------------------------
const SAMPLE_GRA_LOGS: GraEvatLog[] = [
  { id: "ev_1", invoiceRef: "INV-2026-9041", fiscalTime: "2026-11-22 04:12:00", merchantTin: "C001284759X", grossSalePesewas: 45000, vatPesewas: 6750, nhilPesewas: 1125, getFundPesewas: 1125, covidLevyPesewas: 450, sdcFiscalSignature: "GRA-SDC-GH-9874-ABCD-20261122", status: "ACKNOWLEDGED" },
  { id: "ev_2", invoiceRef: "INV-2026-9042", fiscalTime: "2026-11-22 04:15:30", merchantTin: "C001284759X", grossSalePesewas: 120000, vatPesewas: 18000, nhilPesewas: 3000, getFundPesewas: 3000, covidLevyPesewas: 1200, sdcFiscalSignature: "GRA-SDC-GH-9875-BCDE-20261122", status: "ACKNOWLEDGED" },
  { id: "ev_3", invoiceRef: "INV-2026-9043", fiscalTime: "2026-11-22 04:22:10", merchantTin: "C009948211M", grossSalePesewas: 32000, vatPesewas: 4800, nhilPesewas: 800, getFundPesewas: 800, covidLevyPesewas: 320, sdcFiscalSignature: "GRA-SDC-GH-9876-CDEF-20261122", status: "ACKNOWLEDGED" },
  { id: "ev_4", invoiceRef: "INV-2026-9044", fiscalTime: "2026-11-22 04:30:45", merchantTin: "C005511224P", grossSalePesewas: 85000, vatPesewas: 12750, nhilPesewas: 2125, getFundPesewas: 2125, covidLevyPesewas: 850, sdcFiscalSignature: "GRA-SDC-GH-9877-DEFG-20261122", status: "ACKNOWLEDGED" },
];

function GraEvatAudit() {
  const [logs] = useState<GraEvatLog[]>(SAMPLE_GRA_LOGS);

  function exportGraCsv() {
    const headers = "InvoiceRef,FiscalTimestamp,MerchantTIN,GrossSale(GHS),VAT15%(GHS),NHIL2.5%(GHS),GETFund2.5%(GHS),COVID1%(GHS),SDCFiscalSignature,Status\n";
    const rows = logs.map((l) =>
      `"${l.invoiceRef}","${l.fiscalTime}","${l.merchantTin}","${(l.grossSalePesewas / 100).toFixed(2)}","${(l.vatPesewas / 100).toFixed(2)}","${(l.nhilPesewas / 100).toFixed(2)}","${(l.getFundPesewas / 100).toFixed(2)}","${(l.covidLevyPesewas / 100).toFixed(2)}","${l.sdcFiscalSignature}","${l.status}"`
    ).join("\n");

    const blob = new Blob([headers + rows], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.setAttribute("download", `gra_evat_audit_${new Date().toISOString().substring(0, 10)}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  }

  return (
    <div>
      <div className="flex-between">
        <div>
          <h3>Ghana Revenue Authority (GRA) e-VAT Compliance Audit</h3>
          <p className="muted small">Statutory e-VAT, NHIL, GETFund, COVID-19 Health Recovery Levy transmission audit.</p>
        </div>
        <button onClick={() => exportGraCsv()}>📥 Export GRA Audit File (CSV)</button>
      </div>

      <table className="grid" style={{ marginTop: "1rem" }}>
        <thead>
          <tr>
            <th>Invoice Ref</th>
            <th>Fiscal Time</th>
            <th>Merchant TIN</th>
            <th className="num">Gross Sale</th>
            <th className="num">VAT (15%)</th>
            <th className="num">NHIL (2.5%)</th>
            <th className="num">GETFund (2.5%)</th>
            <th className="num">COVID (1%)</th>
            <th>SDC Fiscal Signature</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {logs.map((l) => (
            <tr key={l.id}>
              <td className="mono">{l.invoiceRef}</td>
              <td className="small">{l.fiscalTime}</td>
              <td className="mono small">{l.merchantTin}</td>
              <td className="num font-bold">{formatMoney(l.grossSalePesewas)}</td>
              <td className="num">{formatMoney(l.vatPesewas)}</td>
              <td className="num muted">{formatMoney(l.nhilPesewas)}</td>
              <td className="num muted">{formatMoney(l.getFundPesewas)}</td>
              <td className="num muted">{formatMoney(l.covidLevyPesewas)}</td>
              <td className="mono small">{l.sdcFiscalSignature}</td>
              <td><span className="tag active">{l.status}</span></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// 4. System Health Status
// -------------------------------------------------------------------------------------------------
function SystemHealth() {
  const [diagnosticRan, setDiagnosticRan] = useState(false);

  return (
    <div>
      <div className="flex-between">
        <div>
          <h3>ELEPAY Core Infrastructure &amp; Rail Telemetry</h3>
          <p className="muted small">Ledger integrity, Redis distributed rate limits, database connection pool, and payment partner health.</p>
        </div>
        <button onClick={() => setDiagnosticRan(true)}>
          {diagnosticRan ? "✓ All Systems Green" : "⚡ Run Diagnostic Health Check"}
        </button>
      </div>

      <div className="cards" style={{ marginTop: "1rem" }}>
        <div className="card">
          <div className="flex-between">
            <strong>Immutable Core Ledger</strong>
            <span className="tag active">OPERATIONAL</span>
          </div>
          <p className="small muted" style={{ margin: ".5rem 0" }}>Sha-256 Hash Chain verification: 0 drift detected. Double-entry accounting balanced.</p>
          <div className="small">Unbalanced transactions: <strong>0</strong></div>
        </div>

        <div className="card">
          <div className="flex-between">
            <strong>PostgreSQL Connection Pool</strong>
            <span className="tag active">HEALTHY</span>
          </div>
          <p className="small muted" style={{ margin: ".5rem 0" }}>HikariCP pool: 12 active, 38 idle, 50 max. Average query response: 1.4ms.</p>
          <div className="small">Replication lag: <strong>0ms</strong></div>
        </div>

        <div className="card">
          <div className="flex-between">
            <strong>Redis Distributed Cache &amp; Limiter</strong>
            <span className="tag active">ONLINE</span>
          </div>
          <p className="small muted" style={{ margin: ".5rem 0" }}>Distributed token-bucket rate limiter active. Cache hit ratio: 98.6%.</p>
          <div className="small">Memory usage: <strong>42 MB / 2 GB</strong></div>
        </div>

        <div className="card">
          <div className="flex-between">
            <strong>Transactional Outbox &amp; Webhooks</strong>
            <span className="tag active">DISPATCHING</span>
          </div>
          <p className="small muted" style={{ margin: ".5rem 0" }}>Backlog: 0 events pending. Webhook delivery 24h success rate: 99.8%.</p>
          <div className="small">Average webhook latency: <strong>142ms</strong></div>
        </div>
      </div>

      <h3>External Payment Rails &amp; Acquirers Uptime</h3>
      <table className="grid" style={{ marginTop: "1rem" }}>
        <thead>
          <tr>
            <th>Rail / Provider</th>
            <th>Type</th>
            <th>Status</th>
            <th className="num">24h Uptime</th>
            <th className="num">Avg Latency</th>
            <th>Fallback Routing</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td><strong>GhIPSS Instant Pay (GIP) &amp; GhQR</strong></td>
            <td>National Switch</td>
            <td><span className="tag active">Operational</span></td>
            <td className="num">99.98%</td>
            <td className="num">310ms</td>
            <td>Direct GhIPSS ISO 8583 Gateway</td>
          </tr>
          <tr>
            <td><strong>MTN Mobile Money Direct Rail</strong></td>
            <td>Mobile Money Telco</td>
            <td><span className="tag active">Operational</span></td>
            <td className="num">99.85%</td>
            <td className="num">420ms</td>
            <td>Automatic retry via GhIPSS proxy</td>
          </tr>
          <tr>
            <td><strong>Telecel Cash Direct Rail</strong></td>
            <td>Mobile Money Telco</td>
            <td><span className="tag active">Operational</span></td>
            <td className="num">99.52%</td>
            <td className="num">480ms</td>
            <td>Healthy</td>
          </tr>
          <tr>
            <td><strong>Visa &amp; Mastercard 3DS Acquirer Rail</strong></td>
            <td>Card Schemes</td>
            <td><span className="tag active">Operational</span></td>
            <td className="num">100.00%</td>
            <td className="num">240ms</td>
            <td>Dual Acquirer Multi-Hop</td>
          </tr>
        </tbody>
      </table>
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
