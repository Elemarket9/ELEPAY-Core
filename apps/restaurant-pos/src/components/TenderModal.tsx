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
  radius,
  spacing,
  TenderInitiation,
  TenderMode,
} from "@elepay/mobile-core";

interface Props {
  visible: boolean;
  onClose: () => void;
  client: ElepayClient;
  merchantId: string;
  totalPesewas: number;
  ensureOrder: () => Promise<PosOrder>;
  onOrderPaid: (order: PosOrder) => Promise<void>;
}

export function TenderModal({
  visible,
  onClose,
  client,
  merchantId,
  totalPesewas,
  ensureOrder,
  onOrderPaid,
}: Props) {
  const [mode, setMode] = useState<TenderMode>("CASH");
  const [cashInput, setCashInput] = useState("");
  const [momoNetwork, setMomoNetwork] = useState<MomoNetwork>("mtn");
  const [momoPhone, setMomoPhone] = useState("");
  const [activeOrder, setActiveOrder] = useState<PosOrder | null>(null);
  const [pendingRef, setPendingRef] = useState<string | null>(null);
  const [pollStatus, setPollStatus] = useState<string | null>(null);
  const [qrPayload, setQrPayload] = useState<string | null>(null);
  const [cardPayUrl, setCardPayUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const completingRef = useRef(false);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  useEffect(() => () => stopPolling(), [stopPolling]);

  useEffect(() => {
    if (!visible) {
      stopPolling();
      setPendingRef(null);
      setPollStatus(null);
      setQrPayload(null);
      setCardPayUrl(null);
      setError(null);
    }
  }, [visible, stopPolling]);

  const completeDigitalOrder = useCallback(
    async (ord: PosOrder, usedFallback: boolean) => {
      if (completingRef.current) return;
      completingRef.current = true;
      stopPolling();
      try {
        let paidOrder = ord;
        if (usedFallback) {
          paidOrder = await client.call<PosOrder>(`/v1/pos/orders/${ord.id}/digital-checkout/confirm`, { method: "POST" });
        } else {
          const refreshed = await client.call<PosOrder>(`/v1/pos/orders/${ord.id}`);
          paidOrder =
            refreshed.status === "PAID"
              ? refreshed
              : await client
                  .call<PosOrder>(`/v1/pos/orders/${ord.id}/digital-checkout/confirm`, { method: "POST" })
                  .catch(() => refreshed);
        }
        setPendingRef(null);
        setQrPayload(null);
        setCardPayUrl(null);
        await onOrderPaid(paidOrder);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        completingRef.current = false;
      }
    },
    [client, onOrderPaid, stopPolling],
  );

  const startPolling = useCallback(
    (ord: PosOrder, refToPoll: string, usedFallback: boolean) => {
      stopPolling();
      setPollStatus("PENDING");
      pollRef.current = setInterval(() => {
        void (async () => {
          try {
            const st = await client.call<{ status: string; message?: string | null }>(
              `/v1/public/checkouts/${refToPoll}/status`,
              { anonymous: true },
            );
            setPollStatus(st.status);
            if (isTerminalStatusPaid(st.status)) {
              await completeDigitalOrder(ord, usedFallback);
            } else if (isTerminalStatusFailed(st.status)) {
              stopPolling();
              setError(st.message || `Payment ${st.status.toLowerCase()}.`);
            }
          } catch {
            try {
              const latest = await client.call<PosOrder>(`/v1/pos/orders/${ord.id}`);
              if (isTerminalStatusPaid(latest.status)) {
                await completeDigitalOrder(ord, false);
              }
            } catch {
              // Keep polling across transient drops
            }
          }
        })();
      }, 3000);
    },
    [client, completeDigitalOrder, stopPolling],
  );

  async function payCash() {
    setError(null);
    const cash = parseMoney(cashInput);
    if (cash === null || cash <= 0) {
      setError("Enter cash received, e.g. 50.00");
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
      const paid = await client.call<PosOrder>(`/v1/pos/orders/${ord.id}/payments`, {
        idempotencyKey: newKey(),
        body: { tenderType: "CASH", amountPesewas: cash, reference: null },
      });
      if (paid.status !== "PAID") {
        setError("Order is not fully paid yet.");
        return;
      }
      await onOrderPaid(paid);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
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
      let ref: string | null = null;
      let usedFallback = false;
      try {
        const res = await client.call<TenderInitiation>(`/v1/pos/orders/${ord.id}/tenders`, {
          idempotencyKey: newKey(),
          body: { tenderType: "MOMO", network: momoNetwork, phone: msisdn, amountPesewas: ord.totalPesewas },
        });
        const norm = normalizeTenderResult(res);
        ref = norm.checkoutRef;
        if (isTerminalStatusPaid(norm.paymentStatus) || isTerminalStatusPaid(norm.orderStatus)) {
          await completeDigitalOrder(norm.order ?? ord, false);
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
            idempotencyKey: newKey(),
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
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      let ref: string | null = null;
      let payload: string | null = null;
      let usedFallback = false;
      try {
        const res = await client.call<TenderInitiation>(`/v1/pos/orders/${ord.id}/tenders`, {
          idempotencyKey: newKey(),
          body: { tenderType: "GHQR", amountPesewas: ord.totalPesewas },
        });
        const norm = normalizeTenderResult(res);
        ref = norm.checkoutRef;
        payload = norm.qrPayload ?? norm.payUrl;
        if (isTerminalStatusPaid(norm.paymentStatus) || isTerminalStatusPaid(norm.orderStatus)) {
          await completeDigitalOrder(norm.order ?? ord, false);
          return;
        }
      } catch (err) {
        if (err instanceof ApiError && err.status === 404) {
          usedFallback = true;
          const dc = await client.call<{ reference: string; payUrl: string | null }>(
            `/v1/pos/orders/${ord.id}/digital-checkout`,
            { method: "POST" },
          );
          ref = dc.reference;
          const qr = await client
            .call<{ payload: string }>(`/v1/merchants/${merchantId}/qr?link=${encodeURIComponent(dc.reference)}`)
            .catch(() => ({ payload: dc.payUrl ?? dc.reference }));
          payload = qr.payload;
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
    setBusy(true);
    try {
      const ord = await ensureOrder();
      setActiveOrder(ord);
      const res = await client.call<TenderInitiation>(`/v1/pos/orders/${ord.id}/tenders`, {
        idempotencyKey: newKey(),
        body: { tenderType: "CARD", amountPesewas: ord.totalPesewas },
      });
      const norm = normalizeTenderResult(res);
      if (isTerminalStatusPaid(norm.paymentStatus) || isTerminalStatusPaid(norm.orderStatus)) {
        await completeDigitalOrder(norm.order ?? ord, false);
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
    if (activeOrder) {
      await client.call(`/v1/pos/orders/${activeOrder.id}/digital-checkout/cancel`, { method: "POST" }).catch(() => {});
    }
    setPendingRef(null);
    setPollStatus(null);
    setQrPayload(null);
    setCardPayUrl(null);
  }

  const parsedCash = parseMoney(cashInput);
  const changeDue = parsedCash !== null && parsedCash >= totalPesewas ? parsedCash - totalPesewas : null;

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <View style={styles.backdrop}>
        <View style={styles.sheet}>
          <View style={styles.header}>
            <View>
              <Text style={styles.title}>Collect Payment</Text>
              <Text style={styles.totalSub}>Total due: {formatMoney(totalPesewas)}</Text>
            </View>
            <Pressable onPress={onClose}>
              <Text style={styles.closeText}>Close</Text>
            </Pressable>
          </View>

          <ScrollView showsVerticalScrollIndicator={false} keyboardShouldPersistTaps="handled">
            <View style={styles.modeRow}>
              {(
                [
                  ["CASH", "Cash"],
                  ["MOMO", "MoMo"],
                  ["GHQR", "GhQR"],
                  ["CARD", "Card"],
                ] as const
              ).map(([k, label]) => (
                <Pressable
                  key={k}
                  style={[styles.modeBtn, mode === k && styles.modeBtnActive]}
                  onPress={() => {
                    setMode(k);
                    setError(null);
                  }}
                >
                  <Text style={[styles.modeBtnText, mode === k && styles.modeBtnTextActive]}>{label}</Text>
                </Pressable>
              ))}
            </View>

            {error && <Text style={styles.errorText}>{error}</Text>}

            {pendingRef ? (
              <View style={styles.pendingBox}>
                <ActivityIndicator color={colors.info} />
                <Text style={styles.pendingTitle}>Awaiting live rail confirmation</Text>
                <Text style={styles.pendingMeta}>
                  Ref: {pendingRef} · Status: {pollStatus ?? "PENDING"}
                </Text>
                {qrPayload && (
                  <View style={styles.qrBox}>
                    <QRCode value={qrPayload} size={200} backgroundColor="#fff" color={colors.ink} />
                  </View>
                )}
                {cardPayUrl && (
                  <Pressable style={styles.secondaryBtn} onPress={() => void Linking.openURL(cardPayUrl)}>
                    <Text style={styles.secondaryBtnText}>Open Card Checkout Link</Text>
                  </Pressable>
                )}
                <View style={styles.row}>
                  {activeOrder && (
                    <Pressable style={[styles.primaryBtn, { flex: 1 }]} onPress={() => void completeDigitalOrder(activeOrder, false)}>
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
                  placeholder={(totalPesewas / 100).toFixed(2)}
                  placeholderTextColor={colors.subtle}
                />
                <View style={styles.denomRow}>
                  <Pressable style={styles.denomChip} onPress={() => setCashInput((totalPesewas / 100).toFixed(2))}>
                    <Text style={styles.denomText}>Exact</Text>
                  </Pressable>
                  {[1000, 2000, 5000, 10000].map((add) => (
                    <Pressable
                      key={add}
                      style={styles.denomChip}
                      onPress={() => setCashInput(((totalPesewas + add) / 100).toFixed(2))}
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
                  {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.primaryBtnText}>Charge Cash {formatMoney(totalPesewas)}</Text>}
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
                  {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.primaryBtnText}>Send MoMo Push ({formatMoney(totalPesewas)})</Text>}
                </Pressable>
              </View>
            ) : mode === "GHQR" ? (
              <View>
                <Text style={styles.mutedText}>
                  Generate a dynamic EMVCo GhQR code for the customer to scan with any Ghana bank or MoMo app.
                </Text>
                <Pressable style={styles.primaryBtn} disabled={busy} onPress={() => void startGhQr()}>
                  {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.primaryBtnText}>Display GhQR ({formatMoney(totalPesewas)})</Text>}
                </Pressable>
              </View>
            ) : (
              <View>
                <Text style={styles.mutedText}>
                  Create a live ELEPAY Card Checkout session for Visa / Mastercard payment.
                </Text>
                <Pressable style={styles.primaryBtn} disabled={busy} onPress={() => void startCard()}>
                  {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.primaryBtnText}>Start Card Checkout ({formatMoney(totalPesewas)})</Text>}
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
});
