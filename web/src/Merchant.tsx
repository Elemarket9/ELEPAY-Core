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

const TABS = ["Overview", "Transactions", "Settlements", "Refunds", "Disputes", "Payouts", "Collect"] as const;

/** Merchant dashboard backed by the merchant APIs; the server enforces every scope shown here. */
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
