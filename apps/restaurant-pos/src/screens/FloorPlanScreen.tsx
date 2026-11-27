import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Modal, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from "react-native";
import { colors, DiningTable, ElepayClient, FloorPlan, MerchantView, newKey, radius, spacing } from "@elepay/mobile-core";

interface Props {
  client: ElepayClient;
  merchant: MerchantView;
  onStartTableOrder: (tableId: string) => void;
}

type TableStatus = DiningTable["status"] | "BILL_PRINTED";

type FloorTable = Omit<DiningTable, "status"> & {
  status: TableStatus;
  branchId?: string | null;
};

type TableActionModal =
  | { kind: "move"; table: FloorTable }
  | { kind: "merge"; table: FloorTable };

const BILL_PRINTED_COLOR = "#6D28D9";
const BILL_PRINTED_BG = "#F3E8FF";

const NEXT_STATUS: Record<TableStatus, TableStatus> = {
  AVAILABLE: "OCCUPIED",
  OCCUPIED: "DIRTY",
  BILL_PRINTED: "DIRTY",
  DIRTY: "AVAILABLE",
  RESERVED: "OCCUPIED",
};

function compatibleBranch(a: FloorTable, b: FloorTable): boolean {
  if (a.branchId && b.branchId) return a.branchId === b.branchId;
  return true;
}

function statusLabel(status: TableStatus): string {
  if (status === "DIRTY") return "NEEDS CLEANING";
  if (status === "BILL_PRINTED") return "BILL PRINTED";
  return status;
}

