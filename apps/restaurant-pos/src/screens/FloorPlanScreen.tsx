import React, { useCallback, useEffect, useState } from "react";
import { Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from "react-native";
import { colors, DiningTable, ElepayClient, FloorPlan, MerchantView, radius, spacing } from "@elepay/mobile-core";

interface Props {
  client: ElepayClient;
  merchant: MerchantView;
  onStartTableOrder: (tableId: string) => void;
}

const NEXT_STATUS: Record<DiningTable["status"], DiningTable["status"]> = {
  AVAILABLE: "OCCUPIED",
  OCCUPIED: "DIRTY",
  DIRTY: "AVAILABLE",
  RESERVED: "OCCUPIED",
};

export function FloorPlanScreen({ client, merchant, onStartTableOrder }: Props) {
  const [plans, setPlans] = useState<FloorPlan[]>([]);
  const [tables, setTables] = useState<DiningTable[]>([]);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      const [p, t] = await Promise.all([
        client.call<FloorPlan[]>(`/v1/restaurant/floor-plans/merchant/${merchant.id}`),
        client.call<DiningTable[]>(`/v1/restaurant/tables/merchant/${merchant.id}`),
      ]);
      setPlans(p);
      setTables(t);
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

  async function cycleStatus(t: DiningTable) {
    const next = NEXT_STATUS[t.status];
    try {
      if (next === "AVAILABLE" && t.status !== "RESERVED") {
        await client.call(`/v1/restaurant/tables/${t.id}/vacate?markDirty=false`, { method: "POST" });
      } else {
        await client.call(`/v1/restaurant/tables/${t.id}/status?status=${next}`, { method: "POST" });
      }
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  const counts = {
    AVAILABLE: tables.filter((t) => t.status === "AVAILABLE").length,
    OCCUPIED: tables.filter((t) => t.status === "OCCUPIED").length,
    RESERVED: tables.filter((t) => t.status === "RESERVED").length,
    DIRTY: tables.filter((t) => t.status === "DIRTY").length,
  };

  return (
    <ScrollView
      style={styles.root}
      contentContainerStyle={styles.content}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void load()} tintColor={colors.green} />}
    >
      {error && <Text style={styles.errorText}>{error}</Text>}

      <View style={styles.summaryRow}>
        <StatPill label="Available" count={counts.AVAILABLE} bg={colors.okBg} fg={colors.ok} />
        <StatPill label="Occupied" count={counts.OCCUPIED} bg={colors.warnBg} fg={colors.warn} />
        <StatPill label="Reserved" count={counts.RESERVED} bg={colors.infoBg} fg={colors.info} />
        <StatPill label="Clean" count={counts.DIRTY} bg={colors.dangerBg} fg={colors.danger} />
      </View>

      {plans.length === 0 ? (
        <View style={styles.emptyCard}>
          <Text style={styles.mutedText}>No floor plan sections yet. Create sections &amp; tables in the Merchant Dashboard.</Text>
        </View>
      ) : (
        plans.map((p) => {
          const mine = tables.filter((t) => t.sectionId === p.id);
          return (
            <View key={p.id} style={styles.sectionCard}>
              <Text style={styles.sectionTitle}>{p.name}</Text>
              <View style={styles.grid}>
                {mine.map((t) => {
                  const topColor =
                    t.status === "AVAILABLE"
                      ? colors.green
                      : t.status === "OCCUPIED"
                        ? colors.goldDeep
                        : t.status === "RESERVED"
                          ? colors.info
                          : colors.danger;
                  return (
                    <View key={t.id} style={[styles.tableCard, { borderTopColor: topColor }]}>
                      <View style={styles.rowBetween}>
                        <Text style={styles.tableNum}>Table {t.tableNumber}</Text>
                        <Text style={styles.tableSeats}>{t.capacity} seats</Text>
                      </View>
                      <Text style={styles.tableStatus}>{t.status === "DIRTY" ? "NEEDS CLEANING" : t.status}</Text>
                      <View style={styles.btnRow}>
                        <Pressable style={styles.orderBtn} onPress={() => onStartTableOrder(t.id)}>
                          <Text style={styles.orderBtnText}>Order</Text>
                        </Pressable>
                        <Pressable style={styles.cycleBtn} onPress={() => void cycleStatus(t)}>
                          <Text style={styles.cycleBtnText}>Status</Text>
                        </Pressable>
                      </View>
                    </View>
                  );
                })}
              </View>
            </View>
          );
        })
      )}
    </ScrollView>
  );
}

function StatPill({ label, count, bg, fg }: { label: string; count: number; bg: string; fg: string }) {
  return (
    <View style={[styles.statPill, { backgroundColor: bg }]}>
      <Text style={[styles.statText, { color: fg }]}>
        {count} {label}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  content: { padding: spacing.md, gap: spacing.md, paddingBottom: 32 },
  errorText: { color: colors.danger, fontSize: 13, fontWeight: "600" },
  summaryRow: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  statPill: { paddingHorizontal: 10, paddingVertical: 5, borderRadius: radius.pill },
  statText: { fontSize: 12, fontWeight: "800" },
  emptyCard: { backgroundColor: colors.surface, borderRadius: radius.md, padding: spacing.lg, alignItems: "center" },
  mutedText: { fontSize: 13, color: colors.muted, textAlign: "center" },
  sectionCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.line,
    padding: spacing.md,
  },
  sectionTitle: { fontSize: 16, fontWeight: "800", color: colors.ink, marginBottom: 10 },
  grid: { flexDirection: "row", flexWrap: "wrap", gap: 10 },
  tableCard: {
    width: "48%",
    minWidth: 155,
    backgroundColor: colors.surfaceAlt,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: colors.line,
    borderTopWidth: 4,
    padding: 12,
    gap: 6,
  },
  rowBetween: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  tableNum: { fontSize: 15, fontWeight: "800", color: colors.ink },
  tableSeats: { fontSize: 11, color: colors.muted, fontWeight: "600" },
  tableStatus: { fontSize: 11, fontWeight: "800", color: colors.inkSecondary },
  btnRow: { flexDirection: "row", gap: 6, marginTop: 4 },
  orderBtn: {
    flex: 1,
    backgroundColor: colors.green,
    paddingVertical: 7,
    borderRadius: radius.xs,
    alignItems: "center",
  },
  orderBtnText: { color: "#fff", fontSize: 12, fontWeight: "700" },
  cycleBtn: {
    flex: 1,
    borderWidth: 1,
    borderColor: colors.lineStrong,
    backgroundColor: colors.surface,
    paddingVertical: 7,
    borderRadius: radius.xs,
    alignItems: "center",
  },
  cycleBtnText: { color: colors.ink, fontSize: 12, fontWeight: "700" },
});
