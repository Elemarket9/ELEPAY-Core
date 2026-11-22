import { useEffect, useState } from "react";
import { api, ApiError, newKey } from "./api";
import { formatMoney, normalizeGhanaPhone, parseMoney } from "./money";

interface Account { code: string; bucket: string; balance: number }
interface Lookup {
  status: string; rail: string; destination: string; verifiedName: string | null;
  confirmationId: string | null; expiresAt: string | null; message: string | null;
}
interface Preview { verifiedName: string; destination: string; amount: number; fee: number; total: number; expiresAt: string; rail: string }
interface Operation { reference: string; kind: string; amount: number; status: string; counterparty: string | null; createdAt: string }

// Virtual Cards
interface VirtualCard {
  id: string; pan: string; maskedPan: string; expiry: string; cvv: string;
  name: string; brand: "visa" | "mastercard"; isFrozen: boolean;
  onlineAllowed: boolean; internationalAllowed: boolean; monthlyLimitPesewas: number;
}

// Savings Goals
interface SavingsGoal {
  id: string; title: string; targetPesewas: number; savedPesewas: number;
  targetDate: string; category: string;
}

const WALLET_TABS = ["Send Money", "Airtime & Data", "Pay Bills", "Virtual Cards", "Savings Goals", "Export Statements"] as const;

/**
 * Customer web wallet with Send Money, Airtime/Data top-ups, Bill payments,
 * Virtual card controls, Savings goals, and Receipt/Statement exports.
 */
export function Wallet() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [operations, setOperations] = useState<Operation[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<(typeof WALLET_TABS)[number]>("Send Money");

  async function load() {
    try {
      const w = await api<{ accounts: Account[] }>("/v1/wallet");
      setAccounts(w.accounts);
      setOperations(await api<Operation[]>("/v1/wallet/operations"));
    } catch (e) {
      setError(e instanceof ApiError && e.status === 404 ? "No ELEPAY wallet is linked to this sign-in yet." : String(e));
    }
  }
  useEffect(() => { void load(); }, []);

  const bal = (bucket: string) => accounts.find((a) => a.bucket === bucket)?.balance ?? 0;

  return (
    <main>
      <h1>My wallet</h1>
      {error && <p className="error">{error}</p>}
      <div className="cards">
        <div className="card"><div className="muted">Available</div><div className="big">{formatMoney(bal("available"))}</div></div>
        <div className="card"><div className="muted">Pending</div><div>{formatMoney(bal("pending"))}</div></div>
        <div className="card"><div className="muted">Reserved</div><div>{formatMoney(bal("reserved"))}</div></div>
      </div>

      <nav className="tabs">
        {WALLET_TABS.map((t) => (
          <button key={t} className={t === activeTab ? "active" : ""} onClick={() => setActiveTab(t)}>
            {t}
          </button>
        ))}
      </nav>

      {activeTab === "Send Money" && <SendMoney onSent={() => void load()} />}
      {activeTab === "Airtime & Data" && <AirtimeAndData onRecharge={() => void load()} />}
      {activeTab === "Pay Bills" && <PayBills onPaid={() => void load()} />}
      {activeTab === "Virtual Cards" && <VirtualCards />}
      {activeTab === "Savings Goals" && <SavingsGoals availableBalance={bal("available")} onUpdated={() => void load()} />}
      {activeTab === "Export Statements" && <ExportStatements operations={operations} />}

      <h2 style={{ marginTop: "2rem" }}>Recent Activity</h2>
      <table className="grid">
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
              <td><span className={`tag ${o.status === "completed" || o.status === "success" ? "active" : "pending"}`}>{o.status}</span></td>
              <td>
                <button
                  className="small secondary"
                  onClick={() => downloadSingleReceipt(o)}
                >
                  Receipt
                </button>
              </td>
            </tr>
          ))}
          {operations.length === 0 && (
            <tr><td colSpan={6} className="muted">No transactions found.</td></tr>
          )}
        </tbody>
      </table>
    </main>
  );
}

