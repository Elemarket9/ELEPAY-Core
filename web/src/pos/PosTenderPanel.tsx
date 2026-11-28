import { useCallback, useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { api, ApiError, newKey } from "../api";
import { safeRedirectUrl } from "../Checkout";
import { formatMoney, normalizeGhanaPhone, parseMoney } from "../money";
import { IconBanknote, IconCreditCard, IconExternalLink, IconLandmark, IconQrCode, IconSmartphone } from "../ui/Icons";
import {
  isFeatureNotEnabled,
  isPollStatusFailed,
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

export type ExternalTenderMode = "BANK_TRANSFER" | "EXTERNAL_MOMO" | "EXTERNAL_POS" | "EXTERNAL_CARD";
export type PanelTenderMode = TenderMode | ExternalTenderMode;

const EXTERNAL_TENDER_META: Record<ExternalTenderMode, { label: string; placeholder: string; hint: string }> = {
  BANK_TRANSFER: {
    label: "Bank transfer",
    placeholder: "e.g. GIP-20261127-99412",
    hint: "Record a confirmed bank transfer (excluded from shift cash drawer; not settled by ELEPAY).",
  },
  EXTERNAL_MOMO: {
    label: "Own MoMo",
    placeholder: "e.g. MTN-TXN-8841209",
    hint: "Record payment received on your own MoMo merchant SIM (excluded from cash drawer; ELEPAY never touches funds).",
  },
  EXTERNAL_POS: {
    label: "External POS",
    placeholder: "e.g. EXT-POS-441092",
    hint: "Record payment settled on your own third-party POS terminal (excluded from cash drawer; ELEPAY never touches funds).",
  },
  EXTERNAL_CARD: {
    label: "External card",
    placeholder: "e.g. RRN-00492184",
    hint: "Record payment swiped on your own bank POS card terminal (excluded from cash drawer; ELEPAY never touches funds).",
  },
};

function isExternalMode(m: PanelTenderMode): m is ExternalTenderMode {
  return m === "BANK_TRANSFER" || m === "EXTERNAL_MOMO" || m === "EXTERNAL_POS" || m === "EXTERNAL_CARD";
}

export interface SubBill {
  id?: string;
  splitKey: string;
  label: string;
  seatNumber?: number | null;
  amountPesewas: number;
  paidPesewas: number;
  remainingPesewas: number;
  status: string;
  itemIds?: string[];
  openCheckoutRef?: string | null;
}

export interface SplitPlan {
  orderId: string;
  orderStatus: string;
  totalPesewas: number;
  paidPesewas: number;
  splitType: string | null;
  subBills: SubBill[];
}

export function normalizeSplitPlan(raw: {
  orderId: string;
  orderStatus: string;
  totalPesewas: number;
  paidPesewas: number;
  splitType?: string | null;
  subBills?: SubBill[];
  splits?: SubBill[];
}): SplitPlan {
  const list = raw.subBills ?? raw.splits ?? [];
  return {
    orderId: raw.orderId,
    orderStatus: raw.orderStatus,
    totalPesewas: raw.totalPesewas,
    paidPesewas: raw.paidPesewas,
    splitType: raw.splitType ?? null,
    subBills: list.map((s) => ({
      ...s,
      remainingPesewas:
        typeof s.remainingPesewas === "number"
          ? Math.max(0, s.remainingPesewas)
          : Math.max(0, s.amountPesewas - (s.paidPesewas ?? 0)),
    })),
  };
}

export function computeTargetTenderAmount(params: {
  order: Pick<PosOrder, "totalPesewas" | "paidPesewas"> | null;
  totalPesewas: number;
  splitAmountPesewas?: number | null;
}): number {
  if (params.splitAmountPesewas != null) {
    return Math.max(0, params.splitAmountPesewas);
  }
  if (params.order) {
    return Math.max(0, params.order.totalPesewas - params.order.paidPesewas);
  }
  return Math.max(0, params.totalPesewas);
}

export function validateBankTransferInput(params: {
  amountInput: string;
  reference: string;
  maxAmountPesewas: number;
}): { ok: true; amountPesewas: number; reference: string } | { ok: false; error: string } {
  const ref = params.reference.trim();
  if (!ref) {
    return { ok: false, error: "Bank transfer reference is required (e.g. GIP/NIP ref)." };
  }
  if (ref.length > 128) {
    return { ok: false, error: "Bank transfer reference must be 128 characters or fewer." };
  }
  const amount = params.amountInput.trim() ? parseMoney(params.amountInput) : params.maxAmountPesewas;
  if (amount === null || amount <= 0) {
    return { ok: false, error: "Enter a valid bank transfer amount, e.g. 50.00" };
  }
  if (amount > params.maxAmountPesewas) {
    return {
      ok: false,
      error: `Bank transfer amount cannot exceed balance due (${formatMoney(params.maxAmountPesewas)}).`,
    };
  }
  return { ok: true, amountPesewas: amount, reference: ref };
}

export function buildItemSplitGroups(
  itemIds: string[],
  assignments: Record<string, number>,
): { label: string; itemIds: string[] }[] {
  const map = new Map<number, string[]>();
  for (const id of itemIds) {
    const billNum = assignments[id] ?? 1;
    const list = map.get(billNum) ?? [];
    list.push(id);
    map.set(billNum, list);
  }
  return Array.from(map.entries())
    .sort((a, b) => a[0] - b[0])
    .map(([billNum, ids]) => ({ label: `Bill ${billNum}`, itemIds: ids }));
}

interface PosTenderPanelProps {
  merchantId: string;
  terminalId: string | null;
  totalPesewas: number;
  ensureOrder: () => Promise<PosOrder>;
  onOrderPaid: (order: PosOrder) => Promise<void>;
  onModuleDisabled: () => void;
  onClearCart?: () => void;
  hasOrder: boolean;
  order?: PosOrder | null;
  splitKey?: string | null;
  splitLabel?: string | null;
  splitAmountPesewas?: number | null;
  onPartialPaid?: (order: PosOrder) => Promise<void> | void;
}

interface DigitalCheckoutFallback {
  id: string;
  reference: string;
  amount: number;
  status: string;
  payUrl: string | null;
  qrPayload?: string | null;
}

export function PosTenderPanel({
  merchantId: _merchantId,
  terminalId,
  totalPesewas,
  ensureOrder,
  onOrderPaid,
  onModuleDisabled,
  onClearCart,
  hasOrder,
  order: externalOrder = null,
  splitKey = null,
  splitLabel = null,
  splitAmountPesewas = null,
  onPartialPaid,
}: PosTenderPanelProps) {
  const [mode, setMode] = useState<PanelTenderMode>("CASH");
  const [cashInput, setCashInput] = useState("");
  const [bankAmountInput, setBankAmountInput] = useState("");
  const [bankReference, setBankReference] = useState("");
  const [momoPhone, setMomoPhone] = useState("");
  const [momoNetwork, setMomoNetwork] = useState<MomoNetwork>("mtn");
  const [nfcKsn, setNfcKsn] = useState("");
  const [nfcEmvTlvHex, setNfcEmvTlvHex] = useState("");
  const [nfcMaskedPan, setNfcMaskedPan] = useState("");
  const [activeOrder, setActiveOrder] = useState<PosOrder | null>(externalOrder);
  const [pendingRef, setPendingRef] = useState<string | null>(null);
  const [checkoutRef, setCheckoutRef] = useState<string | null>(null);
  const [pollStatus, setPollStatus] = useState<string | null>(null);
  const [qrPayload, setQrPayload] = useState<string | null>(null);
  const [qrReference, setQrReference] = useState<string | null>(null);
  const [qrImage, setQrImage] = useState<string | null>(null);
  const [cardPayUrl, setCardPayUrl] = useState<string | null>(null);
  const [nfcResult, setNfcResult] = useState<NormalizedNfcTapResult | null>(null);
  const [verifyNotice, setVerifyNotice] = useState<string | null>(null);
  const [partialNotice, setPartialNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const pollGenRef = useRef(0);
  const completingRef = useRef(false);
  const usedFallbackRef = useRef(false);

  useEffect(() => {
    setActiveOrder(externalOrder);
  }, [externalOrder]);

  useEffect(() => {
    setCashInput("");
    setBankAmountInput("");
  }, [splitKey, splitAmountPesewas]);

  const stopPolling = useCallback(() => {
    pollGenRef.current += 1;
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

  const remainingPesewas = computeTargetTenderAmount({
    order: activeOrder,
    totalPesewas,
    splitAmountPesewas,
  });

  const handleSettledOrder = useCallback(async (settledOrder: PosOrder, successMsg?: string) => {
    stopPolling();
    setPendingRef(null);
    setCheckoutRef(null);
    setPollStatus(null);
    setQrPayload(null);
    setQrReference(null);
    setCardPayUrl(null);
    setVerifyNotice(null);
    setCashInput("");
    setBankAmountInput("");
    setBankReference("");
    setActiveOrder(settledOrder);

    if (settledOrder.status === "PAID") {
      setPartialNotice(null);
      await onOrderPaid(settledOrder);
      return;
    }

    const left = Math.max(0, settledOrder.totalPesewas - settledOrder.paidPesewas);
    setPartialNotice(successMsg ?? `Payment recorded. Remaining order balance: ${formatMoney(left)}.`);
    await onPartialPaid?.(settledOrder);
  }, [onOrderPaid, onPartialPaid, stopPolling]);

  const completeDigitalOrder = useCallback(async (orderObj: PosOrder, usedFallbackCheckout: boolean) => {
    if (completingRef.current) return;
    completingRef.current = true;
    setVerifyNotice(null);
    setError(null);
    const confirmPath = splitKey
      ? `/v1/pos/orders/${orderObj.id}/digital-checkout/confirm?splitKey=${encodeURIComponent(splitKey)}`
      : `/v1/pos/orders/${orderObj.id}/digital-checkout/confirm`;
    try {
      let paidOrder: PosOrder;
      if (usedFallbackCheckout) {
        paidOrder = await api<PosOrder>(confirmPath, { method: "POST" });
      } else {
        const refreshed = await api<PosOrder>(`/v1/pos/orders/${orderObj.id}`);
        if (refreshed.status === "PAID" || refreshed.paidPesewas > orderObj.paidPesewas) {
          paidOrder = refreshed;
        } else {
          paidOrder = await api<PosOrder>(confirmPath, { method: "POST" });
        }
      }
      if (paidOrder.status !== "PAID" && !(paidOrder.status === "PARTIAL" && paidOrder.paidPesewas > orderObj.paidPesewas)) {
        setActiveOrder(paidOrder);
        setVerifyNotice("Payment not confirmed yet — still waiting for rail confirmation.");
        return;
      }
      await handleSettledOrder(paidOrder);
    } catch (err) {
      if (isFeatureNotEnabled(err)) {
        stopPolling();
        onModuleDisabled();
        return;
      }
      if (err instanceof ApiError && err.status === 409) {
        setVerifyNotice(err.message || "Payment not confirmed yet — still waiting for rail confirmation.");
      } else {
        setError(err instanceof ApiError ? err.message : String(err));
      }
    } finally {
      completingRef.current = false;
    }
  }, [handleSettledOrder, onModuleDisabled, splitKey, stopPolling]);

  const startPolling = useCallback((orderObj: PosOrder, refToPoll: string, usedFallbackCheckout: boolean) => {
    stopPolling();
    const gen = ++pollGenRef.current;
    usedFallbackRef.current = usedFallbackCheckout;
    setVerifyNotice(null);
    setPollStatus("PENDING");
    pollTimerRef.current = setInterval(() => {
      void (async () => {
        try {
          const st = await api<{ status: string; railStatus?: string | null; message?: string | null }>(
            `/v1/public/checkouts/${refToPoll}/status`,
            { anonymous: true },
          );
          if (pollGenRef.current !== gen) return;
          setPollStatus(st.railStatus && isTerminalStatusFailed(st.railStatus) ? st.railStatus : st.status);
          if (isTerminalStatusPaid(st.status)) {
            await completeDigitalOrder(orderObj, usedFallbackCheckout);
          } else if (isPollStatusFailed(st)) {
            stopPolling();
            const failedLabel = (isTerminalStatusFailed(st.railStatus) ? st.railStatus : st.status) ?? "failed";
            setError(st.message || `Payment ${failedLabel.toLowerCase()}.`);
          }
        } catch {
          if (pollGenRef.current !== gen) return;
          // Also check POS order directly in case tender settled server-side
          try {
            const ord = await api<PosOrder>(`/v1/pos/orders/${orderObj.id}`);
            if (pollGenRef.current !== gen) return;
            if (isTerminalStatusPaid(ord.status) || ord.paidPesewas > orderObj.paidPesewas) {
              await completeDigitalOrder(orderObj, false);
            }
          } catch {
            // Transient poll error: keep polling until cancelled or settled
          }
        }
      })();
    }, 3000);
  }, [completeDigitalOrder, stopPolling]);

  const cashKeyRef = useRef<{ sig: string; key: string } | null>(null);
  const bankKeyRef = useRef<{ sig: string; key: string } | null>(null);

  async function payCash() {
    setError(null);
    setPartialNotice(null);
    const cash = parseMoney(cashInput);
    if (cash === null || cash <= 0) {
      setError("Enter the cash received, like 50.00");
      return;
    }
    if (cash < remainingPesewas) {
      setError(`Cash received is less than ${formatMoney(remainingPesewas)}`);
      return;
    }
    setBusy(true);
    let currentOrderId: string | null = null;
    try {
      const ord = await ensureOrder();
      currentOrderId = ord.id;
      setActiveOrder(ord);
      const duePesewas = computeTargetTenderAmount({
        order: ord,
        totalPesewas,
        splitAmountPesewas,
      });
      if (cash < duePesewas) {
        setError(`Cash received is less than remaining balance ${formatMoney(duePesewas)}`);
        return;
      }
      const sig = `${ord.id}:${splitKey ?? "full"}:${ord.paidPesewas}:${cash}`;
      if (cashKeyRef.current?.sig !== sig) {
        cashKeyRef.current = { sig, key: newKey() };
      }
      const paid = await api<PosOrder>(`/v1/pos/orders/${ord.id}/payments`, {
        body: {
          tenderType: "CASH",
          amountPesewas: cash,
          reference: null,
          ...(splitKey ? { splitKey } : {}),
        },
        idempotencyKey: cashKeyRef.current.key,
      });
      cashKeyRef.current = null;
      await handleSettledOrder(paid);
    } catch (err) {
      if (isFeatureNotEnabled(err)) { onModuleDisabled(); return; }
      if (err instanceof ApiError && err.status === 409 && err.code === "order_paid" && currentOrderId) {
        try {
          const latest = await api<PosOrder>(`/v1/pos/orders/${currentOrderId}`);
          if (latest.status === "PAID") {
            cashKeyRef.current = null;
            setActiveOrder(latest);
            await onOrderPaid(latest);
            return;
          }
        } catch {
          // fall through to show original error
        }
      }
      setError(`Sale not recorded: ${err instanceof ApiError ? err.message : String(err)}`);
    } finally {
      setBusy(false);
    }
  }

  async function payBankTransfer() {
    setError(null);
    setPartialNotice(null);
    const check = validateBankTransferInput({
      amountInput: bankAmountInput,
      reference: bankReference,
      maxAmountPesewas: remainingPesewas,
    });
    if (!check.ok) {
      setError(check.error);
      return;
    }
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const duePesewas = computeTargetTenderAmount({
        order: ord,
        totalPesewas,
        splitAmountPesewas,
      });
      const finalCheck = validateBankTransferInput({
        amountInput: bankAmountInput,
        reference: bankReference,
        maxAmountPesewas: duePesewas,
      });
      if (!finalCheck.ok) {
        setError(finalCheck.error);
        return;
      }
      const extType: ExternalTenderMode = isExternalMode(mode) ? mode : "BANK_TRANSFER";
      const extLabel = EXTERNAL_TENDER_META[extType].label;
      const sig = `${extType}:${ord.id}:${splitKey ?? "full"}:${ord.paidPesewas}:${finalCheck.amountPesewas}:${finalCheck.reference}`;
      if (bankKeyRef.current?.sig !== sig) {
        bankKeyRef.current = { sig, key: newKey() };
      }
      const res = await api<TenderInitiation>(`/v1/pos/orders/${ord.id}/tenders`, {
        body: {
          tenderType: extType,
          amountPesewas: finalCheck.amountPesewas,
          reference: finalCheck.reference,
          ...(splitKey ? { splitKey } : {}),
        },
        idempotencyKey: bankKeyRef.current.key,
      });
      bankKeyRef.current = null;
      const normalized = normalizeTenderResult(res);
      const settled = normalized.order ?? (await api<PosOrder>(`/v1/pos/orders/${ord.id}`));
      const left = Math.max(0, settled.totalPesewas - settled.paidPesewas);
      await handleSettledOrder(
        settled,
        `${extLabel} (${finalCheck.reference}) recorded. Remaining balance: ${formatMoney(left)}.`,
      );
    } catch (err) {
      if (isFeatureNotEnabled(err)) { onModuleDisabled(); return; }
      setError(`External tender not recorded: ${err instanceof ApiError ? err.message : String(err)}`);
    } finally {
      setBusy(false);
    }
  }

  async function startMomoPush() {
    setError(null);
    setPartialNotice(null);
    const msisdn = normalizeGhanaPhone(momoPhone);
    if (!msisdn) {
      setError("Enter a valid Ghana MoMo number, e.g. 024 123 4567");
      return;
    }
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const duePesewas = computeTargetTenderAmount({
        order: ord,
        totalPesewas,
        splitAmountPesewas,
      });
      let usedFallback = false;
      let ref: string | null = null;
      try {
        const res = await api<TenderInitiation>(`/v1/pos/orders/${ord.id}/tenders`, {
          body: {
            tenderType: "MOMO",
            network: momoNetwork,
            phone: msisdn,
            amountPesewas: duePesewas,
            ...(splitKey ? { splitKey } : {}),
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
    setPartialNotice(null);
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const duePesewas = computeTargetTenderAmount({
        order: ord,
        totalPesewas,
        splitAmountPesewas,
      });
      let usedFallback = false;
      let ref: string | null = null;
      let emvPayload: string | null = null;
      let resolvedQrRef: string | null = null;
      try {
        const res = await api<TenderInitiation>(`/v1/pos/orders/${ord.id}/tenders`, {
          body: {
            tenderType: "GHQR",
            amountPesewas: duePesewas,
            ...(splitKey ? { splitKey } : {}),
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
          emvPayload = dc.qrPayload ?? dc.payUrl ?? dc.reference;
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
    setPartialNotice(null);
    setNfcResult(null);
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const duePesewas = computeTargetTenderAmount({
        order: ord,
        totalPesewas,
        splitAmountPesewas,
      });
      const res = await api<TenderInitiation>(`/v1/pos/orders/${ord.id}/tenders`, {
        body: {
          tenderType: "CARD",
          amountPesewas: duePesewas,
          ...(splitKey ? { splitKey } : {}),
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
      setCardPayUrl(safeRedirectUrl(normalized.payUrl));
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
    setPartialNotice(null);
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
      const duePesewas = computeTargetTenderAmount({
        order: ord,
        totalPesewas,
        splitAmountPesewas,
      });

      const res = await api<NfcTapResult>(`/v1/pos/orders/${ord.id}/nfc-tap`, {
        body: {
          terminalId,
          ksn,
          emvTlvHex,
          maskedPan: nfcMaskedPan.trim() || null,
          amountPesewas: duePesewas,
          currency: "GHS",
          ...(splitKey ? { splitKey } : {}),
        },
        idempotencyKey: newKey(),
      });
      const normalized = normalizeNfcTapResult(res);
      setNfcResult(normalized);
      if (isTerminalStatusPaid(normalized.status) || isTerminalStatusPaid(normalized.order?.status)) {
        await handleSettledOrder(normalized.order ?? (await api<PosOrder>(`/v1/pos/orders/${ord.id}`)));
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
      const cancelPath = splitKey
        ? `/v1/pos/orders/${activeOrder.id}/digital-checkout/cancel?splitKey=${encodeURIComponent(splitKey)}`
        : `/v1/pos/orders/${activeOrder.id}/digital-checkout/cancel`;
      await api(cancelPath, { method: "POST" }).catch(() => {});
    }
    setPendingRef(null);
    setCheckoutRef(null);
    setPollStatus(null);
    setQrPayload(null);
    setQrReference(null);
    setCardPayUrl(null);
    setNfcResult(null);
    setVerifyNotice(null);
  }

  const parsedCash = parseMoney(cashInput);
  const cashChange = parsedCash !== null && parsedCash >= remainingPesewas ? parsedCash - remainingPesewas : null;
  const parsedBankAmt = bankAmountInput.trim() ? parseMoney(bankAmountInput) : remainingPesewas;

  return (
    <div style={{ marginTop: "0.9rem", paddingTop: "0.9rem", borderTop: "1px solid var(--line)" }}>
      {splitKey && (
        <p className="notice" style={{ margin: "0 0 0.65rem" }}>
          Paying split <strong>{splitLabel ?? splitKey}</strong> · Amount due:{" "}
          <strong className="mono">{formatMoney(remainingPesewas)}</strong>
        </p>
      )}
      <div className="tender-tabs" role="tablist" aria-label="Payment tender">
        <button type="button" className={mode === "CASH" ? "active" : ""} onClick={() => { setMode("CASH"); setError(null); }}>
          <IconBanknote size={17} />
          Cash
        </button>
        <button type="button" className={mode === "EXTERNAL_MOMO" ? "active" : ""} onClick={() => { setMode("EXTERNAL_MOMO"); setError(null); }}>
          <IconSmartphone size={17} />
          Own MoMo
        </button>
        <button type="button" className={mode === "EXTERNAL_POS" ? "active" : ""} onClick={() => { setMode("EXTERNAL_POS"); setError(null); }}>
          <IconCreditCard size={17} />
          Ext POS
        </button>
        <button type="button" className={mode === "EXTERNAL_CARD" ? "active" : ""} onClick={() => { setMode("EXTERNAL_CARD"); setError(null); }}>
          <IconCreditCard size={17} />
          Ext Card
        </button>
        <button type="button" className={mode === "BANK_TRANSFER" ? "active" : ""} onClick={() => { setMode("BANK_TRANSFER"); setError(null); }}>
          <IconLandmark size={17} />
          Bank
        </button>
        <button type="button" className={mode === "MOMO" ? "active" : ""} onClick={() => { setMode("MOMO"); setError(null); }}>
          <IconSmartphone size={17} />
          ELEPAY MoMo
        </button>
        <button type="button" className={mode === "GHQR" ? "active" : ""} onClick={() => { setMode("GHQR"); setError(null); }}>
          <IconQrCode size={17} />
          ELEPAY GhQR
        </button>
        <button type="button" className={mode === "NFC_CARD" ? "active" : ""} onClick={() => { setMode("NFC_CARD"); setError(null); }}>
          <IconCreditCard size={17} />
          ELEPAY Card
        </button>
      </div>

      {error && <p className="notice error" role="alert">{error}</p>}
      {partialNotice && <p className="notice ok" role="status">{partialNotice}</p>}

      {pendingRef ? (
        <div className="checkout-status-card processing" style={{ textAlign: "left" }}>
          <div className="flex-row" style={{ marginBottom: "0.5rem" }}>
            <span className="spinner" />
            <div>
              <strong>Awaiting live rail confirmation</strong>
              <div className="small">
                Ref <span className="mono">{pendingRef}</span>
                {pollStatus && <> · Status: <strong>{pollStatus}</strong></>}
              </div>
            </div>
          </div>
          <p className="small muted" style={{ marginBottom: "0.65rem" }}>Auto-polling every 3 seconds…</p>
          {mode === "MOMO" && (
            <p className="notice warn" style={{ margin: "0 0 0.65rem" }}>
              <strong>Customer approval required:</strong> Ask customer to enter MoMo PIN, or dial{" "}
              <strong className="mono">*170# → 6 (My Wallet) → 3 (My Approvals)</strong> if no prompt appeared.
            </p>
          )}
          {verifyNotice && <p className="notice warn" role="status" style={{ marginBottom: "0.65rem" }}>{verifyNotice}</p>}
          {qrImage && (
            <div className="qr-frame" style={{ margin: "0.65rem 0" }}>
              <img src={qrImage} alt="EMVCo GhQR code" width={200} height={200} />
              {qrReference && <p className="small mono" style={{ margin: "0.25rem 0 0" }}>GhQR Ref: {qrReference}</p>}
            </div>
          )}
          {qrPayload && (
            <details style={{ marginBottom: "0.65rem" }}>
              <summary className="small muted" style={{ cursor: "pointer" }}>EMVCo Payload</summary>
              <p className="small mono" style={{ wordBreak: "break-all", marginTop: "0.3rem" }}>{qrPayload}</p>
            </details>
          )}
          {cardPayUrl && (
            <p className="small" style={{ marginBottom: "0.65rem" }}>
              <a href={cardPayUrl} target="_blank" rel="noreferrer" className="flex-row" style={{ gap: "0.35rem" }}>
                Open Card Checkout Link <IconExternalLink size={14} />
              </a>
            </p>
          )}
          <div className="flex-row">
            {checkoutRef && activeOrder && (
              <button type="button" onClick={() => void completeDigitalOrder(activeOrder, usedFallbackRef.current)} disabled={busy}>
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
              <label style={{ marginTop: 0 }}>
                Cash received (GH₵)
                <input
                  className="mono"
                  inputMode="decimal"
                  value={cashInput}
                  onChange={(e) => setCashInput(e.target.value)}
                  placeholder={(remainingPesewas / 100).toFixed(2)}
                />
              </label>
              <div className="flex-row" style={{ marginBottom: "0.65rem", gap: "0.35rem" }}>
                <button type="button" className="secondary small" style={{ margin: 0 }} onClick={() => setCashInput((remainingPesewas / 100).toFixed(2))}>
                  Exact ({formatMoney(remainingPesewas)})
                </button>
                {[1000, 2000, 5000, 10000].map((add) => (
                  <button
                    key={add}
                    type="button"
                    className="secondary small"
                    style={{ margin: 0 }}
                    onClick={() => setCashInput(((remainingPesewas + add) / 100).toFixed(2))}
                  >
                    +{formatMoney(add)}
                  </button>
                ))}
              </div>
              {cashChange !== null && (
                <div className="change-banner">
                  <span>Change due</span>
                  <strong className="mono">{formatMoney(cashChange)}</strong>
                </div>
              )}
              <div className="flex-row">
                <button type="button" className="large" style={{ flex: 1, margin: 0 }} onClick={() => void payCash()} disabled={busy}>
                  {busy ? "Recording…" : `Charge Cash ${formatMoney(remainingPesewas)}`}
                </button>
                {!hasOrder && onClearCart && (
                  <button type="button" className="secondary large" style={{ margin: 0 }} onClick={onClearCart}>Clear</button>
                )}
              </div>
            </div>
          )}

          {isExternalMode(mode) && (
            <div>
              <p className="small muted" style={{ marginTop: 0 }}>
                {EXTERNAL_TENDER_META[mode].hint}
              </p>
              <div className="grid-2">
                <label style={{ marginTop: 0 }}>
                  {EXTERNAL_TENDER_META[mode].label} Amount (GH₵)
                  <input
                    className="mono"
                    inputMode="decimal"
                    value={bankAmountInput}
                    onChange={(e) => setBankAmountInput(e.target.value)}
                    placeholder={(remainingPesewas / 100).toFixed(2)}
                  />
                </label>
                <label style={{ marginTop: 0 }}>
                  {EXTERNAL_TENDER_META[mode].label} Reference (required)
                  <input
                    className="mono"
                    value={bankReference}
                    onChange={(e) => setBankReference(e.target.value)}
                    maxLength={128}
                    placeholder={EXTERNAL_TENDER_META[mode].placeholder}
                    required
                  />
                </label>
              </div>
              <div className="flex-row" style={{ marginBottom: "0.65rem", gap: "0.35rem" }}>
                <button type="button" className="secondary small" style={{ margin: 0 }} onClick={() => setBankAmountInput((remainingPesewas / 100).toFixed(2))}>
                  Exact ({formatMoney(remainingPesewas)})
                </button>
              </div>
              <button type="button" className="large full-width" onClick={() => void payBankTransfer()} disabled={busy}>
                <IconLandmark size={17} />
                {busy
                  ? "Recording…"
                  : `Record Bank Transfer (${formatMoney(parsedBankAmt ?? remainingPesewas)})`}
              </button>
            </div>
          )}

          {mode === "MOMO" && (
            <div>
              <div className="grid-2">
                <label style={{ marginTop: 0 }}>
                  Network
                  <select value={momoNetwork} onChange={(e) => setMomoNetwork(e.target.value as MomoNetwork)}>
                    {MOMO_NETWORKS.map((n) => (
                      <option key={n.value} value={n.value}>{n.label}</option>
                    ))}
                  </select>
                </label>
                <label style={{ marginTop: 0 }}>
                  Customer MoMo Phone
                  <input
                    className="mono"
                    inputMode="tel"
                    value={momoPhone}
                    onChange={(e) => setMomoPhone(e.target.value)}
                    placeholder="024 123 4567"
                  />
                </label>
              </div>
              <button type="button" className="large full-width" onClick={() => void startMomoPush()} disabled={busy} style={{ marginTop: "0.5rem" }}>
                {busy ? "Sending Prompt…" : `Send MoMo Push (${formatMoney(remainingPesewas)})`}
              </button>
            </div>
          )}

          {mode === "GHQR" && (
            <div>
              <p className="small muted">Generate a dynamic EMVCo GhQR code for the customer to scan with any Ghana bank or MoMo app.</p>
              <button type="button" className="large full-width" onClick={() => void startGhQr()} disabled={busy}>
                <IconQrCode size={17} />
                {busy ? "Generating GhQR…" : `Display GhQR (${formatMoney(remainingPesewas)})`}
              </button>
            </div>
          )}

          {mode === "NFC_CARD" && (
            <div>
              <p className="small muted">
                Pay via online Card Checkout, or submit hardware DUKPT/EMV NFC tap data{terminalId ? ` (Terminal ${terminalId.slice(0, 8)})` : ""}.
              </p>
              <button type="button" className="large full-width" onClick={() => void startCardCheckout()} disabled={busy} style={{ marginBottom: "0.85rem" }}>
                <IconCreditCard size={17} />
                {busy ? "Creating Card Checkout…" : `Charge Card via Checkout (${formatMoney(remainingPesewas)})`}
              </button>
              <details>
                <summary className="small muted" style={{ cursor: "pointer", fontWeight: 600 }}>Hardware NFC Tap-to-Pay (DUKPT / EMV TLV)</summary>
                <div style={{ marginTop: "0.6rem" }}>
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
                    {busy ? "Authorizing Tap…" : `Initiate NFC Tap-to-Pay (${formatMoney(remainingPesewas)})`}
                  </button>
                </div>
              </details>
              {nfcResult && (
                <p className="notice" style={{ marginTop: "0.6rem" }}>
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
