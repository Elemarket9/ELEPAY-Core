import React, { useCallback, useEffect, useState } from "react";
import {
  Modal,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import {
  colors,
  ElepayClient,
  formatMoney,
  MerchantView,
  newKey,
  parseMoney,
  radius,
  spacing,
  TransactionLine,
} from "@elepay/mobile-core";

interface Props {
  client: ElepayClient;
  merchant: MerchantView;
}

export function SalesScreen({ client, merchant }: Props) {
  const [rows, setRows] = useState<TransactionLine[]>([]);
  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState<"all" | "settled" | "pending">("all");
  const [selected, setSelected] = useState<TransactionLine | null>(null);
  const [refundAmount, setRefundAmount] = useState("");
  const [refundReason, setRefundReason] = useState("");
  const [refundBusy, setRefundBusy] = useState(false);
  const [refundMsg, setRefundMsg] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      const list = await client.call<TransactionLine[]>(`/v1/merchants/${merchant.id}/transactions`);
      setRows(list);
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

  const filtered = rows.filter((r) => {
    const matchStatus = statusFilter === "all" || r.status.toLowerCase() === statusFilter;
    const q = search.trim().toLowerCase();
    const matchQuery = !q || r.reference.toLowerCase().includes(q) || (r.description ?? "").toLowerCase().includes(q);
    return matchStatus && matchQuery;
  });

  async function submitRefund() {
    if (!selected || refundBusy) return;
    const maxRefundable = selected.amount - selected.refunded - selected.disputed;
    const pesewas = parseMoney(refundAmount);
    if (!pesewas || pesewas <= 0 || pesewas > maxRefundable) {
      setRefundMsg(`Enter a valid amount up to ${formatMoney(maxRefundable)}.`);
      return;
    }
    if (!refundReason.trim()) {
      setRefundMsg("Enter a refund reason.");
      return;
    }
    setRefundBusy(true);
    setRefundMsg(null);
    try {
      await client.call(
        `/v1/merchants/${merchant.id}/checkouts/${selected.reference}/allocations/${selected.line}/refunds`,
        {
          idempotencyKey: newKey(),
          body: { amount: pesewas, reason: refundReason.trim() },
        },
      );
      setSelected(null);
      setRefundAmount("");
      setRefundReason("");
      await load();
    } catch (e) {
      setRefundMsg(e instanceof Error ? e.message : String(e));
    } finally {
      setRefundBusy(false);
    }
  }

  return (
    <View style={styles.root}>
      <View style={styles.toolbar}>
        <TextInput
          style={styles.searchInput}
          value={search}
          onChangeText={setSearch}
          placeholder="Search reference or item…"
          placeholderTextColor={colors.subtle}
        />
        <View style={styles.pills}>
          {(["all", "settled", "pending"] as const).map((st) => (
            <Pressable
              key={st}
              style={[styles.pill, statusFilter === st && styles.pillActive]}
              onPress={() => setStatusFilter(st)}
            >
              <Text style={[styles.pillText, statusFilter === st && styles.pillTextActive]}>
                {st === "all" ? "All" : st.charAt(0).toUpperCase() + st.slice(1)}
              </Text>
            </Pressable>
          ))}
        </View>
      </View>

      <ScrollView
        contentContainerStyle={styles.list}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void load()} tintColor={colors.green} />}
      >
        {error && <Text style={styles.errorText}>{error}</Text>}
        {filtered.length === 0 ? (
          <View style={styles.emptyCard}>
            <Text style={styles.emptyText}>No matching sales found.</Text>
          </View>
        ) : (
          filtered.map((tx) => (
            <Pressable
              key={`${tx.reference}-${tx.line}`}
              style={styles.card}
              onPress={() => {
                setSelected(tx);
                setRefundAmount("");
                setRefundReason("");
                setRefundMsg(null);
              }}
            >
              <View style={{ flex: 1 }}>
                <Text style={styles.txTitle}>{tx.description || `${tx.kind} · ${tx.collector}`}</Text>
                <Text style={styles.txSub}>{tx.reference}</Text>
              </View>
              <View style={{ alignItems: "flex-end" }}>
                <Text style={styles.txAmount}>{formatMoney(tx.net)}</Text>
                <View style={[styles.tag, tx.status === "settled" ? styles.tagOk : styles.tagWarn]}>
                  <Text style={[styles.tagText, tx.status === "settled" ? styles.tagTextOk : styles.tagTextWarn]}>
                    {tx.status.toUpperCase()}
                  </Text>
                </View>
              </View>
            </Pressable>
          ))
        )}
      </ScrollView>

      <Modal visible={!!selected} transparent animationType="slide" onRequestClose={() => setSelected(null)}>
        <View style={styles.modalBackdrop}>
          <View style={styles.modalSheet}>
            {selected && (
              <>
                <View style={styles.modalHeader}>
                  <Text style={styles.modalTitle}>Sale Receipt</Text>
                  <Pressable onPress={() => setSelected(null)}>
                    <Text style={styles.closeText}>Close</Text>
                  </Pressable>
                </View>
                <Text style={styles.monoSub}>{selected.reference}</Text>
                <View style={styles.summaryBox}>
                  <Row label="Description" value={selected.description || selected.kind} />
                  <Row label="Gross Amount" value={formatMoney(selected.amount)} />
                  <Row label="Processing Fee" value={`-${formatMoney(selected.fee)}`} />
                  <Row label="Refunded" value={formatMoney(selected.refunded)} />
                  <Row label="Net Receivable" value={formatMoney(selected.net)} bold />
                </View>

                {selected.refunded + selected.disputed < selected.amount && (
                  <View style={{ marginTop: spacing.md }}>
                    <Text style={styles.sectionLabel}>Issue Refund</Text>
                    <TextInput
                      style={styles.input}
                      keyboardType="decimal-pad"
                      value={refundAmount}
                      onChangeText={setRefundAmount}
                      placeholder={`Amount GH₵ (max ${((selected.amount - selected.refunded - selected.disputed) / 100).toFixed(2)})`}
                      placeholderTextColor={colors.subtle}
                    />
                    <TextInput
                      style={[styles.input, { marginTop: 8 }]}
                      value={refundReason}
                      onChangeText={setRefundReason}
                      placeholder="Refund reason"
                      placeholderTextColor={colors.subtle}
                    />
                    {refundMsg && <Text style={styles.errorText}>{refundMsg}</Text>}
                    <Pressable style={styles.refundBtn} disabled={refundBusy} onPress={() => void submitRefund()}>
                      <Text style={styles.refundBtnText}>{refundBusy ? "Recording…" : "Submit Refund"}</Text>
                    </Pressable>
                  </View>
                )}
              </>
            )}
          </View>
        </View>
      </Modal>
    </View>
  );
}