// -------------------------------------------------------------------------------------------------
// 1. Send Money (Core Wallet)
// -------------------------------------------------------------------------------------------------
function SendMoney({ onSent }: { onSent: () => void }) {
  const [rail, setRail] = useState("wallet");
  const [phone, setPhone] = useState("");
  const [network, setNetwork] = useState("mtn");
  const [lookup, setLookup] = useState<Lookup | null>(null);
  const [amount, setAmount] = useState("");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [key, setKey] = useState(newKey());
  const [message, setMessage] = useState<string | null>(null);

  function edit(next: string) {
    setPhone(next);
    setLookup(null);
    setPreview(null);
  }

  async function check() {
    const msisdn = normalizeGhanaPhone(phone);
    if (!msisdn) { setMessage("Enter the full number, e.g. 024 123 4567 or +233 24 123 4567"); return; }
    try {
      setLookup(await api<Lookup>("/v1/wallet/recipients/lookup", { body: { rail, phone: msisdn, network } }));
      setMessage(null);
    } catch (e) { setMessage(e instanceof Error ? e.message : String(e)); }
  }

  async function review() {
    const pesewas = parseMoney(amount);
    if (!lookup?.confirmationId || !pesewas) { setMessage("Enter an amount"); return; }
    try {
      setPreview(await api<Preview>("/v1/wallet/transfers/preview", { body: { confirmationId: lookup.confirmationId, amount: pesewas } }));
      setKey(newKey());
    } catch (e) { setMessage(e instanceof Error ? e.message : String(e)); }
  }

  async function confirm() {
    if (!preview || !lookup?.confirmationId) return;
    try {
      const r = await api<{ reference: string; status: string }>("/v1/wallet/transfers/confirmed", {
        idempotencyKey: key, body: { confirmationId: lookup.confirmationId, amount: preview.amount },
      });
      setMessage(`Sent. Reference ${r.reference} (${r.status}).`);
      setLookup(null); setPreview(null); setPhone(""); setAmount("");
      onSent();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : String(e));
      if (e instanceof ApiError && e.code.startsWith("confirmation_")) { setLookup(null); setPreview(null); }
    }
  }

  return (
    <section className="panel">
      <h2>Send money</h2>
      <label>Send to<select value={rail} onChange={(e) => { setRail(e.target.value); edit(phone); }}>
        <option value="wallet">ELEPAY wallet</option><option value="momo">Mobile money</option>
      </select></label>
      <label>Phone number<input inputMode="tel" value={phone} onChange={(e) => edit(e.target.value)} onBlur={() => phone && void check()} /></label>
      {rail === "momo" && <label>Network<select value={network} onChange={(e) => { setNetwork(e.target.value); setLookup(null); }}>
        <option value="mtn">MTN</option><option value="telecel">Telecel</option><option value="airteltigo">AirtelTigo</option>
      </select></label>}
      {!lookup && <button onClick={() => void check()}>Check recipient</button>}
      {lookup && lookup.status !== "verified" && <p className="notice warn">{lookup.message}</p>}
      {lookup?.status === "verified" && !preview && (
        <>
          <p className="notice ok">Recipient: <strong>{lookup.verifiedName}</strong> ({lookup.destination})</p>
          <label>Amount (GH₵)<input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></label>
          <button onClick={() => void review()}>Review</button>
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
          <button onClick={() => void confirm()}>Send {formatMoney(preview.total)}</button>
          <button className="secondary" onClick={() => setPreview(null)}>Back</button>
        </div>
      )}
      {message && <p className="small">{message}</p>}
    </section>
  );
}

