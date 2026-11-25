import React, { useCallback, useEffect, useState } from "react";
import { RefreshControl, ScrollView, StyleSheet, Text, Pressable, View } from "react-native";
import {
  Balances,
  colors,
  ElepayClient,
  formatMoney,
  MerchantView,
  radius,
  spacing,
  TransactionLine,
} from "@elepay/mobile-core";

interface Props {
  client: ElepayClient;
  merchant: MerchantView;
  onNavigate: (tab: "home" | "sales" | "collect" | "withdraw") => void;
}

export function HomeScreen({ client, merchant, onNavigate }: Props) {
  const [balances, setBalances] = useState<Balances | null>(null);
  const [recent, setRecent] = useState<TransactionLine[]>([]);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      const [b, tx] = await Promise.all([
        client.call<Balances>(`/v1/merchants/${merchant.id}/balances`),
        client.call<TransactionLine[]>(`/v1/merchants/${merchant.id}/transactions`),
      ]);
      setBalances(b);
      setRecent(tx.slice(0, 6));
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

  return (
    <ScrollView
      style={styles.root}
      contentContainerStyle={styles.content}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void load()} tintColor={colors.green} />}
    >
      {error && (
        <View style={styles.errorBox}>
          <Text style={styles.errorText}>{error}</Text>
        </View>
      )}

      {/* Hero Available Balance */}
      <View style={styles.heroCard}>
        <View style={styles.heroTopRow}>
          <Text style={styles.heroLabel}>AVAILABLE BALANCE</Text>
          <View style={styles.codeBadge}>
            <Text style={styles.codeBadgeText}>CODE {merchant.merchantCode}</Text>
          </View>
        </View>
        <Text style={styles.heroValue}>{balances ? formatMoney(balances.available) : "—"}</Text>
        <Text style={styles.heroSub}>Ready for instant MoMo or bank withdrawal</Text>

        <View style={styles.heroActions}>
          <Pressable style={styles.heroPrimaryBtn} onPress={() => onNavigate("collect")}>
            <Text style={styles.heroPrimaryBtnText}>Collect Payment</Text>
          </Pressable>
          <Pressable style={styles.heroSecondaryBtn} onPress={() => onNavigate("withdraw")}>
            <Text style={styles.heroSecondaryBtnText}>Withdraw</Text>
          </Pressable>
        </View>
      </View>

      {/* Secondary KPI Row */}
      <View style={styles.kpiRow}>
        <View style={styles.kpiCard}>
          <Text style={styles.kpiLabel}>PENDING SETTLEMENT</Text>
          <Text style={styles.kpiValue}>{balances ? formatMoney(balances.pending) : "—"}</Text>
          <Text style={styles.kpiMeta}>Clearing automatically</Text>
        </View>
        <View style={styles.kpiCard}>
          <Text style={styles.kpiLabel}>RESERVED</Text>
          <Text style={styles.kpiValue}>{balances ? formatMoney(balances.reserved) : "—"}</Text>
          <Text style={styles.kpiMeta}>Payouts &amp; disputes</Text>
        </View>
      </View>

      {/* Live Recent Sales */}
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle}>Live Sales</Text>
        <Pressable onPress={() => onNavigate("sales")}>
          <Text style={styles.sectionLink}>See all</Text>
        </Pressable>
      </View>

      <View style={styles.listCard}>
        {recent.length === 0 ? (
          <Text style={styles.emptyText}>No live sales recorded yet. Pull down to refresh.</Text>
        ) : (
          recent.map((tx, idx) => (
            <View key={`${tx.reference}-${tx.line}`} style={[styles.txRow, idx > 0 && styles.txDivider]}>
              <View style={{ flex: 1 }}>
                <Text style={styles.txTitle} numberOfLines={1}>
                  {tx.description || `${tx.kind} · ${tx.collector}`}
                </Text>
                <Text style={styles.txMeta}>
                  {tx.reference} · {tx.status.toUpperCase()}
                </Text>
              </View>
              <View style={{ alignItems: "flex-end" }}>
                <Text style={styles.txAmount}>{formatMoney(tx.net)}</Text>
                <Text style={styles.txFee}>Gross {formatMoney(tx.amount)}</Text>
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
  errorBox: {
    padding: 10,
    borderRadius: radius.sm,
    backgroundColor: colors.dangerBg,
    borderWidth: 1,
    borderColor: colors.dangerBorder,
    marginBottom: spacing.md,
  },
  errorText: { color: colors.danger, fontSize: 13, fontWeight: "600" },
  heroCard: {
    backgroundColor: colors.greenDeep,
    borderRadius: radius.lg,
    padding: spacing.lg,
    marginBottom: spacing.md,
  },
  heroTopRow: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  heroLabel: { color: "rgba(255,255,255,0.75)", fontSize: 11, fontWeight: "700", letterSpacing: 0.8 },
  codeBadge: {
    backgroundColor: "rgba(245,197,66,0.18)",
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: radius.pill,
  },
  codeBadgeText: { color: colors.gold, fontSize: 11, fontWeight: "700" },
  heroValue: { color: "#fff", fontSize: 32, fontWeight: "800", marginTop: 8, letterSpacing: -0.5 },
  heroSub: { color: "rgba(255,255,255,0.75)", fontSize: 12, marginTop: 4 },
  heroActions: { flexDirection: "row", gap: 10, marginTop: spacing.lg },
  heroPrimaryBtn: {
    flex: 1,
    backgroundColor: colors.gold,
    paddingVertical: 12,
    borderRadius: radius.sm,
    alignItems: "center",
  },
  heroPrimaryBtnText: { color: colors.ink, fontSize: 14, fontWeight: "800" },
  heroSecondaryBtn: {
    flex: 1,
    backgroundColor: "rgba(255,255,255,0.14)",
    paddingVertical: 12,
    borderRadius: radius.sm,
    alignItems: "center",
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.25)",
  },
  heroSecondaryBtnText: { color: "#fff", fontSize: 14, fontWeight: "700" },
  kpiRow: { flexDirection: "row", gap: 10, marginBottom: spacing.lg },
  kpiCard: {
    flex: 1,
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    padding: spacing.md,
    borderWidth: 1,
    borderColor: colors.line,
  },
  kpiLabel: { fontSize: 10, fontWeight: "700", color: colors.muted, letterSpacing: 0.6 },
  kpiValue: { fontSize: 19, fontWeight: "800", color: colors.ink, marginTop: 6 },
  kpiMeta: { fontSize: 11, color: colors.muted, marginTop: 2 },
  sectionHeader: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", marginBottom: 8 },
  sectionTitle: { fontSize: 16, fontWeight: "800", color: colors.ink },
  sectionLink: { fontSize: 13, fontWeight: "700", color: colors.green },
  listCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.line,
    paddingHorizontal: spacing.md,
  },
  emptyText: { color: colors.muted, fontSize: 13, textAlign: "center", paddingVertical: spacing.lg },
  txRow: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", paddingVertical: 13, gap: 10 },
  txDivider: { borderTopWidth: 1, borderTopColor: "#EEF2F0" },
  txTitle: { fontSize: 14, fontWeight: "700", color: colors.ink },
  txMeta: { fontSize: 11, color: colors.muted, marginTop: 2 },
  txAmount: { fontSize: 15, fontWeight: "800", color: colors.greenDeep },
  txFee: { fontSize: 11, color: colors.muted, marginTop: 2 },
});
