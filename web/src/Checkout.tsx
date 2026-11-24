import { useCallback, useEffect, useState } from "react";
import QRCode from "qrcode";
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

const NETWORKS = [["mtn", "MTN MoMo"], ["telecel", "Telecel Cash"], ["airteltigo", "AT Money"]];

export function safeRedirectUrl(url: string | null | undefined): string | null {
  if (!url) return null;
  try {
    const parsed = new URL(url);
    if (parsed.protocol === "https:" || parsed.protocol === "http:") {
      return parsed.toString();
    }
  } catch {
    return null;
  }
  return null;
}

export function formatSplitReceipt(checkout: PublicCheckout, fee: number, total: number): string {
  const linesText = checkout.lines.map((l) =>
    `  • ${l.merchant}: ${l.kind} (${l.description ?? "Item"}) - ${formatMoney(l.amount)}`
  ).join("\n");

  return `ELEPAY SPLIT-ORDER RECEIPT
===================================================
Receipt Reference: ${checkout.reference}
Collector: ${checkout.collector}
Date: ${checkout.paidAt ? new Date(checkout.paidAt).toLocaleString() : new Date().toLocaleString()}
Status: ${checkout.status.toUpperCase()}

ITEMIZED ALLOCATIONS:
${linesText}

Subtotal: ${formatMoney(checkout.subtotal)}
Processing Fee: ${formatMoney(fee)}
Total Paid: ${formatMoney(total)}

Authorized by Bank of Ghana National Payment Systems
===================================================
Thank you for your payment!
`;
}

/**
 * Universal Checkout with QR code presentation and itemized split-order receipts.
 */
export function Checkout({ reference }: { reference: string }) {
  const [checkout, setCheckout] = useState<PublicCheckout | null>(null);
  const [status, setStatus] = useState<PaymentStatus | null>(null);
  const [method, setMethod] = useState("momo");
  const [phone, setPhone] = useState("");
  const [network, setNetwork] = useState("mtn");
  const [showQr, setShowQr] = useState(false);
  const [qrCodeUrl, setQrCodeUrl] = useState<string | null>(null);
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

  // Static payment-link QR generator
  useEffect(() => {
    if (showQr) {
      const payLink = window.location.href;
      QRCode.toDataURL(payLink, { margin: 1, width: 220 })
        .then(setQrCodeUrl)
        .catch(() => setQrCodeUrl(null));
    }
  }, [showQr]);

  const selected = checkout?.methods.find((m) => m.method === method);

  async function pay() {
    setBusy(true);
    setError(null);
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
        const safeUrl = safeRedirectUrl(result.redirectUrl);
        if (safeUrl) window.location.assign(safeUrl);
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

  function downloadSplitReceipt() {
    if (!checkout) return;
    const content = formatSplitReceipt(checkout, selected?.fee ?? 0, selected?.total ?? checkout.subtotal);
    const blob = new Blob([content], { type: "text/plain;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.setAttribute("download", `receipt_${checkout.reference}.txt`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  }

  if (!checkout) {
    return <main className="narrow"><p>{error ?? "Loading…"}</p><p className="muted">Reference {reference}</p></main>;
  }

  const safeStatusRedirect = safeRedirectUrl(status?.redirectUrl);

  return (
    <main className="narrow">
      <div className="flex-between">
        <h1>Pay {checkout.collector}</h1>
      </div>

      {checkout.description && <p className="muted">{checkout.description}</p>}

      {/* Itemized Split-Order Breakdown */}
      <table className="lines">
        <tbody>
          {checkout.lines.map((l, i) => (
            <tr key={i}>
              <td>
                <strong>{l.merchant}</strong>
                <div className="muted small">{l.kind === "delivery" ? "Delivery / Freight" : l.description}</div>
              </td>
              <td className="num">
                {formatMoney(l.amount)}
              </td>
            </tr>
          ))}
          <tr>
            <td>Fee ({method})</td>
            <td className="num">{formatMoney(selected?.fee ?? 0)}</td>
          </tr>
          <tr className="total">
            <td>Total</td>
            <td className="num">
              <div>{formatMoney(selected?.total ?? checkout.subtotal)}</div>
            </td>
          </tr>
        </tbody>
      </table>

      {/* QR Toggle */}
      <div style={{ margin: "1rem 0" }}>
        <button className="small secondary" onClick={() => setShowQr(!showQr)}>
          {showQr ? "📱 Pay on this device" : "📷 Scan QR code to pay"}
        </button>
      </div>

      {showQr && (
        <div style={{ textAlign: "center", background: "#fff", padding: "1.2rem", borderRadius: "8px", border: "1px solid var(--line)", margin: "1rem 0" }}>
          <h3>Scan to Pay with ELEPAY / GhQR</h3>
          {qrCodeUrl ? (
            <img src={qrCodeUrl} alt="Payment QR Code" width={220} height={220} style={{ margin: ".5rem auto" }} />
          ) : (
            <p>Generating QR…</p>
          )}
          <p className="small muted">Scan with ELEPAY Wallet, MTN MoMo App, or any GhQR banking app.</p>
        </div>
      )}

      {status?.status === "paid" ? (
        <div className="notice ok">
          <strong>Payment received.</strong> Receipt {checkout.reference}
          <div style={{ marginTop: ".8rem" }}>
            <button className="small" onClick={() => downloadSplitReceipt()}>
              📥 Download Itemized Receipt
            </button>
          </div>
        </div>
      ) : status?.status === "processing" ? (
        <div className="notice">
          <strong>Waiting for confirmation…</strong>
          <p>{status.message}</p>
          {safeStatusRedirect && <p><a href={safeStatusRedirect}>Continue to approve the payment</a></p>}
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