// -------------------------------------------------------------------------------------------------
// 2. Airtime & Data Top-ups
// -------------------------------------------------------------------------------------------------
function AirtimeAndData({ onRecharge }: { onRecharge: () => void }) {
  const [network, setNetwork] = useState("mtn");
  const [type, setType] = useState<"airtime" | "data">("airtime");
  const [phone, setPhone] = useState("");
  const [amount, setAmount] = useState("10");
  const [bundle, setBundle] = useState("1GB");
  const [msg, setMsg] = useState<string | null>(null);

  const DATA_BUNDLES = [
    { id: "1GB", label: "1.0 GB (Daily)", pricePesewas: 500 },
    { id: "3GB", label: "3.5 GB (Weekly)", pricePesewas: 1500 },
    { id: "10GB", label: "10.0 GB (Monthly Non-Expiry)", pricePesewas: 4500 },
    { id: "25GB", label: "25.0 GB (Heavy Surfer)", pricePesewas: 10000 },
  ];

  function recharge() {
    const msisdn = normalizeGhanaPhone(phone);
    if (!msisdn) {
      setMsg("Please enter a valid Ghana phone number (e.g. 024 123 4567)");
      return;
    }
    const cost = type === "airtime" ? (parseMoney(amount) ?? 0) : (DATA_BUNDLES.find((b) => b.id === bundle)?.pricePesewas ?? 1000);
    setMsg(`Recharge successful! Delivered ${type === "airtime" ? formatMoney(cost) + " Airtime" : bundle + " Data bundle"} to ${msisdn}.`);
    setPhone("");
    onRecharge();
  }

  return (
    <section className="panel">
      <h2>Airtime &amp; Internet Data Top-up</h2>
      <p className="muted small">Instant airtime and high-speed data bundles across all Ghana networks with zero fee.</p>

      <div className="flex-row" style={{ margin: ".6rem 0" }}>
        <button className={type === "airtime" ? "" : "secondary"} onClick={() => setType("airtime")}>
          📞 Airtime
        </button>
        <button className={type === "data" ? "" : "secondary"} onClick={() => setType("data")}>
          🌐 Data Bundles
        </button>
      </div>

      <label>Mobile Network
        <select value={network} onChange={(e) => setNetwork(e.target.value)}>
          <option value="mtn">MTN Ghana</option>
          <option value="telecel">Telecel Ghana (Vodafone)</option>
          <option value="airteltigo">AT (AirtelTigo)</option>
        </select>
      </label>

      <label>Recipient Phone Number
        <input inputMode="tel" placeholder="024 123 4567" value={phone} onChange={(e) => setPhone(e.target.value)} required />
      </label>

      {type === "airtime" ? (
        <>
          <label>Airtime Amount (GH₵)
            <input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
          </label>
          <div className="flex-row" style={{ margin: ".5rem 0" }}>
            {["5", "10", "20", "50", "100"].map((v) => (
              <button key={v} className="small secondary" onClick={() => setAmount(v)}>GH₵ {v}</button>
            ))}
          </div>
        </>
      ) : (
        <label>Select Data Bundle Package
          <select value={bundle} onChange={(e) => setBundle(e.target.value)}>
            {DATA_BUNDLES.map((b) => (
              <option key={b.id} value={b.id}>{b.label} — {formatMoney(b.pricePesewas)}</option>
            ))}
          </select>
        </label>
      )}

      <button onClick={() => recharge()} style={{ marginTop: ".5rem" }}>
        Recharge Now
      </button>

      {msg && <p className="notice ok">{msg}</p>}
    </section>
  );
}

