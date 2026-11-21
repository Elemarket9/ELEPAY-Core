import { useCallback, useEffect, useState } from "react";
import { api, ApiError, newKey } from "./api";
import { getToken, signIn } from "./auth";
import { formatMoney, normalizeGhanaPhone, parseMoney } from "./money";

interface Line { merchant: string; kind: string; description: string | null; amount: number }
interface MethodTotal { method: string; fee: number; total: number }
interface PublicCheckout {
  reference: string; collector: string; description: string | null; status: string; lines: Line[];
  subtotal: number; methods: MethodTotal[]; expiresAt: string; paidAt: string | null;
}
interface PaymentStatus {
  reference: string; status: string; method: string | null; fee: number; total: number;
  railStatus: string | null; redirectUrl: string | null; message: string | null;
}

const NETWORKS = [["mtn", "MTN MoMo"], ["telecel", "Telecel Cash"], ["airteltigo", "AirtelTigo Money"]];

/**
 * Low-data checkout. The receipt reference in the URL is the only state the
 * customer needs: reopening the link after a dropped connection resumes the
 * same payment, and a retry reuses the stored idempotency key so it can never
 * start a second charge.
 */
export function Checkout({ reference }: { reference: string }) {
  const [checkout, setCheckout] = useState<PublicCheckout | null>(null);
  const [status, setStatus] = useState<PaymentStatus | null>(null);
  const [method, setMethod] = useState("momo");
  const [phone, setPhone] = useState("");
  const [network, setNetwork] = useState("mtn");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const keyName = `elepay.pay.${reference}`;

  const refresh = useCallback(async () => {
    try {
      const [c, s] = await Promise.all([
        api<PublicCheckout>(`/v1/public/checkouts/${reference}`, { anonymous: true }),
        api<PaymentStatus>(`/v1/public/checkouts/${reference}/status`, { anonymous: true }),
      ]);
      setCheckout(c);
      setStatus(s);
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "No connection. Your payment reference is " + reference + ".");
    }
  }, [reference]);

  useEffect(() => { void refresh(); }, [refresh]);
  useEffect(() => {
    if (status?.status !== "processing") return;
    const timer = setInterval(() => void refresh(), 4000);
    return () => clearInterval(timer);
  }, [status?.status, refresh]);

  const selected = checkout?.methods.find((m) => m.method === method);

  async function pay() {
    setBusy(true);
    setError(null);
    // One key per checkout attempt, kept across reloads so a resubmission is a replay.
    const key = localStorage.getItem(keyName) ?? newKey();
    localStorage.setItem(keyName, key);
    try {
      if (method === "wallet") {
        if (!(await getToken())) await signIn(prompt("Sign in (development subject)") ?? undefined);
        await api(`/v1/checkouts/${reference}/pay/wallet`, { method: "POST", idempotencyKey: key });
      } else {
        const msisdn = normalizeGhanaPhone(phone);
        if (method === "momo" && !msisdn) throw new ApiError(400, "invalid_phone", "Enter your MoMo number, e.g. 024 123 4567");
        const result = await api<PaymentStatus>(`/v1/public/checkouts/${reference}/pay`, {
          method: "POST", anonymous: true, idempotencyKey: key,
          body: method === "momo" ? { method, phone: msisdn, network } : { method },
        });
        if (result.redirectUrl) window.location.assign(result.redirectUrl);
      }
      await refresh();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && e.code === "idempotency_conflict") {
        localStorage.removeItem(keyName);
      }
      setError(e instanceof Error ? e.message : String(e));
      await refresh();
    } finally {
      setBusy(false);
    }
  }

  if (!checkout) {
    return <main className="narrow"><p>{error ?? "Loading…"}</p><p className="muted">Reference {reference}</p></main>;
  }

  return (
    <main className="narrow">
      <h1>Pay {checkout.collector}</h1>
      {checkout.description && <p className="muted">{checkout.description}</p>}
      <table className="lines">
        <tbody>
          {checkout.lines.map((l, i) => (
            <tr key={i}><td>{l.merchant}<div className="muted small">{l.kind === "delivery" ? "Delivery" : l.description}</div></td>
              <td className="num">{formatMoney(l.amount)}</td></tr>
          ))}
          <tr><td>Fee ({method})</td><td className="num">{formatMoney(selected?.fee ?? 0)}</td></tr>
          <tr className="total"><td>Total</td><td className="num">{formatMoney(selected?.total ?? checkout.subtotal)}</td></tr>
        </tbody>
      </table>

      {status?.status === "paid" ? (
        <div className="notice ok"><strong>Payment received.</strong> Receipt {checkout.reference}</div>
      ) : status?.status === "processing" ? (
        <div className="notice">
          <strong>Waiting for confirmation…</strong>
          <p>{status.message}</p>
          {status.redirectUrl && <p><a href={status.redirectUrl}>Continue to approve the payment</a></p>}
          <p className="small">You can close this page and reopen the same link. Reference {checkout.reference}.</p>
        </div>
      ) : checkout.status !== "open" ? (
        <div className="notice warn">This checkout is {checkout.status}.</div>
      ) : (
        <form onSubmit={(e) => { e.preventDefault(); void pay(); }}>
          <fieldset>
            <legend>Pay with</legend>
            {checkout.methods.map((m) => (
              <label key={m.method} className="choice">
                <input type="radio" name="method" checked={method === m.method} onChange={() => setMethod(m.method)} />
                {m.method === "momo" ? "Mobile money" : m.method === "card" ? "Card" : "ELEPAY wallet"} — {formatMoney(m.total)}
              </label>
            ))}
          </fieldset>
          {method === "momo" && (
            <>
              <label>MoMo number<input inputMode="tel" autoComplete="tel" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="024 123 4567" required /></label>
              <label>Network<select value={network} onChange={(e) => setNetwork(e.target.value)}>
                {NETWORKS.map(([v, n]) => <option key={v} value={v}>{n}</option>)}
              </select></label>
            </>
          )}
          <button disabled={busy} type="submit">{busy ? "Working…" : `Approve ${formatMoney(selected?.total ?? 0)}`}</button>
        </form>
      )}
      {error && <p className="error" role="alert">{error}</p>}
      <p className="small muted">Need help? Quote reference <strong>{checkout.reference}</strong> to ELEPAY support.</p>
    </main>
  );
}

