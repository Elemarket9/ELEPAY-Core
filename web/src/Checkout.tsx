import { useCallback, useEffect, useState } from "react";
import QRCode from "qrcode";
import { api, ApiError, newKey } from "./api";
import { getToken, oidcEnabled, signIn } from "./auth";
import { formatMoney, normalizeGhanaPhone, parseMoney } from "./money";
import {
  BrandMark,
  IconAlertTriangle,
  IconCheckCircle,
  IconCreditCard,
  IconDownload,
  IconExternalLink,
  IconLock,
  IconQrCode,
  IconSmartphone,
  IconWallet,
} from "./ui/Icons";

interface Line { merchant: string; kind: string; description: string | null; amount: number }
interface MethodTotal { method: string; fee: number; total: number }
interface PublicCheckout {
  reference: string; collector: string; description: string | null; status: string; lines: Line[];
  subtotal: number; methods: MethodTotal[]; expiresAt: string; paidAt: string | null;
}
interface PaymentStatus {
  reference: string; status: string; method: string | null; fee: number; total: number;
  railStatus: string | null; redirectUrl: string | null; message: string | null;
  qrPayload?: string | null;
}

const NETWORKS = [["mtn", "MTN MoMo"], ["telecel", "Telecel Cash"], ["airteltigo", "AT Money"]] as const;

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