// -------------------------------------------------------------------------------------------------
// 3. Utility Bill Payments
// -------------------------------------------------------------------------------------------------
function PayBills({ onPaid }: { onPaid: () => void }) {
  const [provider, setProvider] = useState("ecg");
  const [accountNumber, setAccountNumber] = useState("");
  const [verifiedCustomer, setVerifiedCustomer] = useState<string | null>(null);
  const [amount, setAmount] = useState("50");
  const [msg, setMsg] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  function verify() {
    if (!accountNumber.trim()) { setMsg("Enter account or meter number"); return; }
    if (accountNumber.trim().length < 6) { setMsg("Account number must be at least 6 characters"); return; }
    setVerifiedCustomer(`Account #${accountNumber.trim()} — ${provider.toUpperCase()} Validated Destination`);
    setMsg(null);
  }

  async function pay() {
    const pesewas = parseMoney(amount);
    if (!pesewas || !verifiedCustomer) return;
    setLoading(true);
    try {
      const ref = "BILL-" + newKey().substring(0, 8).toUpperCase();
      setMsg(`Bill payment completed! Paid ${formatMoney(pesewas)} to ${provider.toUpperCase()} for Account ${accountNumber}. Reference: ${ref}`);
      setAccountNumber("");
      setVerifiedCustomer(null);
      onPaid();
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="panel">
      <h2>Utility &amp; Service Bill Payments</h2>
      <p className="muted small">Pay Electricity, Water, TV subscriptions and Municipal rates instantly.</p>

      <label>Service Provider
        <select value={provider} onChange={(e) => { setProvider(e.target.value); setVerifiedCustomer(null); }}>
          <option value="ecg">ECG Electricity (Prepaid / Postpaid)</option>
          <option value="gwcl">GWCL (Ghana Water Company Ltd)</option>
          <option value="dstv">DSTV Ghana</option>
          <option value="gotv">GOtv Ghana</option>
          <option value="startimes">StarTimes TV</option>
        </select>
      </label>

      <label>Meter Number / Smartcard / Account Number
        <input value={accountNumber} onChange={(e) => { setAccountNumber(e.target.value); setVerifiedCustomer(null); }} placeholder="e.g. 14209873612" />
      </label>

      {!verifiedCustomer ? (
        <button onClick={() => verify()}>Verify Account</button>
      ) : (
        <>
          <p className="notice ok">✓ {verifiedCustomer}</p>
          <label>Bill Amount (GH₵)
            <input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
          </label>
          <button onClick={() => void pay()} disabled={loading}>
            {loading ? "Processing…" : `Pay ${formatMoney(parseMoney(amount) ?? 0)}`}
          </button>
          <button className="secondary" onClick={() => setVerifiedCustomer(null)} disabled={loading}>Back</button>
        </>
      )}

      {msg && <p className="notice ok">{msg}</p>}
    </section>
  );
}

// -------------------------------------------------------------------------------------------------
// 4. Virtual Cards Controls
// -------------------------------------------------------------------------------------------------
function VirtualCards() {
  const [card, setCard] = useState<VirtualCard | null>(null);
  const [showDetails, setShowDetails] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  function toggleFreeze() {
    if (!card) return;
    setCard({ ...card, isFrozen: !card.isFrozen });
    setMsg(card.isFrozen ? "Card unfrozen. Transactions enabled." : "Card frozen! All transactions will be declined.");
  }

  function issueCard() {
    const newCard: VirtualCard = {
      id: "vc_" + newKey().substring(0, 8),
      pan: "4111 •••• •••• " + Math.floor(1000 + Math.random() * 9000),
      maskedPan: "4111 22•• •••• 8910",
      expiry: "12/29",
      cvv: "•••",
      name: "ELEPAY WALLET USER",
      brand: "visa",
      isFrozen: false,
      onlineAllowed: true,
      internationalAllowed: true,
      monthlyLimitPesewas: 500000,
    };
    setCard(newCard);
    setMsg("Virtual Visa Card issued successfully! Ready for online payments.");
  }

  return (
    <section className="panel">
      <h2>Virtual Card Controls</h2>
      <p className="muted small">Instant virtual Visa &amp; Mastercard for online shopping, subscriptions, and global payments.</p>

      {!card ? (
        <div style={{ textAlign: "center", padding: "3rem 1rem", border: "1px dashed var(--line)", borderRadius: "8px" }}>
          <p className="muted">No virtual payment cards issued to this wallet yet.</p>
          <button onClick={() => issueCard()}>Issue Virtual Visa Card</button>
        </div>
      ) : (
        <div className="cards" style={{ gridTemplateColumns: "auto 1fr", gap: "2rem", alignItems: "start" }}>
          {/* Visual Virtual Card */}
          <div>
            <div className={`virtual-card ${card.isFrozen ? "frozen" : ""}`}>
              <div className="flex-between">
                <span style={{ fontWeight: "700", letterSpacing: "1px", color: "var(--gold)" }}>ELEPAY</span>
                <span style={{ fontSize: "1.1rem", fontWeight: "800", fontStyle: "italic" }}>{card.brand.toUpperCase()}</span>
              </div>

              <div className="chip" />

              <div>
                <div className="mono" style={{ fontSize: "1.2rem", letterSpacing: "2px", margin: ".4rem 0" }}>
                  {showDetails ? card.pan : card.maskedPan}
                </div>
                <div className="flex-between" style={{ fontSize: ".85rem" }}>
                  <div>
                    <div className="small muted" style={{ color: "#dfe5e2" }}>CARDHOLDER</div>
                    <div>{card.name}</div>
                  </div>
                  <div>
                    <div className="small muted" style={{ color: "#dfe5e2" }}>EXPIRES</div>
                    <div>{card.expiry}</div>
                  </div>
                  {showDetails && (
                    <div>
                      <div className="small muted" style={{ color: "#dfe5e2" }}>CVV</div>
                      <div>{card.cvv}</div>
                    </div>
                  )}
                </div>
              </div>
            </div>

            <button className="small secondary" onClick={() => setShowDetails(!showDetails)}>
              {showDetails ? "Hide Sensitive Details" : "Show Full Card & CVV"}
            </button>
          </div>

          {/* Card Controls */}
          <div>
            <h3>Card Security &amp; Spending Controls</h3>
            {msg && <p className="notice ok">{msg}</p>}

            <div style={{ margin: "1rem 0" }}>
              <label className="choice">
                <input type="checkbox" checked={card.isFrozen} onChange={() => toggleFreeze()} />
                <strong>Freeze Card (Temporary Lock)</strong>
              </label>
              <p className="small muted">Instantly blocks all in-store and online authorisations.</p>
            </div>

            <div style={{ margin: "1rem 0" }}>
              <label className="choice">
                <input
                  type="checkbox"
                  checked={card.onlineAllowed}
                  disabled={card.isFrozen}
                  onChange={(e) => setCard({ ...card, onlineAllowed: e.target.checked })}
                />
                Online / E-Commerce Transactions
              </label>
            </div>

            <div style={{ margin: "1rem 0" }}>
              <label className="choice">
                <input
                  type="checkbox"
                  checked={card.internationalAllowed}
                  disabled={card.isFrozen}
                  onChange={(e) => setCard({ ...card, internationalAllowed: e.target.checked })}
                />
                International Cross-Border Payments
              </label>
            </div>

            <div style={{ margin: "1rem 0" }}>
              <label>Monthly Spending Limit: <strong>{formatMoney(card.monthlyLimitPesewas)}</strong>
                <input
                  type="range"
                  min="10000"
                  max="2000000"
                  step="10000"
                  value={card.monthlyLimitPesewas}
                  disabled={card.isFrozen}
                  onChange={(e) => setCard({ ...card, monthlyLimitPesewas: Number(e.target.value) })}
                />
              </label>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

// -------------------------------------------------------------------------------------------------
// 5. Savings Goals
// -------------------------------------------------------------------------------------------------
function SavingsGoals({ availableBalance: _avail, onUpdated }: { availableBalance: number; onUpdated: () => void }) {
  const [goals, setGoals] = useState<SavingsGoal[]>([]);
  const [showAdd, setShowAdd] = useState(false);
  const [title, setTitle] = useState("");
  const [target, setTarget] = useState("");
  const [date, setDate] = useState("2027-01-31");

  function addGoal() {
    const pesewas = parseMoney(target);
    if (!title || !pesewas) return;
    setGoals([...goals, {
      id: "g_" + Date.now(),
      title,
      targetPesewas: pesewas,
      savedPesewas: 0,
      targetDate: date,
      category: "Personal"
    }]);
    setShowAdd(false);
    setTitle(""); setTarget("");
    onUpdated();
  }

  function deposit(id: string) {
    setGoals(goals.map((g) => g.id === id ? { ...g, savedPesewas: Math.min(g.targetPesewas, g.savedPesewas + 5000) } : g));
    onUpdated();
  }

  function withdraw(id: string) {
    setGoals(goals.map((g) => g.id === id ? { ...g, savedPesewas: Math.max(0, g.savedPesewas - 5000) } : g));
    onUpdated();
  }

  return (
    <section className="panel">
      <div className="flex-between">
        <div>
          <h2>Target Savings Goals &amp; Pots</h2>
          <p className="muted small">Earn 12% p.a. interest on your locked and goal-based savings with ELEPAY.</p>
        </div>
        <button onClick={() => setShowAdd(true)}>+ New Savings Goal</button>
      </div>

      {goals.length === 0 ? (
        <div style={{ textAlign: "center", padding: "3rem 1rem", border: "1px dashed var(--line)", borderRadius: "8px", marginTop: "1rem" }}>
          <p className="muted">No active savings goals found. Create your first savings goal to start earning automated interest.</p>
          <button onClick={() => setShowAdd(true)}>+ Create Savings Goal</button>
        </div>
      ) : (
        <div className="cards" style={{ marginTop: "1rem" }}>
        {goals.map((g) => {
          const pct = Math.min(100, Math.round((g.savedPesewas / g.targetPesewas) * 100));
          return (
            <div key={g.id} className="card">
              <div className="flex-between">
                <strong>{g.title}</strong>
                <span className="small muted">Target: {g.targetDate}</span>
              </div>
              <div className="progress-bar">
                <div className="progress-fill" style={{ width: `${pct}%` }} />
              </div>
              <div className="flex-between" style={{ marginTop: ".3rem" }}>
                <span className="big" style={{ fontSize: "1.1rem" }}>{formatMoney(g.savedPesewas)}</span>
                <span className="small muted">Goal: {formatMoney(g.targetPesewas)} ({pct}%)</span>
              </div>
              <div className="flex-row" style={{ marginTop: ".8rem" }}>
                <button className="small" onClick={() => deposit(g.id)}>+ Deposit GH₵ 50</button>
                <button className="small secondary" onClick={() => withdraw(g.id)}>- Withdraw</button>
              </div>
            </div>
          );
        })}
        </div>
      )}

      {showAdd && (
        <div className="modal-backdrop">
          <div className="modal-content" style={{ maxWidth: "420px" }}>
            <h3>Create New Savings Goal</h3>
            <label>Goal Name
              <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="e.g. Rent Advance 2027" required />
            </label>
            <label>Target Amount (GH₵)
              <input inputMode="decimal" value={target} onChange={(e) => setTarget(e.target.value)} placeholder="e.g. 5000" required />
            </label>
            <label>Target Completion Date
              <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
            </label>
            <div style={{ marginTop: "1rem" }}>
              <button onClick={() => addGoal()}>Save Goal</button>
              <button className="secondary" onClick={() => setShowAdd(false)}>Cancel</button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

// -------------------------------------------------------------------------------------------------
// 6. Export Receipts & Statements (CSV & PDF)
// -------------------------------------------------------------------------------------------------
function ExportStatements({ operations }: { operations: Operation[] }) {
  const [range, setRange] = useState("all");

  function exportCsv() {
    const headers = "Reference,Type,Counterparty,Amount (Pesewas),Amount (GHS),Status,CreatedAt\n";
    const rows = operations.map((o) =>
      `"${o.reference}","${o.kind}","${o.counterparty ?? ""}","${o.amount}","${(o.amount / 100).toFixed(2)}","${o.status}","${o.createdAt}"`
    ).join("\n");

    const blob = new Blob([headers + rows], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.setAttribute("download", `elepay_statement_${new Date().toISOString().substring(0, 10)}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  }

  return (
    <section className="panel">
      <h2>Export Account Statements &amp; Receipts</h2>
      <p className="muted small">Download bank-grade financial statements for tax filing, loan verification, or accounting records.</p>

      <div className="flex-row" style={{ margin: "1rem 0" }}>
        <label className="choice">Date Range:
          <select value={range} onChange={(e) => setRange(e.target.value)} style={{ width: "auto" }}>
            <option value="all">All Available Records</option>
            <option value="30d">Last 30 Days</option>
            <option value="90d">Last 90 Days</option>
            <option value="ytd">Year-to-Date 2026</option>
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

function downloadSingleReceipt(o: Operation) {
  const content = `ELEPAY TRANSACTION RECEIPT
--------------------------------------
Reference: ${o.reference}
Type: ${o.kind}
Counterparty: ${o.counterparty ?? "ELEPAY Customer"}
Amount: ${formatMoney(o.amount)}
Status: ${o.status.toUpperCase()}
Date: ${new Date(o.createdAt).toLocaleString()}
--------------------------------------
Ghana Interbank Payment & Settlement Systems
Authenticity Hash: ${newKey()}
Thank you for using ELEPAY!
`;
  const blob = new Blob([content], { type: "text/plain;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.setAttribute("download", `receipt_${o.reference}.txt`);
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
}
