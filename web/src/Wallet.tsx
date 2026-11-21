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

/**
 * Customer web wallet. Sending by phone looks up the complete number once,
 * shows only the verified name, and confirms with the server-bound
 * confirmation id; editing the number discards the confirmation.
 */
export function Wallet() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [operations, setOperations] = useState<Operation[]>([]);
  const [error, setError] = useState<string | null>(null);

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
      <SendMoney onSent={() => void load()} />
      <h2>Activity</h2>
      <table className="grid">
        <thead><tr><th>Reference</th><th>Type</th><th>Counterparty</th><th className="num">Amount</th><th>Status</th></tr></thead>
        <tbody>{operations.map((o) => (
          <tr key={o.reference}><td>{o.reference}</td><td>{o.kind}</td><td>{o.counterparty}</td>
            <td className="num">{formatMoney(o.amount)}</td><td>{o.status}</td></tr>
        ))}</tbody>
      </table>
    </main>
  );
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

  function edit(next: string) {
    // Any edit to the destination invalidates the previous confirmation.
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