export function FloorPlanScreen({ client, merchant, onStartTableOrder }: Props) {
  const [plans, setPlans] = useState<FloorPlan[]>([]);
  const [tables, setTables] = useState<FloorTable[]>([]);
  const [billRequests, setBillRequests] = useState<string[]>([]);
  const [refreshing, setRefreshing] = useState(false);
  const [busyTableId, setBusyTableId] = useState<string | null>(null);
  const [modal, setModal] = useState<TableActionModal | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      const [p, t, bills] = await Promise.all([
        client.call<FloorPlan[]>(`/v1/restaurant/floor-plans/merchant/${merchant.id}`),
        client.call<FloorTable[]>(`/v1/restaurant/tables/merchant/${merchant.id}`),
        client.call<string[]>(`/v1/restaurant/waiter/${merchant.id}/bill-requests`),
      ]);
      setPlans(p);
      setTables(t);
      setBillRequests(bills);
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

  const sectionNames = useMemo(() => {
    const map = new Map<string, string>();
    for (const p of plans) map.set(p.id, p.name);
    return map;
  }, [plans]);

  async function cycleStatus(t: FloorTable) {
    const next = NEXT_STATUS[t.status];
    setBusyTableId(t.id);
    setNotice(null);
    try {
      if (next === "AVAILABLE" && t.status !== "RESERVED") {
        await client.call(`/v1/restaurant/tables/${t.id}/vacate?markDirty=false`, { method: "POST" });
      } else {
        await client.call(`/v1/restaurant/tables/${t.id}/status?status=${next}`, { method: "POST" });
      }
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyTableId(null);
    }
  }

  async function printBill(t: FloorTable) {
    setBusyTableId(t.id);
    setNotice(null);
    setError(null);
    try {
      await client.call(`/v1/restaurant/tables/${t.id}/print-bill`, { method: "POST" });
      setNotice(`Bill marked printed for Table ${t.tableNumber}.`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyTableId(null);
    }
  }

  async function confirmMove(from: FloorTable, to: FloorTable) {
    setBusyTableId(from.id);
    setNotice(null);
    setError(null);
    try {
      await client.call(`/v1/restaurant/tables/${from.id}/move?to=${encodeURIComponent(to.id)}`, {
        method: "POST",
        idempotencyKey: newKey(),
      });
      setModal(null);
      setNotice(`Moved Table ${from.tableNumber} to Table ${to.tableNumber}.`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyTableId(null);
    }
  }

  async function confirmMerge(target: FloorTable, source: FloorTable) {
    setBusyTableId(target.id);
    setNotice(null);
    setError(null);
    try {
      await client.call(`/v1/restaurant/tables/${target.id}/merge?source=${encodeURIComponent(source.id)}`, {
        method: "POST",
      });
      setModal(null);
      setNotice(`Merged Table ${source.tableNumber} into Table ${target.tableNumber}.`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyTableId(null);
    }
  }

  const moveCandidates = useMemo(() => {
    if (!modal || modal.kind !== "move") return [];
    return tables.filter(
      (other) =>
        other.id !== modal.table.id &&
        !other.currentOrderId &&
        (other.status === "AVAILABLE" || other.status === "RESERVED") &&
        compatibleBranch(modal.table, other),
    );
  }, [modal, tables]);

  const mergeCandidates = useMemo(() => {
    if (!modal || modal.kind !== "merge") return [];
    return tables.filter(
      (other) =>
        other.id !== modal.table.id &&
        !!other.currentOrderId &&
        other.currentOrderId !== modal.table.currentOrderId &&
        (other.status === "OCCUPIED" || other.status === "BILL_PRINTED") &&
        compatibleBranch(modal.table, other),
    );
  }, [modal, tables]);

  const counts = {
    AVAILABLE: tables.filter((t) => t.status === "AVAILABLE").length,
    OCCUPIED: tables.filter((t) => t.status === "OCCUPIED").length,
    BILL_PRINTED: tables.filter((t) => t.status === "BILL_PRINTED").length,
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
      {notice && <Text style={styles.noticeText}>{notice}</Text>}

      <View style={styles.summaryRow}>
        <StatPill label="Available" count={counts.AVAILABLE} bg={colors.okBg} fg={colors.ok} />
        <StatPill label="Occupied" count={counts.OCCUPIED} bg={colors.warnBg} fg={colors.warn} />
        <StatPill label="Bill Printed" count={counts.BILL_PRINTED} bg={BILL_PRINTED_BG} fg={BILL_PRINTED_COLOR} />
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
                        : t.status === "BILL_PRINTED"
                          ? BILL_PRINTED_COLOR
                          : t.status === "RESERVED"
                            ? colors.info
                            : colors.danger;
                  const isActiveOrder =
                    !!t.currentOrderId && (t.status === "OCCUPIED" || t.status === "BILL_PRINTED");
                  const mergedPeers = t.currentOrderId
                    ? tables.filter((o) => o.id !== t.id && o.currentOrderId === t.currentOrderId)
                    : [];
                  const isBusy = busyTableId === t.id;
                  return (
                    <View key={t.id} style={[styles.tableCard, { borderTopColor: topColor }]}>
                      <View style={styles.rowBetween}>
                        <Text style={styles.tableNum}>Table {t.tableNumber}</Text>
                        <Text style={styles.tableSeats}>{t.capacity} seats</Text>
                      </View>
                      {t.currentOrderId && billRequests.includes(t.currentOrderId) && (
                        <Text style={styles.billReqText}>BILL REQUESTED</Text>
                      )}
                      <Text
                        style={[
                          styles.tableStatus,
                          t.status === "BILL_PRINTED" && { color: BILL_PRINTED_COLOR },
                        ]}
                      >
                        {statusLabel(t.status)}
                      </Text>
                      {mergedPeers.length > 0 && (
                        <Text style={styles.mergedText}>
                          Merged: {mergedPeers.map((m) => `T${m.tableNumber}`).join(", ")}
                        </Text>
                      )}
                      <View style={styles.btnRow}>
                        <Pressable style={styles.orderBtn} disabled={isBusy} onPress={() => onStartTableOrder(t.id)}>
                          <Text style={styles.orderBtnText}>Order</Text>
                        </Pressable>
                        <Pressable
                          style={[styles.cycleBtn, (!!t.currentOrderId || isBusy) && styles.btnDisabled]}
                          disabled={!!t.currentOrderId || isBusy}
                          onPress={() => void cycleStatus(t)}
                        >
                          <Text style={styles.cycleBtnText}>Status</Text>
                        </Pressable>
                      </View>
                      {isActiveOrder && (
                        <View style={styles.actionRow}>
                          {t.status === "OCCUPIED" && (
                            <Pressable
                              style={[styles.billBtn, isBusy && styles.btnDisabled]}
                              disabled={isBusy}
                              onPress={() => void printBill(t)}
                            >
                              <Text style={styles.billBtnText}>Print Bill</Text>
                            </Pressable>
                          )}
                          <Pressable
                            style={[styles.secondaryActionBtn, isBusy && styles.btnDisabled]}
                            disabled={isBusy}
                            onPress={() => setModal({ kind: "move", table: t })}
                          >
                            <Text style={styles.secondaryActionText}>Move</Text>
                          </Pressable>
                          <Pressable
                            style={[styles.secondaryActionBtn, isBusy && styles.btnDisabled]}
                            disabled={isBusy}
                            onPress={() => setModal({ kind: "merge", table: t })}
                          >
                            <Text style={styles.secondaryActionText}>Merge</Text>
                          </Pressable>
                        </View>
                      )}
                    </View>
                  );
                })}
              </View>
            </View>
          );
        })
      )}

      <Modal
        visible={modal !== null}
        transparent
        animationType="fade"
        onRequestClose={() => setModal(null)}
      >
        <View style={styles.modalBackdrop}>
          <View style={styles.modalCard}>
            {modal?.kind === "move" && (
              <>
                <Text style={styles.modalTitle}>Move Table {modal.table.tableNumber}</Text>
                <Text style={styles.modalSubtitle}>
                  Select an available or reserved destination table. Table {modal.table.tableNumber} will be marked needs cleaning.
                </Text>
                <ScrollView style={styles.candidateList}>
                  {moveCandidates.length === 0 ? (
                    <Text style={styles.mutedText}>No available or reserved tables in this branch.</Text>
                  ) : (
                    moveCandidates.map((dest) => (
                      <Pressable
                        key={dest.id}
                        style={styles.candidateRow}
                        disabled={busyTableId !== null}
                        onPress={() => void confirmMove(modal.table, dest)}
                      >
                        <View>
                          <Text style={styles.candidateName}>Table {dest.tableNumber}</Text>
                          <Text style={styles.candidateMeta}>
                            {sectionNames.get(dest.sectionId) ?? "Floor"} · {dest.capacity} seats · {dest.status}
                          </Text>
                        </View>
                        <Text style={styles.candidateAction}>Move here</Text>
                      </Pressable>
                    ))
                  )}
                </ScrollView>
              </>
            )}

            {modal?.kind === "merge" && (
              <>
                <Text style={styles.modalTitle}>Merge into Table {modal.table.tableNumber}</Text>
                <Text style={styles.modalSubtitle}>
                  Choose an occupied table whose items will be moved into Table {modal.table.tableNumber}. Both tables will share the merged order until settled.
                </Text>
                <ScrollView style={styles.candidateList}>
                  {mergeCandidates.length === 0 ? (
                    <Text style={styles.mutedText}>No other occupied tables available to merge.</Text>
                  ) : (
                    mergeCandidates.map((src) => (
                      <Pressable
                        key={src.id}
                        style={styles.candidateRow}
                        disabled={busyTableId !== null}
                        onPress={() => void confirmMerge(modal.table, src)}
                      >
                        <View>
                          <Text style={styles.candidateName}>Table {src.tableNumber}</Text>
                          <Text style={styles.candidateMeta}>
                            {sectionNames.get(src.sectionId) ?? "Floor"} · {src.capacity} seats · {statusLabel(src.status)}
                          </Text>
                        </View>
                        <Text style={styles.candidateAction}>Merge</Text>
                      </Pressable>
                    ))
                  )}
                </ScrollView>
              </>
            )}

            <Pressable style={styles.modalCancelBtn} onPress={() => setModal(null)}>
              <Text style={styles.modalCancelText}>Cancel</Text>
            </Pressable>
          </View>
        </View>
      </Modal>
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
  noticeText: { color: colors.ok, fontSize: 13, fontWeight: "600" },
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
  billReqText: { fontSize: 11, fontWeight: "800", color: BILL_PRINTED_COLOR },
  tableStatus: { fontSize: 11, fontWeight: "800", color: colors.inkSecondary },
  mergedText: { fontSize: 11, fontWeight: "700", color: colors.info },
  btnRow: { flexDirection: "row", gap: 6, marginTop: 4 },
  actionRow: { flexDirection: "row", flexWrap: "wrap", gap: 6, marginTop: 2 },
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
  billBtn: {
    flexGrow: 1,
    backgroundColor: BILL_PRINTED_BG,
    borderWidth: 1,
    borderColor: "#D8B4FE",
    paddingVertical: 6,
    paddingHorizontal: 8,
    borderRadius: radius.xs,
    alignItems: "center",
  },
  billBtnText: { color: BILL_PRINTED_COLOR, fontSize: 11, fontWeight: "700" },
  secondaryActionBtn: {
    flexGrow: 1,
    borderWidth: 1,
    borderColor: colors.lineStrong,
    backgroundColor: colors.surface,
    paddingVertical: 6,
    paddingHorizontal: 8,
    borderRadius: radius.xs,
    alignItems: "center",
  },
  secondaryActionText: { color: colors.inkSecondary, fontSize: 11, fontWeight: "700" },
  btnDisabled: { opacity: 0.45 },
  modalBackdrop: {
    flex: 1,
    backgroundColor: "rgba(15, 23, 20, 0.5)",
    justifyContent: "center",
    padding: spacing.lg,
  },
  modalCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    padding: spacing.md,
    maxHeight: "75%",
    gap: spacing.sm,
  },
  modalTitle: { fontSize: 17, fontWeight: "800", color: colors.ink },
  modalSubtitle: { fontSize: 13, color: colors.muted },
  candidateList: { maxHeight: 280 },
  candidateRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingVertical: 10,
    paddingHorizontal: 10,
    borderWidth: 1,
    borderColor: colors.line,
    borderRadius: radius.xs,
    marginBottom: 8,
    backgroundColor: colors.surfaceAlt,
  },
  candidateName: { fontSize: 14, fontWeight: "800", color: colors.ink },
  candidateMeta: { fontSize: 12, color: colors.muted },
  candidateAction: { fontSize: 12, fontWeight: "800", color: colors.green },
  modalCancelBtn: {
    borderWidth: 1,
    borderColor: colors.lineStrong,
    borderRadius: radius.xs,
    paddingVertical: 10,
    alignItems: "center",
  },
  modalCancelText: { fontSize: 13, fontWeight: "700", color: colors.ink },
});