export function StartFromLink({ slug, code }: { slug?: string; code?: string }) {
  const [info, setInfo] = useState<{ title?: string; merchant: string; amount?: number | string } | null>(null);
  const [amount, setAmount] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (slug) api<{ title: string; merchant: string; amount: number | string }>(`/v1/public/links/${slug}`, { anonymous: true })
      .then(setInfo).catch((e) => setError(e.message));
    else setInfo({ merchant: `Merchant code ${code}` });
  }, [slug, code]);

  async function start() {
    const pesewas = parseMoney(amount);
    try {
      if (!(info?.amount && info.amount !== "open") && !pesewas) throw new Error("Enter an amount, e.g. 10 or 12.50");
      const body = info?.amount && info.amount !== "open" ? {} : { amount: pesewas };
      const r = await api<{ reference: string }>(slug ? `/v1/public/links/${slug}/checkouts` : `/v1/public/merchant-codes/${code}/checkouts`,
        { method: "POST", anonymous: true, body });
      window.location.assign(`/pay/${r.reference}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  if (!info) return <main className="narrow"><p>{error ?? "Loading…"}</p></main>;
  const fixed = typeof info.amount === "number";
  return (
    <main className="narrow">
      <h1>{info.title ?? "Pay"}</h1>
      <p>{info.merchant}</p>
      {fixed ? <p className="big">{formatMoney(info.amount as number)}</p>
        : <label>Amount (GH₵)<input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></label>}
      <button onClick={() => void start()}>Continue</button>
      {error && <p className="error">{error}</p>}
    </main>
  );
}
