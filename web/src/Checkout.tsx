import { useCallback, useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { api, ApiError, newKey } from "./api";
import { devSignInEnabled, getToken, oidcEnabled, signIn } from "./auth";
import { formatMoney, normalizeGhanaPhone, parseMoney } from "./money";

interface Line { merchant: string; kind: string; description: string | null; amount: number }
interface MethodTotal { method: string; fee: number; total: number }
interface PublicCheckout {
  reference: string; collector: string; description: string | null; status: string; lines: Line[];
  subtotal: number; methods: MethodTotal[]; expiresAt: string; paidAt: string | null;
}
interface PaymentStatus {
  reference: string; status: string; method: string | null; fee: number; total: number;
  railStatus: string | null; redirectUrl: string | null; message: string | null; attemptId?: string | null;
  subtotal?: number;
}

const NETWORKS = [["mtn", "MTN MoMo"], ["telecel", "Telecel Cash"], ["airteltigo", "AT Money"]];
type PaymentMethod = "wallet" | "momo" | "card" | "ghqr";
export interface CheckoutAttempt { key: string; method: PaymentMethod; previousAttemptId: string | null; phone?: string; network?: string; }
const KNOWN_METHODS = new Set<string>(["wallet", "momo", "card", "ghqr"]);

export function decodeCheckoutAttempt(raw: string | null): CheckoutAttempt | null {
  if (raw === null) return null;
  const v = JSON.parse(raw) as Partial<CheckoutAttempt> | null;
  if (!v || typeof v.key !== "string" || !v.key || v.key.length > 200 || typeof v.method !== "string" || !KNOWN_METHODS.has(v.method) || (v.previousAttemptId !== null && typeof v.previousAttemptId !== "string")) throw new Error("Invalid saved checkout attempt");
  if (v.method === "momo") {
    if (typeof v.phone !== "string" || normalizeGhanaPhone(v.phone) !== v.phone || !NETWORKS.some(n => n[0] === v.network)) throw new Error("Invalid saved payment details");
    return { key: v.key, method: v.method, previousAttemptId: v.previousAttemptId, phone: v.phone, network: v.network };
  }
  return { key: v.key, method: v.method, previousAttemptId: v.previousAttemptId };
}
export function checkoutPhase(status: Pick<PaymentStatus, "status" | "railStatus"> | null): "paid" | "waiting" | "open" | "closed" | "unknown" {
  if (!status) return "unknown";
  if (status.status === "paid") return "paid";
  if (status.status === "processing" || ["created", "pending", "unknown", "succeeded"].includes(status.railStatus ?? "")) return "waiting";
  if (status.status === "open") return "open";
  if (["expired", "cancelled", "canceled", "closed"].includes(status.status)) return "closed";
  return "unknown";
}
export function checkoutAttemptResolved(attempt: CheckoutAttempt, status: PaymentStatus): boolean {
  const phase = checkoutPhase(status);
  return phase === "paid" || phase === "closed" ||
    (phase === "open" && Boolean(status.attemptId) && status.attemptId !== attempt.previousAttemptId && status.method === attempt.method && status.railStatus === "failed");
}
export function checkoutExpired(expiresAt: string, now = Date.now()): boolean {
  const expiry = Date.parse(expiresAt);
  return !Number.isFinite(expiry) || expiry <= now;
}
export function validCheckoutTotal(subtotal: number, fee: number, total: number): boolean {
  return [subtotal, fee, total].every(Number.isSafeInteger) && subtotal > 0 && fee >= 0 && total === subtotal + fee && Number.isSafeInteger(subtotal + fee);
}
function requestSignal(): AbortSignal { return AbortSignal.timeout(20000); }

export function resolveInitialMethod(methods: Pick<MethodTotal, "method">[], currentMethod?: string): string {
  if (currentMethod && methods.some((m) => m.method === currentMethod)) {
    return currentMethod;
  }
  return methods[0]?.method ?? "momo";
}

export function checkoutMethodLabel(method: string): string {
  switch (method) {
    case "momo": return "Mobile money";
    case "card": return "Card";
    case "ghqr": return "GhQR";
    case "wallet": return "ELEPAY wallet";
    default: return method;
  }
}

export function safeRedirectUrl(url: string | null | undefined): string | null {
  if (!url) return null;
  try {
    const parsed = new URL(url);
    if (parsed.username || parsed.password) return null;
    if (parsed.protocol === "https:" || (parsed.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(parsed.hostname) && import.meta.env.DEV)) {
      return parsed.toString();
    }
  } catch {
    return null;
  }
  return null;
}

export function formatSplitReceipt(checkout: PublicCheckout, fee: number, total: number): string {
  if (checkout.status !== "paid" || !validCheckoutTotal(checkout.subtotal, fee, total)) throw new Error("Payment is not confirmed or totals are invalid.");
  const linesText = checkout.lines.map((l) =>
    `  • ${l.merchant}: ${l.kind} (${l.description ?? "Item"}) - ${formatMoney(l.amount)}`
  ).join("\n");

  return `ELEPAY SPLIT-ORDER RECEIPT
===================================================
Receipt Reference: ${checkout.reference}
Collector: ${checkout.collector}
Date: ${checkout.paidAt ? new Date(checkout.paidAt).toLocaleString("en-GH", { timeZone: "Africa/Accra" }) + " GMT" : "Not supplied"}
Status: ${checkout.status.toUpperCase()}

ITEMIZED ALLOCATIONS:
${linesText}

Subtotal: ${formatMoney(checkout.subtotal)}
Processing Fee: ${formatMoney(fee)}
Total Paid: ${formatMoney(total)}

Payment status and amounts are supplied by ELEPAY. This is not a bank settlement certificate.
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
  const [method, setMethod] = useState("");
  const [phone, setPhone] = useState("");
  const [network, setNetwork] = useState("mtn");
  const [showQr, setShowQr] = useState(false);
  const [qrCodeUrl, setQrCodeUrl] = useState<string | null>(null);
  const [qrError, setQrError] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [lastChecked, setLastChecked] = useState<number | null>(null);
  const [now, setNow] = useState(Date.now);
  const keyName = "elepay.checkout.attempt." + reference;
  const [recovery, setRecovery] = useState<{ attempt: CheckoutAttempt | null; blocked: boolean }>(() => {
    try { return { attempt: decodeCheckoutAttempt(sessionStorage.getItem(keyName)), blocked: false }; }
    catch { return { attempt: null, blocked: true }; }
  });
  const attemptRef = useRef(recovery.attempt);
  const inFlight = useRef(false);
  const refreshPromise = useRef<Promise<{ checkout: PublicCheckout; status: PaymentStatus } | null> | null>(null);
  const mounted = useRef(true);

  const clearAttempt = useCallback(() => {
    try {
      sessionStorage.removeItem(keyName);
      attemptRef.current = null;
      setRecovery({ attempt: null, blocked: false });
    } catch { setRecovery({ attempt: attemptRef.current, blocked: true }); }
  }, [keyName]);

  const refresh = useCallback(() => {
    if (refreshPromise.current) return refreshPromise.current;
    setRefreshing(true);
    const pending = (async () => {
      try {
        const [c, s] = await Promise.all([
          api<PublicCheckout>("/v1/public/checkouts/" + reference, { anonymous: true, signal: requestSignal() }),
          api<PaymentStatus>("/v1/public/checkouts/" + reference + "/status", { anonymous: true, signal: requestSignal() }),
        ]);
        if (!c || !s || c.reference !== reference || s.reference !== reference || !Array.isArray(c.methods) || !Array.isArray(c.lines) || !Number.isSafeInteger(c.subtotal) || c.subtotal <= 0) throw new Error("Invalid checkout response");
        if (!mounted.current) return null;
        setCheckout(c); setStatus(s); setLastChecked(Date.now()); setStatusError(null);
        setMethod(prev => resolveInitialMethod(c.methods.filter(m => KNOWN_METHODS.has(m.method) && validCheckoutTotal(c.subtotal, m.fee, m.total)), prev));
        if (attemptRef.current && checkoutAttemptResolved(attemptRef.current, s) && c.status === s.status) clearAttempt();
        return { checkout: c, status: s };
      } catch (e) {
        if (mounted.current) setStatusError(e instanceof ApiError ? e.message : "Could not verify the current payment status. Check your connection and try again.");
        return null;
      } finally { refreshPromise.current = null; if (mounted.current) setRefreshing(false); }
    })();
    refreshPromise.current = pending;
    return pending;
  }, [reference, clearAttempt]);

  useEffect(() => {
    mounted.current = true; void refresh();
    return () => { mounted.current = false; };
  }, [refresh]);
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  const phase = checkoutPhase(status);
  useEffect(() => {
    if (phase !== "waiting" && !recovery.attempt) return;
    const timer = setInterval(() => { if (document.visibilityState === "visible" && !inFlight.current) void refresh(); }, 5000);
    const resume = () => { if (document.visibilityState === "visible" && !inFlight.current) void refresh(); };
    document.addEventListener("visibilitychange", resume);
    window.addEventListener("online", resume);
    return () => { clearInterval(timer); document.removeEventListener("visibilitychange", resume); window.removeEventListener("online", resume); };
  }, [phase, recovery.attempt, refresh]);
  useEffect(() => {
    if (!showQr) return;
    let active = true;
    setQrError(false); setQrCodeUrl(null);
    QRCode.toDataURL(window.location.origin + "/pay/" + reference, { margin: 2, width: 220 })
      .then(url => { if (active) setQrCodeUrl(url); })
      .catch(() => { if (active) setQrError(true); });
    return () => { active = false; };
  }, [showQr, reference]);

  const methods = checkout?.methods.filter(m => KNOWN_METHODS.has(m.method) && validCheckoutTotal(checkout.subtotal, m.fee, m.total)) ?? [];
  const selected = methods.find(m => m.method === method);
  const consistent = checkout?.status === status?.status;
  const expired = checkout ? checkoutExpired(checkout.expiresAt, now) : true;

  async function submit(retry?: CheckoutAttempt) {
    if (inFlight.current || recovery.blocked || (!retry && (attemptRef.current || !selected))) return;
    if (!retry && method === "momo" && !normalizeGhanaPhone(phone)) { setError("Enter a valid Ghana mobile money number."); return; }
    inFlight.current = true; setBusy(true); setError(null);
    try {
      const fresh = await refresh();
      if (!fresh || checkoutPhase(fresh.status) !== "open" || fresh.checkout.status !== "open" || checkoutExpired(fresh.checkout.expiresAt)) {
        setError("A new payment cannot be submitted at the current status. Review the latest payment information."); return;
      }
      if (retry && attemptRef.current?.key !== retry.key) { setError("The original attempt has resolved. Review the updated checkout before paying again."); return; }
      const payMethod = retry?.method ?? method as PaymentMethod;
      if (!KNOWN_METHODS.has(payMethod)) return;
      const quote = fresh.checkout.methods.find(m => m.method === payMethod);
      if (!retry && (!quote || !selected || !validCheckoutTotal(fresh.checkout.subtotal, quote.fee, quote.total) || quote.total !== selected.total || quote.fee !== selected.fee || fresh.checkout.subtotal !== checkout?.subtotal)) {
        setError("Payment details changed. Review the updated amount and confirm again."); return;
      }
      if (payMethod === "wallet") {
        // Wallet retries after navigation are not automatic: the authenticated customer may have changed.
        if (retry) { setError("Check the original wallet payment status and contact support if unresolved. Do not submit from a different wallet."); return; }
        if (!(await getToken())) {
          if (oidcEnabled) { await signIn(); return; }
          if (devSignInEnabled) { const subject = prompt("Development sign-in subject"); if (!subject) return; await signIn(subject); }
          else { setError("Wallet sign-in is unavailable. Choose another payment method."); return; }
        }
      }
      const attempt: CheckoutAttempt = retry ?? { key: newKey(), method: payMethod, previousAttemptId: fresh.status.attemptId ?? null, ...(payMethod === "momo" ? { phone: normalizeGhanaPhone(phone)!, network } : {}) };
      // Tab-scoped recovery: save exact retry details only while the outcome is uncertain.
      try { sessionStorage.setItem(keyName, JSON.stringify(attempt)); }
      catch { setError("Payment recovery storage is unavailable. No payment was submitted."); return; }
      attemptRef.current = attempt; setRecovery({ attempt, blocked: false });
      const response = await api<PaymentStatus>(
        payMethod === "wallet" ? "/v1/checkouts/" + reference + "/pay/wallet" : "/v1/public/checkouts/" + reference + "/pay",
        { method: "POST", anonymous: payMethod !== "wallet", idempotencyKey: attempt.key, signal: requestSignal(),
          ...(payMethod === "wallet" ? {} : { body: payMethod === "momo" ? { method: payMethod, phone: attempt.phone, network: attempt.network } : { method: payMethod } }) });
      if (!response || response.reference !== reference) throw new Error("Payment response was not verified");
      // Only server-provided status is displayed; never infer success from an HTTP response.
      setStatus(response);
      await refresh();
    } catch (e) {
      if (attemptRef.current && e instanceof ApiError && [400, 401, 403, 422].includes(e.status) && ["invalid_phone", "invalid_network", "payer_mismatch", "validation_failed", "rail_unavailable", "rail_operation_unavailable"].includes(e.code)) {
        clearAttempt();
        setError(e.message);
      } else {
        setError(attemptRef.current ? "Payment outcome is not confirmed. Check status before trying again. " + (e instanceof ApiError ? e.message : "") : e instanceof Error ? e.message : "Payment could not be started.");
      }
      await refresh();
    } finally { inFlight.current = false; setBusy(false); }
  }

  function downloadSplitReceipt() {
    if (!checkout || !status || status.status !== "paid" || checkout.status !== "paid" || !validCheckoutTotal(checkout.subtotal, status.fee, status.total)) return;
    const content = formatSplitReceipt(checkout, status.fee, status.total);
    const url = URL.createObjectURL(new Blob([content], { type: "text/plain;charset=utf-8;" }));
    const link = document.createElement("a"); link.href = url; link.download = "receipt_" + checkout.reference + ".txt";
    document.body.appendChild(link); link.click(); link.remove(); URL.revokeObjectURL(url);
  }
  const [copied, setCopied] = useState(false);
  async function copyReference() {
    try { await navigator.clipboard.writeText(reference); setCopied(true); } catch { setCopied(false); }
  }
  if (!checkout) return <main className="narrow checkout-page"><section className="panel"><p className="eyebrow">ELEPAY CHECKOUT</p><h1>{statusError ? "Payment details unavailable" : "Loading checkout"}</h1><p role={statusError ? "alert" : "status"}>{statusError ?? "Retrieving merchant and payment details…"}</p><p className="mono small">{reference}</p><button disabled={refreshing} onClick={() => void refresh()}>Check again</button></section></main>;

  const actual = phase === "paid" || phase === "waiting";
  const totals = actual && status && validCheckoutTotal(checkout.subtotal, status.fee, status.total) ? status : !actual ? selected : undefined;
  const redirect = phase === "waiting" && consistent && !statusError ? safeRedirectUrl(status?.redirectUrl) : null;
  const paid = phase === "paid" && consistent && Boolean(totals) && !statusError;
  const seconds = Math.max(0, Math.ceil((Date.parse(checkout.expiresAt) - now) / 1000));
  const canPay = phase === "open" && consistent && !expired && !statusError && !recovery.attempt && !recovery.blocked;
  return <main className="checkout-page checkout-workspace">
    <div className="checkout-top"><a className="brand" href="/">ELEPAY</a><span className="small muted">Payment checkout</span></div>
    <div className="checkout-layout">
      <section className="panel checkout-summary">
        <p className="eyebrow">PAYMENT SUMMARY</p><h1>Pay {checkout.collector}</h1>
        {checkout.description && <p className="muted">{checkout.description}</p>}
        <div className="checkout-total"><span className="small">{paid ? "Total paid" : actual ? "Payment total" : "Total to pay"}</span><strong>{totals ? formatMoney(totals.total) : "—"}</strong><span className="small muted">Ghana cedis (GHS)</span></div>
        <h2>Order breakdown</h2>
        <table className="lines"><tbody>{checkout.lines.map((l,i) => <tr key={i}><td><strong>{l.merchant}</strong><div className="muted small">{l.description ?? l.kind}</div></td><td className="num">{formatMoney(l.amount)}</td></tr>)}
        <tr><td>Subtotal</td><td className="num">{formatMoney(checkout.subtotal)}</td></tr>
        <tr><td>Fee{totals ? " · " + checkoutMethodLabel(actual ? status?.method ?? "payment" : method) : ""}</td><td className="num">{totals ? formatMoney(totals.fee) : "—"}</td></tr>
        <tr className="total"><td>Total</td><td className="num">{totals ? formatMoney(totals.total) : "—"}</td></tr></tbody></table>
        <div className="checkout-reference"><span className="small muted">Payment reference</span><span className="mono">{reference}</span><button className="secondary small" onClick={() => void copyReference()}>{copied ? "Copied" : "Copy reference"}</button></div>
      </section>
      <section className="panel checkout-action" aria-busy={busy}>
        <p className="eyebrow">PAYMENT STATUS</p>
        <div className="checkout-status-line"><span className={"tag " + (paid ? "active" : phase === "closed" ? "failed" : "pending")}>{paid ? "Paid" : phase === "waiting" ? "Awaiting confirmation" : phase === "open" ? "Open" : phase === "closed" ? "Closed" : "Checking"}</span><button className="secondary small" disabled={refreshing || busy} onClick={() => void refresh()}>{refreshing ? "Checking…" : "Refresh status"}</button></div>
        <p className="small muted">{lastChecked ? "Last checked " + new Date(lastChecked).toLocaleTimeString("en-GH", {timeZone:"Africa/Accra"}) + " GMT" : "Status not yet verified"}</p>
        {statusError && <p className="notice warn" role="alert">{statusError} Do not pay again until the status is verified.</p>}
        {error && <p className="notice warn" role="alert">{error}</p>}
        {paid ? <div className="notice ok" role="status"><h2>Payment received</h2><p>Keep this reference for your records.</p><button onClick={downloadSplitReceipt}>Download itemized receipt</button></div>
        : phase === "waiting" || phase === "paid" || !consistent ? <div className="notice" role="status"><h2>Confirming payment</h2><p>{status?.message ?? "Checking the authoritative payment status."}</p><p>Do not start another payment. You can reopen this link to check progress.</p>{redirect && <p><a className="action-link" href={redirect}>Continue to payment provider</a></p>}</div>
        : phase === "closed" || expired ? <div className="notice warn" role="status"><h2>Checkout is no longer payable</h2><p>Ask the merchant for a new payment link if you still need to pay.</p></div>
        : recovery.blocked ? <p className="notice warn" role="alert">Payment recovery data is unavailable. Contact support with the reference before paying again.</p>
        : recovery.attempt ? <div className="notice warn" role="status"><h2>Previous attempt not confirmed</h2><p>Check status first. Retrying uses the original key and details, never a new payment request.</p>{recovery.attempt.method !== "wallet" && <button disabled={busy || refreshing || Boolean(statusError) || !consistent} onClick={() => recovery.attempt && void submit(recovery.attempt)}>Retry original attempt</button>}{recovery.attempt.method === "wallet" && <p>For a wallet payment, contact support if the status remains unresolved.</p>}</div>
        : canPay ? <form onSubmit={e => { e.preventDefault(); void submit(); }}>
          <h2>Choose how to pay</h2>
          <p className="small muted">{Number.isFinite(seconds) ? "Link expires in " + Math.floor(seconds/60) + ":" + String(seconds%60).padStart(2,"0") : "Expiry unavailable"}</p>
          <fieldset disabled={busy} className="checkout-methods"><legend>Available payment methods</legend>
          {methods.map(m => <label className="checkout-method" key={m.method}><input type="radio" name="method" checked={method === m.method} onChange={() => setMethod(m.method)} /><span><strong>{checkoutMethodLabel(m.method)}</strong><span className="small muted">Fee {formatMoney(m.fee)}</span></span><strong>{formatMoney(m.total)}</strong></label>)}</fieldset>
          {method === "momo" && <><label>Mobile money number<input type="tel" inputMode="tel" autoComplete="tel" disabled={busy} required value={phone} placeholder="024 123 4567" onChange={e => setPhone(e.target.value)} /></label><label>Network<select disabled={busy} value={network} onChange={e => setNetwork(e.target.value)}>{NETWORKS.map(([v,n]) => <option key={v} value={v}>{n}</option>)}</select></label><p className="small muted">Approve only the correct amount on your phone. Never enter your mobile money PIN on this website.</p></>}
          {method === "wallet" && <p className="small muted">Sign in to your ELEPAY wallet, then review and approve the payment.</p>}
          {method === "card" && <p className="small muted">Follow the payment provider’s approval instructions. Do not share your PIN or one-time code here.</p>}
          {!methods.length && <p className="notice warn">No payment method is available for this checkout.</p>}
          <button className="primary-payment" type="submit" disabled={busy || refreshing || !selected}>{busy ? "Checking payment…" : selected ? "Approve " + formatMoney(selected.total) : "Payment unavailable"}</button>
        </form> : <p className="notice warn">Payment is unavailable until its status is verified.</p>}
        {phase === "open" && consistent && !expired && !recovery.attempt && !statusError && <div className="checkout-qr"><button className="secondary small" aria-expanded={showQr} onClick={() => setShowQr(!showQr)}>{showQr ? "Hide QR code" : "Open on another device"}</button>{showQr && <div className="checkout-qr-content">{qrCodeUrl ? <img src={qrCodeUrl} alt="QR code for this payment link" width={220} height={220} /> : <p role="status">{qrError ? "QR code unavailable." : "Generating payment-link QR…"}</p>}<p className="small muted">Scan using a camera or web-link QR reader. This is not a GhQR network payment code.</p></div>}</div>}
        <p className="small muted">For support, quote your payment reference. Never share a PIN or one-time code.</p>
      </section>
    </div>
  </main>;
}

export function StartFromLink({ slug, code }: { slug?: string; code?: string }) {
  const [info, setInfo] = useState<{ title?: string; merchant: string; amount?: number | string } | null>(null);
  const [amount, setAmount] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const startLock = useRef(false);

  useEffect(() => {
    if (slug) api<{ title: string; merchant: string; amount: number | string }>(`/v1/public/links/${slug}`, { anonymous: true })
      .then(setInfo).catch((e) => setError(e.message));
    else setInfo({ merchant: `Merchant code ${code}` });
  }, [slug, code]);

  async function start() {
    if (startLock.current || !info) return;
    const pesewas = parseMoney(amount);
    startLock.current = true;
    setBusy(true);
    setError(null);
    try {
      if (!(typeof info.amount === "number" && Number.isSafeInteger(info.amount) && info.amount > 0) && (!pesewas || pesewas <= 0 || !Number.isSafeInteger(pesewas))) throw new Error("Enter an amount, e.g. 10 or 12.50");
      const body = typeof info.amount === "number" ? {} : { amount: pesewas };
      const r = await api<{ reference: string }>(slug ? `/v1/public/links/${slug}/checkouts` : `/v1/public/merchant-codes/${code}/checkouts`,
        { method: "POST", anonymous: true, body, signal: requestSignal() });
      if (!r || !/^[\\w-]+$/.test(r.reference)) throw new Error("Could not verify checkout reference");
      window.location.assign(`/pay/${r.reference}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      startLock.current = false; setBusy(false);
    }
  }

  if (!info) return <main className="narrow"><p>{error ?? "Loading…"}</p></main>;
  const fixed = typeof info.amount === "number";
  return (
    <main className="narrow checkout-page"><section className="panel">
      <p className="eyebrow">ELEPAY PAYMENT LINK</p><h1>{info.title ?? "Pay a merchant"}</h1>
      <p>{info.merchant}</p>{code && <p className="muted small">Confirm the merchant name on the next page before approving payment.</p>}
      {fixed ? <p className="big">{formatMoney(info.amount as number)}</p>
        : <label>Amount (GH₵)<input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></label>}
      <button disabled={busy} onClick={() => void start()}>{busy ? "Starting…" : "Continue"}</button>
      {error && <p className="error" role="alert">{error}</p>}
    </section></main>
  );
}