function Row({ label, value, bold }: { label: string; value: string; bold?: boolean }) {
  return (
    <View style={styles.summaryRow}>
      <Text style={[styles.summaryLabel, bold && { fontWeight: "700", color: colors.ink }]}>{label}</Text>
      <Text style={[styles.summaryValue, bold && { fontWeight: "800", color: colors.greenDeep }]}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  toolbar: { padding: spacing.md, backgroundColor: colors.surface, borderBottomWidth: 1, borderBottomColor: colors.line },
  searchInput: {
    borderWidth: 1,
    borderColor: colors.lineStrong,
    borderRadius: radius.sm,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 14,
    color: colors.ink,
    backgroundColor: colors.surfaceAlt,
  },
  pills: { flexDirection: "row", gap: 8, marginTop: 10 },
  pill: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surface,
  },
  pillActive: { backgroundColor: colors.greenDeep, borderColor: colors.greenDeep },
  pillText: { fontSize: 12, fontWeight: "600", color: colors.inkSecondary },
  pillTextActive: { color: "#fff" },
  list: { padding: spacing.md, gap: 10, paddingBottom: 32 },
  card: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    padding: spacing.md,
    borderWidth: 1,
    borderColor: colors.line,
    gap: 10,
  },
  txTitle: { fontSize: 14, fontWeight: "700", color: colors.ink },
  txSub: { fontSize: 11, color: colors.muted, marginTop: 3 },
  txAmount: { fontSize: 15, fontWeight: "800", color: colors.ink },
  tag: { marginTop: 4, paddingHorizontal: 8, paddingVertical: 2, borderRadius: radius.pill },
  tagOk: { backgroundColor: colors.okBg },
  tagWarn: { backgroundColor: colors.warnBg },
  tagText: { fontSize: 10, fontWeight: "700" },
  tagTextOk: { color: colors.ok },
  tagTextWarn: { color: colors.warn },
  emptyCard: { backgroundColor: colors.surface, borderRadius: radius.md, padding: spacing.xl, alignItems: "center" },
  emptyText: { color: colors.muted, fontSize: 13 },
  errorText: { color: colors.danger, fontSize: 13, fontWeight: "600", marginTop: 6 },
  modalBackdrop: { flex: 1, backgroundColor: "rgba(15,23,20,0.55)", justifyContent: "flex-end" },
  modalSheet: {
    backgroundColor: colors.surface,
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    padding: spacing.lg,
  },
  modalHeader: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  modalTitle: { fontSize: 18, fontWeight: "800", color: colors.ink },
  closeText: { fontSize: 14, fontWeight: "700", color: colors.green },
  monoSub: { fontSize: 12, color: colors.muted, marginTop: 4, marginBottom: spacing.md },
  summaryBox: {
    backgroundColor: colors.surfaceAlt,
    borderRadius: radius.md,
    padding: spacing.md,
    borderWidth: 1,
    borderColor: colors.line,
    gap: 8,
  },
  summaryRow: { flexDirection: "row", justifyContent: "space-between" },
  summaryLabel: { fontSize: 13, color: colors.muted },
  summaryValue: { fontSize: 13, fontWeight: "600", color: colors.ink },
  sectionLabel: { fontSize: 13, fontWeight: "700", color: colors.ink, marginBottom: 8 },
  input: {
    borderWidth: 1,
    borderColor: colors.lineStrong,
    borderRadius: radius.sm,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 14,
    color: colors.ink,
  },
  refundBtn: {
    marginTop: 12,
    backgroundColor: colors.green,
    paddingVertical: 12,
    borderRadius: radius.sm,
    alignItems: "center",
  },
  refundBtnText: { color: "#fff", fontSize: 14, fontWeight: "700" },
});
