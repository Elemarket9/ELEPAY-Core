import { useCallback, useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { api, ApiError, newKey } from "../api";
import { formatMoney, normalizeGhanaPhone, parseMoney } from "../money";
import {
  isFeatureNotEnabled,
  isTerminalStatusFailed,
  isTerminalStatusPaid,
  MOMO_NETWORKS,
  MomoNetwork,
  NfcTapResult,
  NormalizedNfcTapResult,
  normalizeNfcTapResult,
  normalizeTenderResult,
  PosOrder,
  TenderInitiation,
  TenderMode,
} from "./posHelpers";

interface PosTenderPanelProps {
  merchantId: string;
  terminalId: string | null;
  totalPesewas: number;
  ensureOrder: () => Promise<PosOrder>;
  onOrderPaid: (order: PosOrder) => Promise<void>;
  onModuleDisabled: () => void;
  onClearCart?: () => void;
  hasOrder: boolean;
}

interface DigitalCheckoutFallback {
  id: string;
  reference: string;
  amount: number;
  status: string;
  payUrl: string | null;
}

export function PosTenderPanel({
  merchantId,
  terminalId,
  totalPesewas,
  ensureOrder,
  onOrderPaid,
  onModuleDisabled,
  onClearCart,
  hasOrder,
}: PosTenderPanelProps) {
  const [mode, setMode] = useState<TenderMode>("CASH");
  const [cashInput, setCashInput] = useState("");
  const [momoPhone, setMomoPhone] = useState("");
  const [momoNetwork, setMomoNetwork] = useState<MomoNetwork>("mtn");
  const [nfcKsn, setNfcKsn] = useState("");
  const [nfcEmvTlvHex, setNfcEmvTlvHex] = useState("");
  const [nfcMaskedPan, setNfcMaskedPan] = useState("");
  const [activeOrder, setActiveOrder] = useState<PosOrder | null>(null);
  const [pendingRef, setPendingRef] = useState<string | null>(null);
  const [checkoutRef, setCheckoutRef] = useState<string | null>(null);
  const [pollStatus, setPollStatus] = useState<string | null>(null);
  const [qrPayload, setQrPayload] = useState<string | null>(null);
  const [qrReference, setQrReference] = useState<string | null>(null);
  const [qrImage, setQrImage] = useState<string | null>(null);
  const [cardPayUrl, setCardPayUrl] = useState<string | null>(null);
  const [nfcResult, setNfcResult] = useState<NormalizedNfcTapResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const completingRef = useRef(false);

  const stopPolling = useCallback(() => {
    if (pollTimerRef.current !== null) {
      clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
  }, []);

  useEffect(() => () => stopPolling(), [stopPolling]);

  useEffect(() => {
    if (!qrPayload) {
      setQrImage(null);
      return;
    }
    void QRCode.toDataURL(qrPayload, { margin: 1, width: 220 })
      .then(setQrImage)
      .catch(() => setQrImage(null));
  }, [qrPayload]);

  const completeDigitalOrder = useCallback(async (orderObj: PosOrder, usedFallbackCheckout: boolean) => {
    if (completingRef.current) return;
    completingRef.current = true;
    stopPolling();
    try {
      let paidOrder = orderObj;
      if (usedFallbackCheckout) {
        paidOrder = await api<PosOrder>(`/v1/pos/orders/${orderObj.id}/digital-checkout/confirm`, { method: "POST" });
      } else {
        const refreshed = await api<PosOrder>(`/v1/pos/orders/${orderObj.id}`);
        if (refreshed.status === "PAID") {
          paidOrder = refreshed;
        } else {
          paidOrder = await api<PosOrder>(`/v1/pos/orders/${orderObj.id}/digital-checkout/confirm`, { method: "POST" }).catch(() => refreshed);
        }
      }
      setPendingRef(null);
      setCheckoutRef(null);
      setPollStatus(null);
      setQrPayload(null);
      setQrReference(null);
      setCardPayUrl(null);
      await onOrderPaid(paidOrder);
    } catch (err) {
      if (isFeatureNotEnabled(err)) { onModuleDisabled(); return; }
      setError(err instanceof ApiError ? err.message : String(err));
    } finally {
      completingRef.current = false;
    }
  }, [onModuleDisabled, onOrderPaid, stopPolling]);

  const startPolling = useCallback((orderObj: PosOrder, refToPoll: string, usedFallbackCheckout: boolean) => {
    stopPolling();
    setPollStatus("PENDING");
    pollTimerRef.current = setInterval(() => {
      void (async () => {
        try {
          const st = await api<{ status: string; message?: string | null }>(
            `/v1/public/checkouts/${refToPoll}/status`,
            { anonymous: true },
          );
          setPollStatus(st.status);
          if (isTerminalStatusPaid(st.status)) {
            await completeDigitalOrder(orderObj, usedFallbackCheckout);
          } else if (isTerminalStatusFailed(st.status)) {
            stopPolling();
            setError(st.message || `Payment ${st.status.toLowerCase()}.`);
          }
        } catch {
          // Also check POS order directly in case tender settled server-side
          try {
            const ord = await api<PosOrder>(`/v1/pos/orders/${orderObj.id}`);
            if (isTerminalStatusPaid(ord.status)) {
              await completeDigitalOrder(orderObj, false);
            }
          } catch {
            // Transient poll error: keep polling until cancelled or settled
          }
        }
      })();
    }, 3000);
  }, [completeDigitalOrder, stopPolling]);

  async function payCash() {
    setError(null);
    const cash = parseMoney(cashInput);
    if (cash === null || cash <= 0) {
      setError("Enter the cash received, like 50.00");
      return;
    }
    if (cash < totalPesewas) {
      setError(`Cash received is less than ${formatMoney(totalPesewas)}`);
      return;
    }
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const paid = await api<PosOrder>(`/v1/pos/orders/${ord.id}/payments`, {
        body: { tenderType: "CASH", amountPesewas: cash, reference: null },
        idempotencyKey: newKey(),
      });
      if (paid.status !== "PAID") {
        setError("The sale is not fully paid yet.");
        return;
      }
      await onOrderPaid(paid);
    } catch (err) {
      if (isFeatureNotEnabled(err)) { onModuleDisabled(); return; }
      setError(`Sale not recorded: ${err instanceof ApiError ? err.message : String(err)}`);
    } finally {
      setBusy(false);
    }
  }

  async function startMomoPush() {
    setError(null);
    const msisdn = normalizeGhanaPhone(momoPhone);
    if (!msisdn) {
      setError("Enter a valid Ghana MoMo number, e.g. 024 123 4567");
      return;
    }
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      let usedFallback = false;
      let ref: string | null = null;
      try {
        const res = await api<TenderInitiation>(`/v1/pos/orders/${ord.id}/tenders`, {
          body: {
            tenderType: "MOMO",
            network: momoNetwork,
            phone: msisdn,
            amountPesewas: ord.totalPesewas,
          },
          idempotencyKey: newKey(),
        });
        const normalized = normalizeTenderResult(res);
        ref = normalized.checkoutRef;
        if (isTerminalStatusPaid(normalized.paymentStatus) || isTerminalStatusPaid(normalized.orderStatus)) {
          await completeDigitalOrder(normalized.order ?? ord, false);
          return;
        }
        if (isTerminalStatusFailed(normalized.paymentStatus)) {
          throw new Error(normalized.prompt ?? `MoMo payment ${normalized.paymentStatus?.toLowerCase()}.`);
        }
      } catch (tenderErr) {
        if (isFeatureNotEnabled(tenderErr)) { onModuleDisabled(); return; }
        if (tenderErr instanceof ApiError && tenderErr.status === 404) {
          usedFallback = true;
          const dc = await api<DigitalCheckoutFallback>(`/v1/pos/orders/${ord.id}/digital-checkout`, { method: "POST" });
          ref = dc.reference;
          await api(`/v1/public/checkouts/${dc.reference}/pay`, {
            method: "POST",
            anonymous: true,
            idempotencyKey: newKey(),
            body: { method: "momo", phone: msisdn, network: momoNetwork },
          });
        } else {
          throw tenderErr;
        }
      }
      if (!ref) throw new Error("No payment reference returned by server.");
      setPendingRef(ref);
      setCheckoutRef(ref);
      startPolling(ord, ref, usedFallback);
    } catch (err) {
      if (isFeatureNotEnabled(err)) { onModuleDisabled(); return; }
      setError(`MoMo push failed: ${err instanceof ApiError ? err.message : String(err)}`);
    } finally {
      setBusy(false);
    }
  }

  async function startGhQr() {
    setError(null);
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      let usedFallback = false;
      let ref: string | null = null;
      let emvPayload: string | null = null;
      let resolvedQrRef: string | null = null;
      try {
        const res = await api<TenderInitiation>(`/v1/pos/orders/${ord.id}/tenders`, {
          body: {
            tenderType: "GHQR",
            amountPesewas: ord.totalPesewas,
          },
          idempotencyKey: newKey(),
        });
        const normalized = normalizeTenderResult(res);
        ref = normalized.checkoutRef;
        emvPayload = normalized.qrPayload ?? normalized.payUrl ?? null;
        resolvedQrRef = normalized.qrReference;
        if (isTerminalStatusPaid(normalized.paymentStatus) || isTerminalStatusPaid(normalized.orderStatus)) {
          await completeDigitalOrder(normalized.order ?? ord, false);
          return;
        }
      } catch (tenderErr) {
        if (isFeatureNotEnabled(tenderErr)) { onModuleDisabled(); return; }
        if (tenderErr instanceof ApiError && tenderErr.status === 404) {
          usedFallback = true;
          const dc = await api<DigitalCheckoutFallback>(`/v1/pos/orders/${ord.id}/digital-checkout`, { method: "POST" });
          ref = dc.reference;
          const qrRes = await api<{ payload: string }>(`/v1/merchants/${merchantId}/qr?link=${encodeURIComponent(dc.reference)}`)
            .catch(() => ({ payload: dc.payUrl ?? dc.reference }));
          emvPayload = qrRes.payload;
        } else {
          throw tenderErr;
        }
      }
      if (!ref) throw new Error("No GhQR reference returned.");
      setPendingRef(ref);
      setCheckoutRef(ref);
      setQrReference(resolvedQrRef);
      setQrPayload(emvPayload ?? ref);
      startPolling(ord, ref, usedFallback);
    } catch (err) {
      if (isFeatureNotEnabled(err)) { onModuleDisabled(); return; }
      setError(`GhQR failed: ${err instanceof ApiError ? err.message : String(err)}`);
    } finally {
      setBusy(false);
    }
  }

  async function startCardCheckout() {
    setError(null);
    setNfcResult(null);
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const res = await api<TenderInitiation>(`/v1/pos/orders/${ord.id}/tenders`, {
        body: {
          tenderType: "CARD",
          amountPesewas: ord.totalPesewas,
        },
        idempotencyKey: newKey(),
      });
      const normalized = normalizeTenderResult(res);
      if (isTerminalStatusPaid(normalized.paymentStatus) || isTerminalStatusPaid(normalized.orderStatus)) {
        await completeDigitalOrder(normalized.order ?? ord, false);
        return;
      }
      if (isTerminalStatusFailed(normalized.paymentStatus)) {
        throw new Error(normalized.prompt ?? `Card payment ${normalized.paymentStatus?.toLowerCase()}.`);
      }
      if (!normalized.checkoutRef) {
        throw new Error("No card checkout reference returned by server.");
      }
      setPendingRef(normalized.checkoutRef);
      setCheckoutRef(normalized.checkoutRef);
      setCardPayUrl(normalized.payUrl);
      startPolling(ord, normalized.checkoutRef, false);
    } catch (err) {
      if (isFeatureNotEnabled(err)) { onModuleDisabled(); return; }
      setError(`Card checkout failed: ${err instanceof ApiError ? err.message : String(err)}`);
    } finally {
      setBusy(false);
    }
  }

  async function initiateNfcTap() {
    setError(null);
    setNfcResult(null);
    if (!terminalId) {
      setError("Select or pair an active POS terminal before initiating an NFC tap.");
      return;
    }
    const ksn = nfcKsn.trim();
    const emvTlvHex = nfcEmvTlvHex.trim();
    if (!ksn || !emvTlvHex) {
      setError("Enter both KSN and redacted EMV TLV hex from the NFC reader, or use Card Checkout below.");
      return;
    }
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);

      const res = await api<NfcTapResult>(`/v1/pos/orders/${ord.id}/nfc-tap`, {
        body: {
          terminalId,
          ksn,
          emvTlvHex,
          maskedPan: nfcMaskedPan.trim() || null,
          amountPesewas: ord.totalPesewas,
          currency: "GHS",
        },
        idempotencyKey: newKey(),
      });
      const normalized = normalizeNfcTapResult(res);
      setNfcResult(normalized);
      if (isTerminalStatusPaid(normalized.status) || isTerminalStatusPaid(normalized.order?.status)) {
        await onOrderPaid(normalized.order ?? (await api<PosOrder>(`/v1/pos/orders/${ord.id}`)));
      } else if (normalized.checkoutRef && !isTerminalStatusFailed(normalized.status)) {
        setPendingRef(normalized.checkoutRef);
        setCheckoutRef(normalized.checkoutRef);
        setCardPayUrl(normalized.payUrl);
        startPolling(ord, normalized.checkoutRef, false);
      } else if (isTerminalStatusFailed(normalized.status)) {
        setError(normalized.message ?? `NFC card authorization ${normalized.status.toLowerCase()}.`);
      }
    } catch (err) {
      if (isFeatureNotEnabled(err)) { onModuleDisabled(); return; }
      setError(`NFC Tap-to-Pay error: ${err instanceof ApiError ? err.message : String(err)}`);
    } finally {
      setBusy(false);
    }
  }

  async function cancelPending() {
    stopPolling();
    if (activeOrder) {
      await api(`/v1/pos/orders/${activeOrder.id}/digital-checkout/cancel`, { method: "POST" }).catch(() => {});
    }
    setPendingRef(null);
    setCheckoutRef(null);
    setPollStatus(null);
    setQrPayload(null);
    setQrReference(null);
    setCardPayUrl(null);
    setNfcResult(null);
  }

  return (
    <div style={{ marginTop: "1rem" }}>
      <div className="tabs" style={{ marginBottom: ".8rem" }}>
        <button type="button" className={mode === "CASH" ? "active" : ""} onClick={() => { setMode("CASH"); setError(null); }}>Cash</button>
        <button type="button" className={mode === "MOMO" ? "active" : ""} onClick={() => { setMode("MOMO"); setError(null); }}>MoMo Push</button>
        <button type="button" className={mode === "GHQR" ? "active" : ""} onClick={() => { setMode("GHQR"); setError(null); }}>GhQR</button>
        <button type="button" className={mode === "NFC_CARD" ? "active" : ""} onClick={() => { setMode("NFC_CARD"); setError(null); }}>Card / NFC Tap</button>
      </div>

      {error && <p className="error">{error}</p>}

      {pendingRef ? (
        <div className="notice">
          <p>
            Awaiting live rail confirmation · Ref <strong className="mono">{pendingRef}</strong>
            {pollStatus && <> · Status: <strong>{pollStatus}</strong></>}
          </p>
          <p className="small muted">Auto-polling every 3 seconds…</p>
          {qrImage && <img src={qrImage} alt="EMVCo GhQR code" width={220} height={220} style={{ display: "block", margin: ".5rem 0" }} />}
          {qrReference && (
            <p className="small mono">
              GhQR Ref: {qrReference}
            </p>
          )}
          {qrPayload && (
            <p className="small mono" style={{ wordBreak: "break-all" }}>
              EMVCo Payload: {qrPayload}
            </p>
          )}
          {cardPayUrl && (
            <p className="small">
              <a href={cardPayUrl} target="_blank" rel="noreferrer">Open Card Checkout Link</a>
            </p>
          )}
          <div className="flex-row">
            {checkoutRef && activeOrder && (
              <button type="button" onClick={() => void completeDigitalOrder(activeOrder, false)} disabled={busy}>
                Verify Now
              </button>
            )}
            <button type="button" className="secondary" onClick={() => void cancelPending()} disabled={busy}>
              Cancel Payment
            </button>
          </div>
        </div>
      ) : (
        <>
          {mode === "CASH" && (
            <div>
              <label>
                Cash received (GH₵)
                <input
                  inputMode="decimal"
                  value={cashInput}
                  onChange={(e) => setCashInput(e.target.value)}
                  placeholder={(totalPesewas / 100).toFixed(2)}
                />
              </label>
              <div className="flex-row" style={{ marginBottom: ".6rem", flexWrap: "wrap" }}>
                <button type="button" className="secondary small" onClick={() => setCashInput((totalPesewas / 100).toFixed(2))}>
                  Exact ({formatMoney(totalPesewas)})
                </button>
                {[1000, 2000, 5000, 10000].map((add) => (
                  <button
                    key={add}
                    type="button"
                    className="secondary small"
                    onClick={() => setCashInput(((totalPesewas + add) / 100).toFixed(2))}
                  >
                    +{formatMoney(add)}
                  </button>
                ))}
              </div>
              <div className="flex-row">
                <button type="button" onClick={() => void payCash()} disabled={busy}>
                  {busy ? "Recording…" : `Charge Cash ${formatMoney(totalPesewas)}`}
                </button>
                {!hasOrder && onClearCart && (
                  <button type="button" className="secondary" onClick={onClearCart}>Clear</button>
                )}
              </div>
            </div>
          )}

          {mode === "MOMO" && (
            <div>
              <div className="grid-2">
                <label>
                  Network
                  <select value={momoNetwork} onChange={(e) => setMomoNetwork(e.target.value as MomoNetwork)}>
                    {MOMO_NETWORKS.map((n) => (
                      <option key={n.value} value={n.value}>{n.label}</option>
                    ))}
                  </select>
                </label>
                <label>
                  Customer MoMo Phone
                  <input
                    inputMode="tel"
                    value={momoPhone}
                    onChange={(e) => setMomoPhone(e.target.value)}
                    placeholder="024 123 4567"
                  />
                </label>
              </div>
              <button type="button" onClick={() => void startMomoPush()} disabled={busy}>
                {busy ? "Sending Prompt…" : `Send MoMo Push (${formatMoney(totalPesewas)})`}
              </button>
            </div>
          )}

          {mode === "GHQR" && (
            <div>
              <p className="small muted">Generate a dynamic EMVCo GhQR code for the customer to scan with any Ghana bank or MoMo app.</p>
              <button type="button" onClick={() => void startGhQr()} disabled={busy}>
                {busy ? "Generating GhQR…" : `Display GhQR (${formatMoney(totalPesewas)})`}
              </button>
            </div>
          )}

          {mode === "NFC_CARD" && (
            <div>
              <p className="small muted">
                Pay via online Card Checkout, or submit hardware DUKPT/EMV NFC tap data{terminalId ? ` (Terminal ${terminalId.slice(0, 8)})` : ""}.
              </p>
              <div style={{ marginBottom: ".8rem" }}>
                <button type="button" onClick={() => void startCardCheckout()} disabled={busy}>
                  {busy ? "Creating Card Checkout…" : `Charge Card via Checkout (${formatMoney(totalPesewas)})`}
                </button>
              </div>
              <div className="grid-2">
                <label>
                  DUKPT KSN
                  <input
                    className="mono"
                    value={nfcKsn}
                    onChange={(e) => setNfcKsn(e.target.value)}
                    placeholder="FFFF9876543210E00001"
                  />
                </label>
                <label>
                  Masked PAN (optional)
                  <input
                    className="mono"
                    value={nfcMaskedPan}
                    onChange={(e) => setNfcMaskedPan(e.target.value)}
                    placeholder="411111******1111"
                  />
                </label>
              </div>
              <label>
                Redacted EMV TLV Hex
                <input
                  className="mono"
                  value={nfcEmvTlvHex}
                  onChange={(e) => setNfcEmvTlvHex(e.target.value)}
                  placeholder="9F02060000000010005F2A020936..."
                />
              </label>
              <button type="button" className="secondary" onClick={() => void initiateNfcTap()} disabled={busy}>
                {busy ? "Authorizing Tap…" : `Initiate NFC Tap-to-Pay (${formatMoney(totalPesewas)})`}
              </button>
              {nfcResult && (
                <p className="notice" style={{ marginTop: ".6rem" }}>
                  Backend Status: <strong>{nfcResult.status}</strong>
                  {nfcResult.authCode && <> · Auth: <span className="mono">{nfcResult.authCode}</span></>}
                  {nfcResult.cardScheme && <> · Scheme: {nfcResult.cardScheme}</>}
                  {nfcResult.cvmMethod && <> · CVM: {nfcResult.cvmMethod}</>}
                  {nfcResult.checkoutRef && <> · Ref: <span className="mono">{nfcResult.checkoutRef}</span></>}
                </p>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}
