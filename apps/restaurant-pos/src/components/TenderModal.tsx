import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  ActivityIndicator,
  Linking,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import QRCode from "react-native-qrcode-svg";
import {
  ApiError,
  colors,
  ElepayClient,
  formatMoney,
  isTerminalStatusFailed,
  isTerminalStatusPaid,
  MOMO_NETWORKS,
  MomoNetwork,
  newKey,
  normalizeGhanaPhone,
  normalizeTenderResult,
  parseMoney,
  PosOrder,
  PosOrderItem,
  radius,
  spacing,
  TenderInitiation,
  TenderMode,
} from "@elepay/mobile-core";

const POLL_INTERVAL_MS = 3000;
const POLL_MAX_MS = 5 * 60 * 1000;

type ExternalTenderMode = "BANK_TRANSFER" | "EXTERNAL_MOMO" | "EXTERNAL_HUBTEL" | "EXTERNAL_CARD";
type ModalTenderMode = TenderMode | ExternalTenderMode;

const EXTERNAL_META: Record<ExternalTenderMode, { label: string; placeholder: string; hint: string }> = {
  BANK_TRANSFER: {
    label: "Bank transfer",
    placeholder: "e.g. GIP-20261127-99412",
    hint: "Record a verified bank transfer (outside the cash drawer; not settled by ELEPAY).",
  },
  EXTERNAL_MOMO: {
    label: "Own MoMo",
    placeholder: "e.g. MTN-TXN-8841209",
    hint: "Record payment received on your own MoMo SIM (outside cash drawer; ELEPAY never touches funds).",
  },
  EXTERNAL_HUBTEL: {
    label: "Hubtel",
    placeholder: "e.g. HUBTEL-POS-441092",
    hint: "Record payment settled on your own Hubtel terminal (outside cash drawer; ELEPAY never touches funds).",
  },
  EXTERNAL_CARD: {
    label: "External card",
    placeholder: "e.g. RRN-00492184",
    hint: "Record payment swiped on your own bank POS card terminal (outside cash drawer; ELEPAY never touches funds).",
  },
};

function isExternalMode(m: ModalTenderMode): m is ExternalTenderMode {
  return m === "BANK_TRANSFER" || m === "EXTERNAL_MOMO" || m === "EXTERNAL_HUBTEL" || m === "EXTERNAL_CARD";
}

export interface ModalSubBill {
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

export interface ModalSplitPlan {
  orderId: string;
  orderStatus: string;
  totalPesewas: number;
  paidPesewas: number;
  splitType: string | null;
  subBills: ModalSubBill[];
}

function normalizeSplitPlan(raw: {
  orderId: string;
  orderStatus: string;
  totalPesewas: number;
  paidPesewas: number;
  splitType?: string | null;
  subBills?: ModalSubBill[];
  splits?: ModalSubBill[];
}): ModalSplitPlan {
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
          ? s.remainingPesewas
          : Math.max(0, s.amountPesewas - (s.paidPesewas ?? 0)),
    })),
  };
}

interface Props {
  visible: boolean;
  onClose: () => void;
  client: ElepayClient;
  merchantId: string;
  totalPesewas: number;
  ensureOrder: () => Promise<PosOrder>;
  onOrderPaid: (order: PosOrder) => Promise<void>;
  initialOrder?: PosOrder | null;
  onOrderUpdated?: (order: PosOrder) => void;
  splitKey?: string | null;
  splitAmountPesewas?: number | null;
}

