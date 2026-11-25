import React, { useCallback, useEffect, useState } from "react";
import {
  ActivityIndicator,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import {
  Balances,
  colors,
  ElepayClient,
  formatMoney,
  MerchantView,
  newKey,
  parseMoney,
  Payout,
  radius,
  SettlementDestination,
  spacing,
} from "@elepay/mobile-core";

interface Props {
  client: ElepayClient;
  merchant: MerchantView;
}

export function WithdrawScreen({ client, merchant }: Props) {
  const [balances, setBalances] = useState<Balances | null>(null);
  const [destinations, setDestinations] = useState<SettlementDestination[]>([]);
  const [payouts, setPayouts] = useState<Payout[]>([]);
  const [selectedDest, setSelectedDest] = useState<string>("");
  const [amount, setAmount] = useState("");
  const [refreshing, setRefreshing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      const [b, dests, p] = await Promise.all([
        client.call<Balances>(`/v1/merchants/${merchant.id}/balances`),
        client.call<SettlementDestination[]>(`/v1/merchants/${merchant.id}/settlement-destinations`),
        client.call<Payout[]>(`/v1/merchants/${merchant.id}/payouts`),
      ]);
      setBalances(b);
      setDestinations(dests);
      setPayouts(p);
      setSelectedDest((prev) =>
        prev && dests.some((d) => d.id === prev && d.verification === "verified")
          ? prev
          : (dests.find((d) => d.verification === "verified")?.id ?? ""),
      );
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRefreshing(false);
    }
  }, [client, merchant.id]);

  useEffect(() => {
    void load();
  }, [load]);

  async function submitWithdraw() {
    if (busy) return;
    setError(null);
    setMsg(null);
    if (!selectedDest) {
      setError("Select a verified settlement destination first.");
      return;
    }
    const pesewas = parseMoney(amount);
    if (!pesewas || pesewas <= 0) {
      setError("Enter a valid withdrawal amount, e.g. 50.00");
      return;
    }
    setBusy(true);
    try {
      await client.call(`/v1/merchants/${merchant.id}/withdrawals`, {
        idempotencyKey: newKey(),
        body: { amount: pesewas, destinationId: selectedDest },
      });
      setMsg(`Withdrawal of ${formatMoney(pesewas)} requested.`);
      setAmount("");
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <ScrollView
      style={styles.root}
      contentContainerStyle={styles.content}
      keyboardShouldPersistTaps="handled"
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void load()} tintColor={colors.green} />}
    >
      <View style={styles.card}>
        <View style={styles.balRow}>
          <Text style={styles.balLabel}>Available to withdraw</Text>
          <Text style={styles.balValue}>{balances ? formatMoney(balances.available) : "—"}</Text>
        </View>

        <Text style={styles.label}>Verified Destination</Text>
        {destinations.length === 0 ? (
          <Text style={styles.mutedText}>No settlement destinations configured on your merchant account.</Text>
        ) : (
          <View style={styles.destList}>
            {destinations.map((d) => {
              const verified = d.verification === "verified";
              const active = selectedDest === d.id;
              return (
                <Pressable
                  key={d.id}
                  disabled={!verified}
                  style={[styles.destItem, active && styles.destItemActive, !verified && { opacity: 0.5 }]}
                  onPress={() => setSelectedDest(d.id)}
                >
                  <View style={{ flex: 1 }}>
                    <Text style={styles.destTitle}>
                      {d.network.toUpperCase()} · {d.accountNumber}
                    </Text>
                    <Text style={styles.destSub}>
                      {d.accountName} ({d.type})
                    </Text>
                  </View>
                  <View style={[styles.tag, verified ? styles.tagOk : styles.tagWarn]}>
                    <Text style={[styles.tagText, verified ? styles.tagTextOk : styles.tagTextWarn]}>
                      {d.verification.toUpperCase()}
                    </Text>
                  </View>
                </Pressable>
              );
            })}
          </View>
        )}

        <Text style={styles.label}>Amount (GH₵)</Text>
        <TextInput
          style={styles.amountInput}
          keyboardType="decimal-pad"
          value={amount}
          onChangeText={setAmount}
          placeholder="0.00"
          placeholderTextColor={colors.subtle}
        />

        {error && <Text style={styles.errorText}>{error}</Text>}
        {msg && <Text style={styles.okText}>{msg}</Text>}

        <Pressable style={[styles.primaryBtn, busy && styles.disabledBtn]} disabled={busy} onPress={() => void submitWithdraw()}>
          {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.primaryBtnText}>Withdraw Funds</Text>}
        </Pressable>
      </View>

      <Text style={styles.sectionTitle}>Payout History</Text>
      <View style={styles.listCard}>
        {payouts.length === 0 ? (
          <Text style={styles.mutedText}>No payouts recorded yet.</Text>
        ) : (
          payouts.map((p, idx) => (
            <View key={p.reference} style={[styles.payoutRow, idx > 0 && styles.divider]}>
              <View style={{ flex: 1 }}>
                <Text style={styles.payoutRef}>{p.reference}</Text>
                <Text style={styles.payoutSub}>{p.failureReason || p.clientReference}</Text>
              </View>
              <View style={{ alignItems: "flex-end" }}>
                <Text style={styles.payoutAmount}>{formatMoney(p.amount)}</Text>
                <Text style={styles.payoutStatus}>{p.status.toUpperCase()}</Text>
              </View>
            </View>
          ))
        )}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  content: { padding: spacing.md, paddingBottom: 32 },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.lg,
    borderWidth: 1,
    borderColor: colors.line,
    marginBottom: spacing.lg,
  },
  balRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingBottom: spacing.md,
    borderBottomWidth: 1,
    borderBottomColor: colors.line,
    marginBottom: spacing.md,
  },
  balLabel: { fontSize: 13, fontWeight: "600", color: colors.muted },
  balValue: { fontSize: 20, fontWeight: "800", color: colors.greenDeep },
  label: { fontSize: 12, fontWeight: "700", color: colors.inkSecondary, marginTop: spacing.sm, marginBottom: 8 },
  destList: { gap: 8, marginBottom: spacing.sm },
  destItem: {
    flexDirection: "row",
    alignItems: "center",
    padding: 12,
    borderRadius: radius.sm,
    borderWidth: 1.5,
    borderColor: colors.line,
    backgroundColor: colors.surfaceAlt,
    gap: 8,
  },
  destItemActive: { borderColor: colors.green, backgroundColor: colors.greenSubtle },
  destTitle: { fontSize: 14, fontWeight: "700", color: colors.ink },
  destSub: { fontSize: 12, color: colors.muted, marginTop: 2 },
  tag: { paddingHorizontal: 8, paddingVertical: 2, borderRadius: radius.pill },
  tagOk: { backgroundColor: colors.okBg },
  tagWarn: { backgroundColor: colors.warnBg },
  tagText: { fontSize: 10, fontWeight: "700" },
  tagTextOk: { color: colors.ok },
  tagTextWarn: { color: colors.warn },
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
  errorText: { color: colors.danger, fontSize: 13, fontWeight: "600", marginTop: 8 },
  okText: { color: colors.ok, fontSize: 13, fontWeight: "700", marginTop: 8 },
  primaryBtn: {
    marginTop: spacing.md,
    backgroundColor: colors.green,
    paddingVertical: 14,
    borderRadius: radius.sm,
    alignItems: "center",
  },
  disabledBtn: { opacity: 0.6 },
  primaryBtnText: { color: "#fff", fontSize: 15, fontWeight: "700" },
  sectionTitle: { fontSize: 16, fontWeight: "800", color: colors.ink, marginBottom: 8 },
  listCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.line,
    paddingHorizontal: spacing.md,
  },
  mutedText: { color: colors.muted, fontSize: 13, paddingVertical: spacing.md },
  payoutRow: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", paddingVertical: 13, gap: 10 },
  divider: { borderTopWidth: 1, borderTopColor: "#EEF2F0" },
  payoutRef: { fontSize: 13, fontWeight: "700", color: colors.ink },
  payoutSub: { fontSize: 11, color: colors.muted, marginTop: 2 },
  payoutAmount: { fontSize: 15, fontWeight: "800", color: colors.ink },
  payoutStatus: { fontSize: 10, fontWeight: "700", color: colors.green, marginTop: 2 },
});