function methodIcon(method: string) {
  switch (method) {
    case "momo": return <IconSmartphone size={17} />;
    case "card": return <IconCreditCard size={17} />;
    case "ghqr": return <IconQrCode size={17} />;
    case "wallet": return <IconWallet size={17} />;
    default: return <IconCreditCard size={17} />;
  }
}

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
  const [method, setMethod] = useState("");
  const [phone, setPhone] = useState("");
  const [network, setNetwork] = useState("mtn");
  const [showQr, setShowQr] = useState(false);
  const [qrCodeUrl, setQrCodeUrl] = useState<string | null>(null);
  const [railQrPayload, setRailQrPayload] = useState<string | null>(null);
  const [railQrImage, setRailQrImage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const keyName = `elepay.pay.${reference}`;

  const clearIdempotencyKey = useCallback(() => {
    try { localStorage.removeItem(keyName); } catch { /* ignore */ }
  }, [keyName]);

  const refresh = useCallback(async (preserveError = false) => {
    try {
      const [c, s] = await Promise.all([
        api<PublicCheckout>(`/v1/public/checkouts/${reference}`, { anonymous: true }),
        api<PaymentStatus>(`/v1/public/checkouts/${reference}/status`, { anonymous: true }),
      ]);
      setCheckout(c);
      setMethod((prev) => resolveInitialMethod(c.methods, prev));
      setStatus(s);
      if (s.qrPayload) setRailQrPayload(s.qrPayload);
      if (s.status === "paid" || s.railStatus === "failed") {
        clearIdempotencyKey();
      }
      if (!preserveError) setError(null);
    } catch (e) {
      if (!preserveError) {
        setError(e instanceof ApiError ? e.message : "No connection. Your payment reference is " + reference + ".");
      }
    }
  }, [reference, clearIdempotencyKey]);

  useEffect(() => { void refresh(); }, [refresh]);

  useEffect(() => {
    if (status?.status !== "processing") return;
    const timer = setInterval(() => void refresh(), 4000);
    return () => clearInterval(timer);
  }, [status?.status, refresh]);

  // Browser URL QR for opening checkout on another device
  useEffect(() => {
    if (showQr) {
      const payLink = window.location.href;
      QRCode.toDataURL(payLink, { margin: 1, width: 220 })
        .then(setQrCodeUrl)
        .catch(() => setQrCodeUrl(null));
    }
  }, [showQr]);

  // Dynamic rail GhQR payload image during processing
  const activeQrPayload = status?.qrPayload ?? railQrPayload;
  useEffect(() => {
    if (status?.status === "processing" && activeQrPayload) {
      QRCode.toDataURL(activeQrPayload, { margin: 1, width: 220 })
        .then(setRailQrImage)
        .catch(() => setRailQrImage(null));
    } else {
      setRailQrImage(null);
    }
  }, [status?.status, activeQrPayload]);

  const selected = checkout?.methods.find((m) => m.method === method);
  const isSettledOrProcessing = status?.status === "paid" || status?.status === "processing";
  const displayMethod = isSettledOrProcessing && status?.method ? status.method : method;
  const displayFee = isSettledOrProcessing ? status.fee : (selected?.fee ?? 0);
  const displayTotal = isSettledOrProcessing ? status.total : (selected?.total ?? checkout?.subtotal ?? 0);

  async function pay() {
    setBusy(true);
    setError(null);
    try {
      let key: string;
      try {
        key = localStorage.getItem(keyName) ?? newKey();
        localStorage.setItem(keyName, key);
      } catch {
        key = newKey();
      }
      if (method === "wallet") {
        if (!(await getToken())) {
          if (oidcEnabled) {
            await signIn();
            return;
          } else {
            const subject = prompt("Sign in (development subject)");
            if (!subject || !subject.trim()) {
              return;
            }
            await signIn(subject.trim());
          }
        }
        await api(`/v1/checkouts/${reference}/pay/wallet`, { method: "POST", idempotencyKey: key });
      } else {
        const msisdn = normalizeGhanaPhone(phone);
        if (method === "momo" && !msisdn) throw new ApiError(400, "invalid_phone", "Enter your MoMo number, e.g. 024 123 4567");
        const result = await api<PaymentStatus>(`/v1/public/checkouts/${reference}/pay`, {
          method: "POST", anonymous: true, idempotencyKey: key,
          body: method === "momo" ? { method, phone: msisdn, network } : { method },
        });
        if (result.qrPayload) setRailQrPayload(result.qrPayload);
        const safeUrl = safeRedirectUrl(result.redirectUrl);
        if (safeUrl) window.location.assign(safeUrl);
      }
      await refresh();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && e.code === "idempotency_conflict") {
        clearIdempotencyKey();
      }
      setError(e instanceof Error ? e.message : String(e));
      if (!(e instanceof ApiError && e.code === "invalid_phone")) {
        await refresh(true);
      }
    } finally {
      setBusy(false);
    }
  }

  function downloadSplitReceipt() {
    if (!checkout) return;
    const content = formatSplitReceipt(checkout, displayFee, displayTotal);
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
    return (
      <div className="checkout-shell">
        <div className="checkout-container">
          <div className="checkout-topbar">
            <span className="flex-row" style={{ gap: "0.5rem" }}>
              <BrandMark size={24} />
              <strong>ELEPAY</strong>
            </span>
            <span className="checkout-secure-pill"><IconLock size={13} /> Secured</span>
          </div>
          <div className="checkout-card">
            <div className="checkout-body" style={{ textAlign: "center", padding: "2.25rem 1.5rem" }}>
              {error ? (
                <p className="notice error" role="alert">{error}</p>
              ) : (
                <div className="flex-row" style={{ justifyContent: "center" }}>
                  <span className="spinner" />
                  <span className="muted">Loading checkout…</span>
                </div>
              )}
              <p className="small muted mono" style={{ marginTop: "0.75rem" }}>Ref {reference}</p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  const safeStatusRedirect = safeRedirectUrl(status?.redirectUrl);

  return (
    <div className="checkout-shell">
      <div className="checkout-container">
        <div className="checkout-topbar">
          <span className="flex-row" style={{ gap: "0.5rem" }}>
            <BrandMark size={24} />
            <strong style={{ letterSpacing: "0.03em" }}>ELEPAY</strong>
          </span>
          <span className="checkout-secure-pill">
            <IconLock size={13} />
            Secured Checkout
          </span>
        </div>

        <main className="checkout-card" style={{ padding: 0, maxWidth: "none" }}>
          <div className="checkout-merchant-head">
            <div className="flex-between" style={{ alignItems: "flex-start" }}>
              <div>
                <h1>Pay {checkout.collector}</h1>
                {checkout.description && <p className="small muted" style={{ margin: 0 }}>{checkout.description}</p>}
              </div>
              <div style={{ textAlign: "right" }}>
                <div className="small muted">Total due</div>
                <div className="big mono" style={{ fontSize: "1.4rem", color: "var(--green-deep)" }}>
                  {formatMoney(displayTotal)}
                </div>
              </div>
            </div>
          </div>

          <div className="checkout-body">
            {/* Itemized Split-Order Breakdown */}
            <div className="checkout-summary-box">
              <table className="lines">
                <tbody>
                  {checkout.lines.map((l, i) => (
                    <tr key={i}>
                      <td>
                        <strong style={{ fontSize: "0.875rem" }}>{l.merchant}</strong>
                        <div className="muted small">{l.kind === "delivery" ? "Delivery / Freight" : (l.description || "Order item")}</div>
                      </td>
                      <td className="num mono">{formatMoney(l.amount)}</td>
                    </tr>
                  ))}
                  <tr>
                    <td className="small muted">Processing fee ({checkoutMethodLabel(displayMethod)})</td>
                    <td className="num small mono muted">{formatMoney(displayFee)}</td>
                  </tr>
                  <tr className="total">
                    <td>Total</td>
                    <td className="num mono">{formatMoney(displayTotal)}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            {status?.status === "paid" ? (
              <div className="checkout-status-card paid">
                <div className="checkout-status-icon" style={{ color: "var(--ok)" }}>
                  <IconCheckCircle size={24} />
                </div>
                <h2 style={{ margin: "0 0 0.25rem", fontSize: "1.15rem", color: "inherit" }}>Payment received</h2>
                <p className="small" style={{ marginBottom: "1rem" }}>
                  Receipt <span className="mono">{checkout.reference}</span>
                </p>
                <button className="small" onClick={() => downloadSplitReceipt()}>
                  <IconDownload size={15} />
                  Download Itemized Receipt
                </button>
              </div>
            ) : status?.status === "processing" ? (
              <div className="checkout-status-card processing">
                <div className="checkout-status-icon">
                  <span className="spinner" />
                </div>
                <h2 style={{ margin: "0 0 0.3rem", fontSize: "1.1rem", color: "inherit" }}>Waiting for confirmation…</h2>
                {status.message && <p className="small" style={{ marginBottom: "0.65rem" }}>{status.message}</p>}
                {displayMethod === "momo" && (
                  <div className="notice warn" style={{ textAlign: "left", margin: "0.75rem 0" }}>
                    <strong>Approve on your phone:</strong>
                    <span className="small">
                      1. Enter your MoMo PIN on the prompt sent to your phone.
                      <br />
                      2. No prompt? Dial <strong className="mono">*170#</strong> → <strong>6 (My Wallet)</strong> → <strong>3 (My Approvals)</strong> to approve.
                    </span>
                  </div>
                )}
                {railQrImage && (
                  <div className="qr-frame" style={{ margin: "0.75rem auto" }}>
                    <img src={railQrImage} alt="GhQR payment code" width={210} height={210} />
                    <p className="small muted" style={{ margin: "0.35rem 0 0" }}>
                      Scan with any GhQR banking or MoMo app to approve.
                    </p>
                  </div>
                )}
                {safeStatusRedirect && (
                  <p style={{ marginBottom: "0.75rem" }}>
                    <a href={safeStatusRedirect} className="tag info" style={{ padding: "0.45rem 0.9rem", fontSize: "0.82rem", textTransform: "none" }}>
                      Continue to approve payment <IconExternalLink size={14} />
                    </a>
                  </p>
                )}
                <div style={{ margin: "0.75rem 0" }}>
                  <button type="button" className="secondary small" onClick={() => void refresh()}>
                    I&apos;ve Approved — Check Status Now
                  </button>
                </div>
                <p className="small muted" style={{ margin: 0 }}>
                  You can close this page and reopen the same link. Reference <span className="mono">{checkout.reference}</span>.
                </p>
              </div>
            ) : checkout.status !== "open" ? (
              <div className="notice warn">
                <div className="flex-row" style={{ gap: "0.5rem" }}>
                  <IconAlertTriangle size={17} />
                  <strong>This checkout is {checkout.status}.</strong>
                </div>
              </div>
            ) : (
              <>
                <div className="flex-between" style={{ marginBottom: "0.75rem" }}>
                  <span className="small muted" style={{ fontWeight: 700, textTransform: "uppercase", letterSpacing: "0.04em" }}>
                    Payment method
                  </span>
                  <button type="button" className="small secondary" style={{ margin: 0 }} onClick={() => setShowQr(!showQr)}>
                    {showQr ? <><IconSmartphone size={14} /> Pay on this device</> : <><IconQrCode size={14} /> Open on another device</>}
                  </button>
                </div>

                {showQr ? (
                  <div className="qr-frame">
                    <h3 style={{ marginBottom: "0.2rem" }}>Open on another device</h3>
                    {qrCodeUrl ? (
                      <img src={qrCodeUrl} alt="Checkout link QR Code" width={210} height={210} />
                    ) : (
                      <p className="small muted">Generating QR…</p>
                    )}
                    <p className="small muted" style={{ margin: 0 }}>
                      Scan with your phone&apos;s camera to open this checkout link in another browser.
                    </p>
                  </div>
                ) : (
                  <form onSubmit={(e) => { e.preventDefault(); void pay(); }}>
                    <div className="method-list" role="radiogroup" aria-label="Payment method">
                      {checkout.methods.map((m) => {
                        const isSelected = method === m.method;
                        return (
                          <label key={m.method} className={`method-card ${isSelected ? "selected" : ""}`}>
                            <div className="method-card-left">
                              <input
                                type="radio"
                                name="method"
                                checked={isSelected}
                                onChange={() => {
                                  clearIdempotencyKey();
                                  setMethod(m.method);
                                }}
                              />
                              <span className="method-card-icon">{methodIcon(m.method)}</span>
                              <div>
                                <div className="method-card-title">{checkoutMethodLabel(m.method)}</div>
                                <div className="method-card-fee">
                                  {m.fee > 0 ? `Fee ${formatMoney(m.fee)}` : "No processing fee"}
                                </div>
                              </div>
                            </div>
                            <span className="method-card-total">{formatMoney(m.total)}</span>
                          </label>
                        );
                      })}
                    </div>

                    {method === "momo" && (
                      <div style={{ marginBottom: "1rem" }}>
                        <label style={{ marginBottom: "0.5rem" }}>
                          Mobile money network
                          <div className="network-pills">
                            {NETWORKS.map(([v, n]) => (
                              <button
                                key={v}
                                type="button"
                                className={`network-pill ${network === v ? "active" : ""}`}
                                onClick={() => {
                                  clearIdempotencyKey();
                                  setNetwork(v);
                                }}
                              >
                                {n}
                              </button>
                            ))}
                          </div>
                        </label>
                        <label>
                          MoMo phone number
                          <input
                            inputMode="tel"
                            autoComplete="tel"
                            value={phone}
                            onChange={(e) => {
                              clearIdempotencyKey();
                              setPhone(e.target.value);
                            }}
                            placeholder="024 123 4567"
                            required
                          />
                        </label>
                      </div>
                    )}

                    <button disabled={busy} type="submit" className="large full-width">
                      <IconLock size={16} />
                      {busy ? "Processing…" : `Approve ${formatMoney(selected?.total ?? 0)}`}
                    </button>
                  </form>
                )}
              </>
            )}

            {error && <p className="notice error" role="alert" style={{ marginTop: "0.9rem" }}>{error}</p>}
          </div>
        </main>

        <p className="checkout-footer">
          Need help? Quote reference <strong className="mono">{checkout.reference}</strong> to ELEPAY support.
        </p>
      </div>
    </div>
  );
}

export function StartFromLink({ slug, code }: { slug?: string; code?: string }) {
  const [info, setInfo] = useState<{ title?: string; merchant: string; amount?: number | string } | null>(null);
  const [amount, setAmount] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (slug) api<{ title: string; merchant: string; amount: number | string }>(`/v1/public/links/${slug}`, { anonymous: true })
      .then(setInfo).catch((e) => setError(e.message));
    else setInfo({ merchant: `Merchant code ${code}` });
  }, [slug, code]);

  async function start() {
    if (busy) return;
    const pesewas = parseMoney(amount);
    setBusy(true);
    setError(null);
    try {
      if (!(info?.amount && info.amount !== "open") && !pesewas) throw new Error("Enter an amount, e.g. 10 or 12.50");
      const body = info?.amount && info.amount !== "open" ? {} : { amount: pesewas };
      const r = await api<{ reference: string }>(slug ? `/v1/public/links/${slug}/checkouts` : `/v1/public/merchant-codes/${code}/checkouts`,
        { method: "POST", anonymous: true, body });
      window.location.assign(`/pay/${r.reference}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  if (!info) {
    return (
      <div className="checkout-shell">
        <div className="checkout-container">
          <div className="checkout-card">
            <div className="checkout-body" style={{ textAlign: "center" }}>
              <p className={error ? "error" : "muted"}>{error ?? "Loading payment link…"}</p>
            </div>
          </div>
        </div>
      </div>
    );
  }
  const fixed = typeof info.amount === "number";
  return (
    <div className="checkout-shell">
      <div className="checkout-container">
        <div className="checkout-topbar">
          <span className="flex-row" style={{ gap: "0.5rem" }}>
            <BrandMark size={24} />
            <strong>ELEPAY</strong>
          </span>
          <span className="checkout-secure-pill"><IconLock size={13} /> Payment Link</span>
        </div>
        <main className="checkout-card" style={{ padding: 0, maxWidth: "none" }}>
          <div className="checkout-merchant-head">
            <h1>{info.title ?? "Pay Merchant"}</h1>
            <p className="small muted" style={{ margin: 0 }}>{info.merchant}</p>
          </div>
          <div className="checkout-body">
            {fixed ? (
              <div className="checkout-summary-box" style={{ textAlign: "center", padding: "1.25rem" }}>
                <div className="small muted">Amount to pay</div>
                <div className="big mono" style={{ color: "var(--green-deep)", marginTop: "0.2rem" }}>
                  {formatMoney(info.amount as number)}
                </div>
              </div>
            ) : (
              <label>
                Amount (GH₵)
                <input
                  className="mono"
                  inputMode="decimal"
                  placeholder="0.00"
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                />
              </label>
            )}
            <button className="large full-width" disabled={busy} onClick={() => void start()} style={{ marginTop: "0.75rem" }}>
              {busy ? "Starting checkout…" : "Continue to Checkout"}
            </button>
            {error && <p className="notice error" style={{ marginTop: "0.75rem" }}>{error}</p>}
          </div>
        </main>
      </div>
    </div>
  );
}