export function TenderModal({
  visible,
  onClose,
  client,
  totalPesewas,
  ensureOrder,
  onOrderPaid,
  initialOrder = null,
  onOrderUpdated,
  splitKey: propSplitKey = null,
  splitAmountPesewas: propSplitAmountPesewas = null,
}: Props) {
  const [mode, setMode] = useState<ModalTenderMode>("CASH");
  const [cashInput, setCashInput] = useState("");
  const [bankAmountInput, setBankAmountInput] = useState("");
  const [bankReference, setBankReference] = useState("");
  const [momoNetwork, setMomoNetwork] = useState<MomoNetwork>("mtn");
  const [momoPhone, setMomoPhone] = useState("");
  const [activeOrder, setActiveOrder] = useState<PosOrder | null>(initialOrder);
  const [pendingRef, setPendingRef] = useState<string | null>(null);
  const [pollStatus, setPollStatus] = useState<string | null>(null);
  const [qrPayload, setQrPayload] = useState<string | null>(null);
  const [cardPayUrl, setCardPayUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // Split bill state
  const [showSplit, setShowSplit] = useState(false);
  const [splitTab, setSplitTab] = useState<"EVEN" | "SEAT" | "ITEM">("EVEN");
  const [splitCountInput, setSplitCountInput] = useState("2");
  const [splitPlan, setSplitPlan] = useState<ModalSplitPlan | null>(null);
  const [selectedSplitKey, setSelectedSplitKey] = useState<string | null>(propSplitKey);
  const [orderItems, setOrderItems] = useState<PosOrderItem[]>([]);
  const [itemBillCount, setItemBillCount] = useState(2);
  const [itemAssignments, setItemAssignments] = useState<Record<string, number>>({});

  const pollRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pollGenRef = useRef(0);
  const completingRef = useRef(false);
  const usedFallbackRef = useRef(false);
  // Stable idempotency key per logical attempt: a retry after a timeout must reuse the
  // same key so the server replays instead of pushing a second charge.
  const attemptKeyRef = useRef<{ sig: string; key: string } | null>(null);
  const keyFor = (sig: string) => {
    if (attemptKeyRef.current?.sig !== sig) attemptKeyRef.current = { sig, key: newKey() };
    return attemptKeyRef.current.key;
  };

  const stopPolling = useCallback(() => {
    pollGenRef.current += 1;
    if (pollRef.current) {
      clearTimeout(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  useEffect(() => () => stopPolling(), [stopPolling]);

  useEffect(() => {
    if (visible) {
      if (initialOrder) setActiveOrder(initialOrder);
      if (propSplitKey !== null) setSelectedSplitKey(propSplitKey);
    } else {
      stopPolling();
      usedFallbackRef.current = false;
      attemptKeyRef.current = null;
      setActiveOrder(null);
      setPendingRef(null);
      setPollStatus(null);
      setQrPayload(null);
      setCardPayUrl(null);
      setCashInput("");
      setBankAmountInput("");
      setBankReference("");
      setMomoPhone("");
      setError(null);
      setNotice(null);
      setShowSplit(false);
      setSplitPlan(null);
      setSelectedSplitKey(null);
      setOrderItems([]);
      setItemAssignments({});
    }
  }, [visible, initialOrder, propSplitKey, stopPolling]);

  const effectiveSplitKey = selectedSplitKey ?? propSplitKey ?? null;
  const activeSubBill = splitPlan
    ? splitPlan.subBills.find((s) => s.splitKey === effectiveSplitKey) ?? null
    : null;
  const orderRemaining = activeOrder
    ? Math.max(0, activeOrder.totalPesewas - activeOrder.paidPesewas)
    : totalPesewas;
  const remainingDue = activeSubBill
    ? Math.max(0, activeSubBill.remainingPesewas)
    : propSplitAmountPesewas != null
      ? Math.max(0, propSplitAmountPesewas)
      : orderRemaining;

  const refreshSplitPlan = useCallback(
    async (orderId: string) => {
      try {
        const raw = await client.call<{
          orderId: string;
          orderStatus: string;
          totalPesewas: number;
          paidPesewas: number;
          splitType?: string | null;
          subBills?: ModalSubBill[];
          splits?: ModalSubBill[];
        }>(`/v1/pos/orders/${orderId}/splits`);
        const norm = normalizeSplitPlan(raw);
        setSplitPlan(norm);
        if (norm.subBills.length > 0) {
          const currentUnpaid = norm.subBills.find(
            (s) => s.splitKey === selectedSplitKey && s.status !== "PAID" && s.remainingPesewas > 0,
          );
          if (!currentUnpaid) {
            const nextUnpaid = norm.subBills.find((s) => s.status !== "PAID" && s.remainingPesewas > 0);
            setSelectedSplitKey(nextUnpaid?.splitKey ?? null);
          }
        }
      } catch {
        // Ignore if splits endpoint not active or order has no splits yet
      }
    },
    [client, selectedSplitKey],
  );

  const handleSettledOrder = useCallback(
    async (settledOrder: PosOrder, successMessage?: string) => {
      setActiveOrder(settledOrder);
      onOrderUpdated?.(settledOrder);
      attemptKeyRef.current = null;
      stopPolling();
      usedFallbackRef.current = false;
      setPendingRef(null);
      setPollStatus(null);
      setQrPayload(null);
      setCardPayUrl(null);
      setCashInput("");
      setBankAmountInput("");
      setBankReference("");

      if (settledOrder.status === "PAID") {
        setNotice(null);
        await onOrderPaid(settledOrder);
        return;
      }

      if (settledOrder.status === "PARTIAL" || settledOrder.paidPesewas > 0) {
        const left = Math.max(0, settledOrder.totalPesewas - settledOrder.paidPesewas);
        await refreshSplitPlan(settledOrder.id);
        setNotice(successMessage ?? `Payment recorded. Remaining balance: ${formatMoney(left)}.`);
        return;
      }

      setNotice("Payment not confirmed yet — still waiting for rail confirmation.");
    },
    [onOrderPaid, onOrderUpdated, refreshSplitPlan, stopPolling],
  );

  const completeDigitalOrder = useCallback(
    async (ord: PosOrder, usedFallback: boolean, isManualVerify = false) => {
      if (completingRef.current) return;
      completingRef.current = true;
      const gen = pollGenRef.current;
      setError(null);
      setNotice(null);
      const confirmPath = effectiveSplitKey
        ? `/v1/pos/orders/${ord.id}/digital-checkout/confirm?splitKey=${encodeURIComponent(effectiveSplitKey)}`
        : `/v1/pos/orders/${ord.id}/digital-checkout/confirm`;
      try {
        let paidOrder: PosOrder;
        if (usedFallback) {
          paidOrder = await client.call<PosOrder>(confirmPath, { method: "POST" });
        } else {
          const refreshed = await client.call<PosOrder>(`/v1/pos/orders/${ord.id}`);
          if (pollGenRef.current !== gen) return;
          if (refreshed.status === "PAID" || refreshed.paidPesewas > ord.paidPesewas) {
            paidOrder = refreshed;
          } else {
            paidOrder = await client.call<PosOrder>(confirmPath, { method: "POST" });
          }
        }
        if (pollGenRef.current !== gen) return;
        if (paidOrder.status !== "PAID" && !(paidOrder.status === "PARTIAL" && paidOrder.paidPesewas > ord.paidPesewas)) {
          setNotice("Payment not confirmed yet — still waiting for rail confirmation.");
          return;
        }
        await handleSettledOrder(paidOrder);
      } catch (e) {
        if (pollGenRef.current !== gen) return;
        if (isManualVerify && e instanceof ApiError && e.status === 409) {
          setNotice("Payment not confirmed yet — still waiting for rail confirmation.");
        } else {
          setError(e instanceof Error ? e.message : String(e));
        }
      } finally {
        completingRef.current = false;
      }
    },
    [client, effectiveSplitKey, handleSettledOrder],
  );

  const startPolling = useCallback(
    (ord: PosOrder, refToPoll: string, usedFallback: boolean) => {
      stopPolling();
      const gen = ++pollGenRef.current;
      const deadline = Date.now() + POLL_MAX_MS;
      usedFallbackRef.current = usedFallback;
      setPollStatus("PENDING");
      // Self-scheduling loop: one request in flight at a time, bounded duration.
      const tick = async () => {
        if (pollGenRef.current !== gen) return;
        try {
          const st = await client.call<{ status: string; railStatus?: string | null; message?: string | null }>(
            `/v1/public/checkouts/${refToPoll}/status`,
            { anonymous: true },
          );
          if (pollGenRef.current !== gen) return;
          setPollStatus(st.railStatus && st.status.toLowerCase() === "open" ? `${st.status} (${st.railStatus})` : st.status);
          if (isTerminalStatusPaid(st.status)) {
            await completeDigitalOrder(ord, usedFallback, false);
          } else if (
            isTerminalStatusFailed(st.status) ||
            (st.status.toLowerCase() === "open" && isTerminalStatusFailed(st.railStatus))
          ) {
            stopPolling();
            const failedLabel = (st.railStatus || st.status).toLowerCase();
            setError(st.message || `Payment ${failedLabel}.`);
            return;
          }
        } catch {
          if (pollGenRef.current !== gen) return;
          try {
            const latest = await client.call<PosOrder>(`/v1/pos/orders/${ord.id}`);
            if (pollGenRef.current !== gen) return;
            if (isTerminalStatusPaid(latest.status) || latest.paidPesewas > ord.paidPesewas) {
              await completeDigitalOrder(ord, usedFallback, false);
            }
          } catch {
            // Keep polling across transient drops
          }
        }
        if (pollGenRef.current !== gen) return;
        if (Date.now() >= deadline) {
          pollRef.current = null;
          setNotice("Still no rail confirmation. Ask the customer to check their wallet, then tap Verify Now.");
          return;
        }
        pollRef.current = setTimeout(() => void tick(), POLL_INTERVAL_MS);
      };
      pollRef.current = setTimeout(() => void tick(), POLL_INTERVAL_MS);
    },
    [client, completeDigitalOrder, stopPolling],
  );

  async function toggleSplitDrawer() {
    setError(null);
    setNotice(null);
    if (showSplit) {
      setShowSplit(false);
      return;
    }
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      onOrderUpdated?.(ord);
      await refreshSplitPlan(ord.id);
      const items = await client.call<PosOrderItem[]>(`/v1/pos/orders/${ord.id}/items`).catch(() => []);
      setOrderItems(items);
      setItemAssignments((prev) => {
        const next: Record<string, number> = { ...prev };
        for (const it of items) {
          if (!next[it.id]) next[it.id] = 1;
        }
        return next;
      });
      setShowSplit(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function createEvenSplit() {
    setError(null);
    setNotice(null);
    const count = Number(splitCountInput);
    if (!Number.isInteger(count) || count < 2) {
      setError("Split count must be a whole number (at least 2).");
      return;
    }
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const raw = await client.call<ModalSplitPlan & { splits?: ModalSubBill[] }>(
        `/v1/pos/orders/${ord.id}/splits/even?count=${count}`,
        { method: "POST" },
      );
      const norm = normalizeSplitPlan(raw);
      setSplitPlan(norm);
      const firstUnpaid = norm.subBills.find((s) => s.status !== "PAID" && s.remainingPesewas > 0);
      setSelectedSplitKey(firstUnpaid?.splitKey ?? null);
      setNotice(`Split evenly into ${norm.subBills.length} bills.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function createSeatSplit() {
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const raw = await client.call<ModalSplitPlan & { splits?: ModalSubBill[] }>(
        `/v1/pos/orders/${ord.id}/splits/seats`,
        { method: "POST" },
      );
      const norm = normalizeSplitPlan(raw);
      setSplitPlan(norm);
      const firstUnpaid = norm.subBills.find((s) => s.status !== "PAID" && s.remainingPesewas > 0);
      setSelectedSplitKey(firstUnpaid?.splitKey ?? null);
      setNotice(`Split into ${norm.subBills.length} seat bill${norm.subBills.length === 1 ? "" : "s"}.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function createItemSplit() {
    setError(null);
    setNotice(null);
    if (orderItems.length === 0) {
      setError("No order items available to split.");
      return;
    }
    const groupsMap = new Map<number, string[]>();
    for (const it of orderItems) {
      const billIdx = itemAssignments[it.id] ?? 1;
      const list = groupsMap.get(billIdx) ?? [];
      list.push(it.id);
      groupsMap.set(billIdx, list);
    }
    const groups = Array.from(groupsMap.entries())
      .sort((a, b) => a[0] - b[0])
      .map(([billIdx, itemIds]) => ({
        label: `Bill ${billIdx}`,
        itemIds,
      }));
    if (groups.length < 1) {
      setError("Assign every item to a bill before splitting.");
      return;
    }
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const raw = await client.call<ModalSplitPlan & { splits?: ModalSubBill[] }>(
        `/v1/pos/orders/${ord.id}/splits/items`,
        {
          method: "POST",
          body: { groups },
        },
      );
      const norm = normalizeSplitPlan(raw);
      setSplitPlan(norm);
      const firstUnpaid = norm.subBills.find((s) => s.status !== "PAID" && s.remainingPesewas > 0);
      setSelectedSplitKey(firstUnpaid?.splitKey ?? null);
      setNotice(`Split by item into ${norm.subBills.length} bill${norm.subBills.length === 1 ? "" : "s"}.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function payCash() {
    setError(null);
    setNotice(null);
    const cash = parseMoney(cashInput);
    if (cash === null || cash <= 0) {
      setError("Enter cash received, e.g. 50.00");
      return;
    }
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const duePesewas = activeSubBill
        ? Math.max(0, activeSubBill.remainingPesewas)
        : propSplitAmountPesewas != null
          ? Math.max(0, propSplitAmountPesewas)
          : Math.max(0, ord.totalPesewas - ord.paidPesewas);
      if (cash < duePesewas) {
        setError(`Cash received is less than ${formatMoney(duePesewas)}`);
        return;
      }
      const paid = await client.call<PosOrder>(`/v1/pos/orders/${ord.id}/payments`, {
        idempotencyKey: keyFor(`cash:${ord.id}:${effectiveSplitKey ?? "full"}:${ord.paidPesewas}:${cash}`),
        body: {
          tenderType: "CASH",
          amountPesewas: cash,
          reference: null,
          ...(effectiveSplitKey ? { splitKey: effectiveSplitKey } : {}),
        },
      });
      await handleSettledOrder(paid);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function payBankTransfer() {
    setError(null);
    setNotice(null);
    const trimmedRef = bankReference.trim();
    if (!trimmedRef) {
      setError("Enter the bank transfer reference (e.g. GIP/NIP ref).");
      return;
    }
    if (trimmedRef.length > 128) {
      setError("Bank transfer reference must be 128 characters or fewer.");
      return;
    }
    const parsedAmt = bankAmountInput.trim() ? parseMoney(bankAmountInput) : remainingDue;
    if (parsedAmt === null || parsedAmt <= 0) {
      setError("Enter a valid transfer amount, e.g. 50.00");
      return;
    }
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const duePesewas = activeSubBill
        ? Math.max(0, activeSubBill.remainingPesewas)
        : propSplitAmountPesewas != null
          ? Math.max(0, propSplitAmountPesewas)
          : Math.max(0, ord.totalPesewas - ord.paidPesewas);
      const extType: ExternalTenderMode = isExternalMode(mode) ? mode : "BANK_TRANSFER";
      const extLabel = EXTERNAL_META[extType].label;
      if (parsedAmt > duePesewas) {
        setError(`${extLabel} amount cannot exceed balance due (${formatMoney(duePesewas)}).`);
        return;
      }
      const res = await client.call<TenderInitiation>(`/v1/pos/orders/${ord.id}/tenders`, {
        idempotencyKey: keyFor(`${extType}:${ord.id}:${effectiveSplitKey ?? "full"}:${ord.paidPesewas}:${parsedAmt}:${trimmedRef}`),
        body: {
          tenderType: extType,
          amountPesewas: parsedAmt,
          reference: trimmedRef,
          ...(effectiveSplitKey ? { splitKey: effectiveSplitKey } : {}),
        },
      });
      const norm = normalizeTenderResult(res);
      const settled = norm.order ?? (await client.call<PosOrder>(`/v1/pos/orders/${ord.id}`));
      await handleSettledOrder(
        settled,
        `${extLabel} (${trimmedRef}) recorded. Remaining: ${formatMoney(Math.max(0, settled.totalPesewas - settled.paidPesewas))}.`,
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function startMomoPush() {
    setError(null);
    setNotice(null);
    const msisdn = normalizeGhanaPhone(momoPhone);
    if (!msisdn) {
      setError("Enter a valid Ghana MoMo number, e.g. 024 123 4567");
      return;
    }
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const targetAmt = activeSubBill
        ? Math.max(0, activeSubBill.remainingPesewas)
        : propSplitAmountPesewas != null
          ? Math.max(0, propSplitAmountPesewas)
          : Math.max(0, ord.totalPesewas - ord.paidPesewas);
      let ref: string | null = null;
      let usedFallback = false;
      try {
        const res = await client.call<TenderInitiation>(`/v1/pos/orders/${ord.id}/tenders`, {
          idempotencyKey: keyFor(`momo:${ord.id}:${effectiveSplitKey ?? "full"}:${ord.paidPesewas}:${momoNetwork}:${msisdn}`),
          body: {
            tenderType: "MOMO",
            network: momoNetwork,
            phone: msisdn,
            amountPesewas: targetAmt,
            ...(effectiveSplitKey ? { splitKey: effectiveSplitKey } : {}),
          },
        });
        const norm = normalizeTenderResult(res);
        ref = norm.checkoutRef;
        if (isTerminalStatusPaid(norm.paymentStatus) || isTerminalStatusPaid(norm.orderStatus)) {
          await completeDigitalOrder(norm.order ?? ord, false, false);
          return;
        }
        if (isTerminalStatusFailed(norm.paymentStatus)) {
          throw new Error(norm.prompt ?? "MoMo payment failed.");
        }
      } catch (err) {
        if (err instanceof ApiError && err.status === 404) {
          usedFallback = true;
          const dc = await client.call<{ reference: string }>(`/v1/pos/orders/${ord.id}/digital-checkout`, { method: "POST" });
          ref = dc.reference;
          await client.call(`/v1/public/checkouts/${dc.reference}/pay`, {
            method: "POST",
            anonymous: true,
            idempotencyKey: keyFor(`pay:${dc.reference}:${momoNetwork}:${msisdn}`),
            body: { method: "momo", phone: msisdn, network: momoNetwork },
          });
        } else {
          throw err;
        }
      }
      if (!ref) throw new Error("No payment reference returned.");
      setPendingRef(ref);
      startPolling(ord, ref, usedFallback);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function startGhQr() {
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const targetAmt = activeSubBill
        ? Math.max(0, activeSubBill.remainingPesewas)
        : propSplitAmountPesewas != null
          ? Math.max(0, propSplitAmountPesewas)
          : Math.max(0, ord.totalPesewas - ord.paidPesewas);
      let ref: string | null = null;
      let payload: string | null = null;
      let usedFallback = false;
      try {
        const res = await client.call<TenderInitiation>(`/v1/pos/orders/${ord.id}/tenders`, {
          idempotencyKey: keyFor(`ghqr:${ord.id}:${effectiveSplitKey ?? "full"}:${ord.paidPesewas}`),
          body: {
            tenderType: "GHQR",
            amountPesewas: targetAmt,
            ...(effectiveSplitKey ? { splitKey: effectiveSplitKey } : {}),
          },
        });
        const norm = normalizeTenderResult(res);
        ref = norm.checkoutRef;
        payload = norm.qrPayload ?? norm.payUrl;
        if (isTerminalStatusPaid(norm.paymentStatus) || isTerminalStatusPaid(norm.orderStatus)) {
          await completeDigitalOrder(norm.order ?? ord, false, false);
          return;
        }
      } catch (err) {
        if (err instanceof ApiError && err.status === 404) {
          usedFallback = true;
          const dc = await client.call<{ reference: string; payUrl?: string | null; qrPayload?: string | null }>(
            `/v1/pos/orders/${ord.id}/digital-checkout`,
            { method: "POST" },
          );
          ref = dc.reference;
          payload = dc.qrPayload ?? dc.payUrl ?? dc.reference;
        } else {
          throw err;
        }
      }
      if (!ref) throw new Error("No GhQR reference returned.");
      setPendingRef(ref);
      setQrPayload(payload ?? ref);
      startPolling(ord, ref, usedFallback);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function startCard() {
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const targetAmt = activeSubBill
        ? Math.max(0, activeSubBill.remainingPesewas)
        : propSplitAmountPesewas != null
          ? Math.max(0, propSplitAmountPesewas)
          : Math.max(0, ord.totalPesewas - ord.paidPesewas);
      const res = await client.call<TenderInitiation>(`/v1/pos/orders/${ord.id}/tenders`, {
        idempotencyKey: keyFor(`card:${ord.id}:${effectiveSplitKey ?? "full"}:${ord.paidPesewas}`),
        body: {
          tenderType: "CARD",
          amountPesewas: targetAmt,
          ...(effectiveSplitKey ? { splitKey: effectiveSplitKey } : {}),
        },
      });
      const norm = normalizeTenderResult(res);
      if (isTerminalStatusPaid(norm.paymentStatus) || isTerminalStatusPaid(norm.orderStatus)) {
        await completeDigitalOrder(norm.order ?? ord, false, false);
        return;
      }
      if (!norm.checkoutRef) throw new Error("No card checkout reference returned.");
      setPendingRef(norm.checkoutRef);
      setCardPayUrl(norm.payUrl);
      startPolling(ord, norm.checkoutRef, false);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function cancelPending() {
    stopPolling();
    usedFallbackRef.current = false;
    if (activeOrder) {
      const cancelPath = effectiveSplitKey
        ? `/v1/pos/orders/${activeOrder.id}/digital-checkout/cancel?splitKey=${encodeURIComponent(effectiveSplitKey)}`
        : `/v1/pos/orders/${activeOrder.id}/digital-checkout/cancel`;
      await client.call(cancelPath, { method: "POST" }).catch(() => {});
    }
    setPendingRef(null);
    setPollStatus(null);
    setQrPayload(null);
    setCardPayUrl(null);
    setNotice(null);
  }

  async function handleClose() {
    if (pendingRef) {
      await cancelPending();
    }
    onClose();
  }

  const parsedCash = parseMoney(cashInput);
  const changeDue = parsedCash !== null && parsedCash >= remainingDue ? parsedCash - remainingDue : null;

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={() => void handleClose()}>
      <View style={styles.backdrop}>
        <View style={styles.sheet}>
          <View style={styles.header}>
            <View style={{ flex: 1 }}>
              <Text style={styles.title}>Collect Payment</Text>
              <Text style={styles.totalSub}>
                {activeSubBill ? `${activeSubBill.label} due: ` : "Total due: "}
                {formatMoney(remainingDue)}
                {activeOrder && activeOrder.paidPesewas > 0 && (
                  ` (Paid ${formatMoney(activeOrder.paidPesewas)} / ${formatMoney(activeOrder.totalPesewas)})`
                )}
              </Text>
            </View>
            <View style={styles.headerActions}>
              {!pendingRef && (
                <Pressable style={styles.splitToggleBtn} disabled={busy} onPress={() => void toggleSplitDrawer()}>
                  <Text style={styles.splitToggleText}>{showSplit ? "Hide Split" : "Split Bill"}</Text>
                </Pressable>
              )}
              <Pressable onPress={() => void handleClose()}>
                <Text style={styles.closeText}>Close</Text>
              </Pressable>
            </View>
          </View>

          <ScrollView showsVerticalScrollIndicator={false} keyboardShouldPersistTaps="handled">
            {showSplit && !pendingRef && (
              <View style={styles.splitBox}>
                <View style={styles.modeRow}>
                  {(
                    [
                      ["EVEN", "Even"],
                      ["SEAT", "By Seat"],
                      ["ITEM", "By Item"],
                    ] as const
                  ).map(([tab, label]) => (
                    <Pressable
                      key={tab}
                      style={[styles.modeBtn, splitTab === tab && styles.modeBtnActive]}
                      onPress={() => setSplitTab(tab)}
                    >
                      <Text style={[styles.modeBtnText, splitTab === tab && styles.modeBtnTextActive]}>{label}</Text>
                    </Pressable>
                  ))}
                </View>

                {splitTab === "EVEN" && (
                  <View style={styles.splitActionRow}>
                    <TextInput
                      style={[styles.input, { width: 72, marginBottom: 0, textAlign: "center" }]}
                      keyboardType="number-pad"
                      value={splitCountInput}
                      onChangeText={setSplitCountInput}
                    />
                    <Pressable style={[styles.secondaryBtn, { flex: 1, marginTop: 0 }]} disabled={busy} onPress={() => void createEvenSplit()}>
                      <Text style={styles.secondaryBtnText}>Create Even Split</Text>
                    </Pressable>
                  </View>
                )}

                {splitTab === "SEAT" && (
                  <Pressable style={[styles.secondaryBtn, { marginTop: 0 }]} disabled={busy} onPress={() => void createSeatSplit()}>
                    <Text style={styles.secondaryBtnText}>Create Split by Seat</Text>
                  </Pressable>
                )}

                {splitTab === "ITEM" && (
                  <View>
                    <View style={styles.splitActionRow}>
                      <Text style={styles.label}>Sub-bills: {itemBillCount}</Text>
                      <View style={styles.denomRow}>
                        <Pressable style={styles.denomChip} onPress={() => setItemBillCount((n) => Math.max(2, n - 1))}>
                          <Text style={styles.denomText}>-</Text>
                        </Pressable>
                        <Pressable style={styles.denomChip} onPress={() => setItemBillCount((n) => Math.min(8, n + 1))}>
                          <Text style={styles.denomText}>+</Text>
                        </Pressable>
                      </View>
                    </View>
                    {orderItems.map((it) => {
                      const assigned = itemAssignments[it.id] ?? 1;
                      return (
                        <View key={it.id} style={styles.splitItemRow}>
                          <View style={{ flex: 1 }}>
                            <Text style={styles.splitItemName}>
                              {it.quantity}× {it.itemName}
                            </Text>
                            <Text style={styles.pendingMeta}>{formatMoney(it.totalPesewas)}</Text>
                          </View>
                          <View style={styles.splitBillChips}>
                            {Array.from({ length: itemBillCount }, (_, idx) => idx + 1).map((billNum) => (
                              <Pressable
                                key={billNum}
                                style={[styles.denomChip, assigned === billNum && styles.modeBtnActive]}
                                onPress={() => setItemAssignments((prev) => ({ ...prev, [it.id]: billNum }))}
                              >
                                <Text style={[styles.denomText, assigned === billNum && styles.modeBtnTextActive]}>
                                  B{billNum}
                                </Text>
                              </Pressable>
                            ))}
                          </View>
                        </View>
                      );
                    })}
                    <Pressable style={styles.secondaryBtn} disabled={busy} onPress={() => void createItemSplit()}>
                      <Text style={styles.secondaryBtnText}>Save Item Split</Text>
                    </Pressable>
                  </View>
                )}

                {splitPlan && splitPlan.subBills.length > 0 && (
                  <View style={{ marginTop: 10, gap: 6 }}>
                    <Text style={styles.label}>Sub-bills (tap an unpaid bill to pay)</Text>
                    {splitPlan.subBills.map((sb) => {
                      const isPaid = sb.status === "PAID" || sb.remainingPesewas <= 0;
                      const isSelected = sb.splitKey === effectiveSplitKey;
                      return (
                        <Pressable
                          key={sb.splitKey}
                          disabled={isPaid}
                          style={[
                            styles.subBillCard,
                            isSelected && styles.subBillCardActive,
                            isPaid && styles.subBillCardPaid,
                          ]}
                          onPress={() => {
                            setSelectedSplitKey(sb.splitKey);
                            setCashInput("");
                            setBankAmountInput("");
                            setError(null);
                          }}
                        >
                          <View style={{ flex: 1 }}>
                            <Text style={styles.splitItemName}>{sb.label}</Text>
                            <Text style={styles.pendingMeta}>
                              Total {formatMoney(sb.amountPesewas)} · Paid {formatMoney(sb.paidPesewas)}
                            </Text>
                          </View>
                          <View style={{ alignItems: "flex-end" }}>
                            <Text style={styles.splitStatusBadge}>{sb.status}</Text>
                            {!isPaid && (
                              <Text style={styles.totalSub}>{formatMoney(sb.remainingPesewas)}</Text>
                            )}
                          </View>
                        </Pressable>
                      );
                    })}
                  </View>
                )}
              </View>
            )}

            <View style={styles.modeRow}>
              {(
                [
                  ["CASH", "Cash"],
                  ["EXTERNAL_MOMO", "Own MoMo"],
                  ["EXTERNAL_HUBTEL", "Hubtel"],
                  ["EXTERNAL_CARD", "Ext Card"],
                  ["BANK_TRANSFER", "Bank"],
                  ["MOMO", "ELEPAY MoMo"],
                  ["GHQR", "ELEPAY GhQR"],
                  ["CARD", "ELEPAY Card"],
                ] as const
              ).map(([k, label]) => (
                <Pressable
                  key={k}
                  style={[styles.modeBtn, mode === k && styles.modeBtnActive]}
                  onPress={() => {
                    setMode(k);
                    setError(null);
                    setNotice(null);
                  }}
                >
                  <Text style={[styles.modeBtnText, mode === k && styles.modeBtnTextActive]}>{label}</Text>
                </Pressable>
              ))}
            </View>

            {error && <Text style={styles.errorText}>{error}</Text>}
            {notice && <Text style={styles.noticeText}>{notice}</Text>}

            {pendingRef ? (
              <View style={styles.pendingBox}>
                <ActivityIndicator color={colors.info} />
                <Text style={styles.pendingTitle}>Awaiting live rail confirmation</Text>
                <Text style={styles.pendingMeta}>
                  Ref: {pendingRef} · Status: {pollStatus ?? "PENDING"}
                </Text>
                {mode === "MOMO" && (
                  <Text style={[styles.pendingMeta, { textAlign: "center", fontWeight: "700", color: colors.warn }]}>
                    Ask customer to approve prompt with MoMo PIN, or dial *170# → 6 (My Wallet) → 3 (My Approvals).
                  </Text>
                )}
                {qrPayload && (
                  <View style={styles.qrBox}>
                    <QRCode value={qrPayload} size={200} backgroundColor="#fff" color={colors.ink} />
                  </View>
                )}
                {cardPayUrl && (
                  <Pressable
                    style={styles.secondaryBtn}
                    onPress={() =>
                      void Linking.openURL(cardPayUrl).catch((e) =>
                        setError(e instanceof Error ? e.message : "Could not open card checkout link."),
                      )
                    }
                  >
                    <Text style={styles.secondaryBtnText}>Open Card Checkout Link</Text>
                  </Pressable>
                )}
                <View style={styles.row}>
                  {activeOrder && (
                    <Pressable
                      style={[styles.primaryBtn, { flex: 1 }]}
                      onPress={() => void completeDigitalOrder(activeOrder, usedFallbackRef.current, true)}
                    >
                      <Text style={styles.primaryBtnText}>Verify Now</Text>
                    </Pressable>
                  )}
                  <Pressable style={[styles.secondaryBtn, { flex: 1 }]} onPress={() => void cancelPending()}>
                    <Text style={styles.secondaryBtnText}>Cancel</Text>
                  </Pressable>
                </View>
              </View>
            ) : mode === "CASH" ? (
              <View>
                <Text style={styles.label}>Cash received (GH₵)</Text>
                <TextInput
                  style={styles.amountInput}
                  keyboardType="decimal-pad"
                  value={cashInput}
                  onChangeText={setCashInput}
                  placeholder={(remainingDue / 100).toFixed(2)}
                  placeholderTextColor={colors.subtle}
                />
                <View style={styles.denomRow}>
                  <Pressable style={styles.denomChip} onPress={() => setCashInput((remainingDue / 100).toFixed(2))}>
                    <Text style={styles.denomText}>Exact</Text>
                  </Pressable>
                  {[1000, 2000, 5000, 10000].map((add) => (
                    <Pressable
                      key={add}
                      style={styles.denomChip}
                      onPress={() => setCashInput(((remainingDue + add) / 100).toFixed(2))}
                    >
                      <Text style={styles.denomText}>+{formatMoney(add)}</Text>
                    </Pressable>
                  ))}
                </View>
                {changeDue !== null && (
                  <View style={styles.changeBanner}>
                    <Text style={styles.changeLabel}>Change due</Text>
                    <Text style={styles.changeValue}>{formatMoney(changeDue)}</Text>
                  </View>
                )}
                <Pressable style={styles.primaryBtn} disabled={busy} onPress={() => void payCash()}>
                  {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.primaryBtnText}>Charge Cash {formatMoney(remainingDue)}</Text>}
                </Pressable>
              </View>
            ) : isExternalMode(mode) ? (
              <View>
                <Text style={styles.mutedText}>
                  {EXTERNAL_META[mode].hint}
                </Text>
                <Text style={styles.label}>{EXTERNAL_META[mode].label} Amount (GH₵)</Text>
                <TextInput
                  style={styles.amountInput}
                  keyboardType="decimal-pad"
                  value={bankAmountInput}
                  onChangeText={setBankAmountInput}
                  placeholder={(remainingDue / 100).toFixed(2)}
                  placeholderTextColor={colors.subtle}
                />
                <View style={styles.denomRow}>
                  <Pressable style={styles.denomChip} onPress={() => setBankAmountInput((remainingDue / 100).toFixed(2))}>
                    <Text style={styles.denomText}>Exact ({formatMoney(remainingDue)})</Text>
                  </Pressable>
                </View>
                <Text style={styles.label}>{EXTERNAL_META[mode].label} Reference (required)</Text>
                <TextInput
                  style={styles.input}
                  value={bankReference}
                  onChangeText={setBankReference}
                  maxLength={128}
                  placeholder={EXTERNAL_META[mode].placeholder}
                  placeholderTextColor={colors.subtle}
                />
                <Pressable style={styles.primaryBtn} disabled={busy} onPress={() => void payBankTransfer()}>
                  {busy ? (
                    <ActivityIndicator color="#fff" />
                  ) : (
                    <Text style={styles.primaryBtnText}>
                      Record Bank Transfer ({formatMoney(bankAmountInput.trim() ? (parseMoney(bankAmountInput) ?? remainingDue) : remainingDue)})
                    </Text>
                  )}
                </Pressable>
              </View>
            ) : mode === "MOMO" ? (
              <View>
                <Text style={styles.label}>Mobile Money Network</Text>
                <View style={styles.modeRow}>
                  {MOMO_NETWORKS.map((n) => (
                    <Pressable
                      key={n.value}
                      style={[styles.modeBtn, momoNetwork === n.value && styles.modeBtnActive]}
                      onPress={() => setMomoNetwork(n.value)}
                    >
                      <Text style={[styles.modeBtnText, momoNetwork === n.value && styles.modeBtnTextActive]}>{n.label}</Text>
                    </Pressable>
                  ))}
                </View>
                <Text style={styles.label}>Customer MoMo Number</Text>
                <TextInput
                  style={styles.input}
                  keyboardType="phone-pad"
                  value={momoPhone}
                  onChangeText={setMomoPhone}
                  placeholder="024 123 4567"
                  placeholderTextColor={colors.subtle}
                />
                <Pressable style={styles.primaryBtn} disabled={busy} onPress={() => void startMomoPush()}>
                  {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.primaryBtnText}>Send MoMo Push ({formatMoney(remainingDue)})</Text>}
                </Pressable>
              </View>
            ) : mode === "GHQR" ? (
              <View>
                <Text style={styles.mutedText}>
                  Generate a dynamic EMVCo GhQR code for the customer to scan with any Ghana bank or MoMo app.
                </Text>
                <Pressable style={styles.primaryBtn} disabled={busy} onPress={() => void startGhQr()}>
                  {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.primaryBtnText}>Display GhQR ({formatMoney(remainingDue)})</Text>}
                </Pressable>
              </View>
            ) : (
              <View>
                <Text style={styles.mutedText}>
                  Create a live ELEPAY Card Checkout session for Visa / Mastercard payment.
                </Text>
                <Pressable style={styles.primaryBtn} disabled={busy} onPress={() => void startCard()}>
                  {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.primaryBtnText}>Start Card Checkout ({formatMoney(remainingDue)})</Text>}
                </Pressable>
              </View>
            )}
          </ScrollView>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: "rgba(15,23,20,0.55)", justifyContent: "flex-end" },
  sheet: {
    backgroundColor: colors.surface,
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    padding: spacing.lg,
    maxHeight: "90%",
  },
  header: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", marginBottom: spacing.md },
  title: { fontSize: 18, fontWeight: "800", color: colors.ink },
  totalSub: { fontSize: 14, fontWeight: "700", color: colors.greenDeep, marginTop: 2 },
  closeText: { fontSize: 14, fontWeight: "700", color: colors.green },
  modeRow: { flexDirection: "row", gap: 6, marginBottom: spacing.md },
  modeBtn: {
    flex: 1,
    paddingVertical: 10,
    borderRadius: radius.sm,
    borderWidth: 1.5,
    borderColor: colors.line,
    backgroundColor: colors.surfaceAlt,
    alignItems: "center",
  },
  modeBtnActive: { borderColor: colors.green, backgroundColor: colors.greenSoft },
  modeBtnText: { fontSize: 12, fontWeight: "700", color: colors.inkSecondary },
  modeBtnTextActive: { color: colors.greenDeep },
  label: { fontSize: 12, fontWeight: "700", color: colors.inkSecondary, marginBottom: 6 },
  input: {
    borderWidth: 1,
    borderColor: colors.lineStrong,
    borderRadius: radius.sm,
    paddingHorizontal: 12,
    paddingVertical: 12,
    fontSize: 16,
    color: colors.ink,
    marginBottom: spacing.md,
  },
  amountInput: {
    borderWidth: 1,
    borderColor: colors.lineStrong,
    borderRadius: radius.sm,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 22,
    fontWeight: "800",
    color: colors.ink,
    backgroundColor: colors.surfaceAlt,
  },
  denomRow: { flexDirection: "row", flexWrap: "wrap", gap: 6, marginVertical: 10 },
  denomChip: {
    paddingHorizontal: 10,
    paddingVertical: 7,
    borderRadius: radius.xs,
    borderWidth: 1,
    borderColor: colors.lineStrong,
    backgroundColor: colors.surface,
  },
  denomText: { fontSize: 12, fontWeight: "700", color: colors.ink },
  changeBanner: {
    flexDirection: "row",
    justifyContent: "space-between",
    backgroundColor: colors.okBg,
    borderWidth: 1,
    borderColor: colors.okBorder,
    borderRadius: radius.sm,
    padding: 12,
    marginBottom: 10,
  },
  changeLabel: { fontSize: 14, fontWeight: "700", color: colors.ok },
  changeValue: { fontSize: 16, fontWeight: "800", color: colors.ok },
  pendingBox: {
    backgroundColor: colors.infoBg,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.infoBorder,
    padding: spacing.md,
    alignItems: "center",
    gap: 8,
  },
  pendingTitle: { fontSize: 15, fontWeight: "800", color: colors.info },
  pendingMeta: { fontSize: 12, color: colors.inkSecondary },
  qrBox: { backgroundColor: "#fff", padding: 14, borderRadius: radius.md, marginVertical: 8 },
  row: { flexDirection: "row", gap: 8, width: "100%", marginTop: 6 },
  primaryBtn: {
    backgroundColor: colors.green,
    paddingVertical: 14,
    borderRadius: radius.sm,
    alignItems: "center",
    marginTop: 6,
  },
  primaryBtnText: { color: "#fff", fontSize: 15, fontWeight: "800" },
  secondaryBtn: {
    borderWidth: 1,
    borderColor: colors.lineStrong,
    backgroundColor: colors.surface,
    paddingVertical: 12,
    paddingHorizontal: 14,
    borderRadius: radius.sm,
    alignItems: "center",
    marginTop: 6,
  },
  secondaryBtnText: { color: colors.ink, fontSize: 14, fontWeight: "700" },
  mutedText: { fontSize: 13, color: colors.muted, marginBottom: spacing.md },
  errorText: { color: colors.danger, fontSize: 13, fontWeight: "600", marginBottom: 8 },
  noticeText: { color: colors.info, fontSize: 13, fontWeight: "600", marginBottom: 8 },
  headerActions: { flexDirection: "row", alignItems: "center", gap: 12 },
  splitToggleBtn: {
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: radius.xs,
    borderWidth: 1,
    borderColor: colors.green,
    backgroundColor: colors.greenSoft,
  },
  splitToggleText: { fontSize: 12, fontWeight: "700", color: colors.greenDeep },
  splitBox: {
    backgroundColor: colors.surfaceAlt,
    borderWidth: 1,
    borderColor: colors.line,
    borderRadius: radius.md,
    padding: spacing.md,
    marginBottom: spacing.md,
  },
  splitActionRow: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 8 },
  splitItemRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingVertical: 6,
    borderBottomWidth: 1,
    borderBottomColor: colors.line,
    gap: 8,
  },
  splitItemName: { fontSize: 13, fontWeight: "700", color: colors.ink },
  splitBillChips: { flexDirection: "row", flexWrap: "wrap", gap: 4 },
  subBillCard: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    padding: 10,
    borderRadius: radius.sm,
    borderWidth: 1.5,
    borderColor: colors.line,
    backgroundColor: colors.surface,
  },
  subBillCardActive: { borderColor: colors.green, backgroundColor: colors.greenSoft },
  subBillCardPaid: { opacity: 0.6, backgroundColor: colors.okBg, borderColor: colors.okBorder },
  splitStatusBadge: { fontSize: 11, fontWeight: "800", color: colors.inkSecondary },
});
