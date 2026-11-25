import React, { useState } from "react";
import {
  ActivityIndicator,
  Modal,
  Pressable,
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
  parseMoney,
  PosCashier,
  PosShift,
  PosTerminal,
  radius,
  spacing,
} from "@elepay/mobile-core";

interface Props {
  visible: boolean;
  onClose: () => void;
  client: ElepayClient;
  merchantId: string;
  terminals: PosTerminal[];
  selectedTerminal: PosTerminal | null;
  cashier: { id: string; badgeNumber: string; name?: string } | null;
  shift: PosShift | null;
  onSelectTerminal: (t: PosTerminal) => void;
  onSelectCashier: (c: { id: string; badgeNumber: string; name?: string }) => void;
  onShiftChanged: (s: PosShift | null) => void;
}

export function TerminalShiftModal({
  visible,
  onClose,
  client,
  merchantId,
  terminals,
  selectedTerminal,
  cashier,
  shift,
  onSelectTerminal,
  onSelectCashier,
  onShiftChanged,
}: Props) {
  const [badge, setBadge] = useState("");
  const [pin, setPin] = useState("");
  const [cashAmount, setCashAmount] = useState("0.00");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function verifyCashier() {
    if (!badge.trim() || !pin.trim()) {
      setError("Enter both badge number and PIN.");
      return;
    }
    setBusy(true);
    setError(null);
    setMsg(null);
    try {
      const res = await client.call<PosCashier>("/v1/pos/cashiers/auth", {
        body: { merchantId, badgeNumber: badge.trim(), pin: pin.trim() },
      });
      onSelectCashier({ id: res.id, badgeNumber: res.badgeNumber, name: res.name });
      setPin("");
      setMsg(`Cashier ${res.name} (${res.badgeNumber}) verified.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function toggleShift() {
    if (!selectedTerminal || !cashier) {
      setError("Select a terminal and verify a cashier first.");
      return;
    }
    const pesewas = parseMoney(cashAmount);
    if (pesewas === null || pesewas < 0) {
      setError("Enter a valid GH₵ cash amount, e.g. 100.00");
      return;
    }
    setBusy(true);
    setError(null);
    setMsg(null);
    try {
      if (shift) {
        const closed = await client.call<PosShift>(`/v1/pos/shifts/${shift.id}/close`, {
          body: { closingBalancePesewas: pesewas, notes: null },
        });
        onShiftChanged(null);
        setMsg(`Shift closed. Discrepancy: ${formatMoney(closed.cashDiscrepancyPesewas ?? 0)}`);
      } else {
        const opened = await client.call<PosShift>("/v1/pos/shifts/open", {
          body: {
            terminalId: selectedTerminal.id,
            cashierId: cashier.id,
            merchantId,
            openingBalancePesewas: pesewas,
            notes: null,
          },
        });
        onShiftChanged(opened);
        setMsg(`Shift opened with ${formatMoney(opened.openingBalancePesewas)} float.`);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <View style={styles.backdrop}>
        <View style={styles.sheet}>
          <View style={styles.header}>
            <Text style={styles.title}>Terminal, Cashier &amp; Shift</Text>
            <Pressable onPress={onClose}>
              <Text style={styles.closeText}>Done</Text>
            </Pressable>
          </View>

          <ScrollView showsVerticalScrollIndicator={false}>
            {error && <Text style={styles.errorText}>{error}</Text>}
            {msg && <Text style={styles.okText}>{msg}</Text>}

            <Text style={styles.sectionTitle}>1. Select POS Terminal</Text>
            <View style={styles.chipWrap}>
              {terminals.length === 0 ? (
                <Text style={styles.mutedText}>No terminals registered yet. Register one in the Merchant Dashboard.</Text>
              ) : (
                terminals.map((t) => {
                  const active = selectedTerminal?.id === t.id;
                  return (
                    <Pressable
                      key={t.id}
                      style={[styles.chip, active && styles.chipActive]}
                      onPress={() => onSelectTerminal(t)}
                    >
                      <Text style={[styles.chipText, active && styles.chipTextActive]}>
                        {t.serialNumber} ({t.model})
                      </Text>
                    </Pressable>
                  );
                })
              )}
            </View>

            <Text style={styles.sectionTitle}>2. Cashier PIN Sign-In</Text>
            {cashier && (
              <Text style={styles.activeMeta}>
                Active cashier: {cashier.name ?? cashier.badgeNumber} ({cashier.badgeNumber})
              </Text>
            )}
            <View style={styles.row}>
              <TextInput
                style={[styles.input, { flex: 1 }]}
                value={badge}
                onChangeText={setBadge}
                placeholder="Badge (B-001)"
                placeholderTextColor={colors.subtle}
              />
              <TextInput
                style={[styles.input, { flex: 1 }]}
                value={pin}
                onChangeText={setPin}
                secureTextEntry
                keyboardType="number-pad"
                placeholder="PIN"
                placeholderTextColor={colors.subtle}
              />
            </View>
            <Pressable style={styles.secondaryBtn} disabled={busy} onPress={() => void verifyCashier()}>
              <Text style={styles.secondaryBtnText}>Verify Cashier PIN</Text>
            </Pressable>

            <Text style={styles.sectionTitle}>3. Register Shift</Text>
            <Text style={styles.activeMeta}>
              {shift ? `Shift OPEN · Float ${formatMoney(shift.openingBalancePesewas)}` : "No active shift"}
            </Text>
            <TextInput
              style={styles.input}
              keyboardType="decimal-pad"
              value={cashAmount}
              onChangeText={setCashAmount}
              placeholder={shift ? "Closing cash count (GH₵)" : "Opening float (GH₵)"}
              placeholderTextColor={colors.subtle}
            />
            <Pressable style={styles.primaryBtn} disabled={busy} onPress={() => void toggleShift()}>
              {busy ? (
                <ActivityIndicator color="#fff" />
              ) : (
                <Text style={styles.primaryBtnText}>{shift ? "Close Active Shift" : "Open Shift"}</Text>
              )}
            </Pressable>
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
    maxHeight: "88%",
  },
  header: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", marginBottom: spacing.sm },
  title: { fontSize: 18, fontWeight: "800", color: colors.ink },
  closeText: { fontSize: 14, fontWeight: "700", color: colors.green },
  sectionTitle: { fontSize: 13, fontWeight: "800", color: colors.ink, marginTop: spacing.md, marginBottom: 8 },
  activeMeta: { fontSize: 12, color: colors.greenDeep, fontWeight: "600", marginBottom: 8 },
  mutedText: { fontSize: 12, color: colors.muted },
  chipWrap: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  chip: {
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: radius.sm,
    borderWidth: 1.5,
    borderColor: colors.line,
    backgroundColor: colors.surfaceAlt,
  },
  chipActive: { borderColor: colors.green, backgroundColor: colors.greenSoft },
  chipText: { fontSize: 13, fontWeight: "600", color: colors.inkSecondary },
  chipTextActive: { color: colors.greenDeep, fontWeight: "800" },
  row: { flexDirection: "row", gap: 8 },
  input: {
    borderWidth: 1,
    borderColor: colors.lineStrong,
    borderRadius: radius.sm,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 15,
    color: colors.ink,
    marginBottom: 8,
  },
  secondaryBtn: {
    borderWidth: 1,
    borderColor: colors.green,
    paddingVertical: 11,
    borderRadius: radius.sm,
    alignItems: "center",
  },
  secondaryBtnText: { color: colors.greenDeep, fontSize: 14, fontWeight: "700" },
  primaryBtn: {
    backgroundColor: colors.green,
    paddingVertical: 13,
    borderRadius: radius.sm,
    alignItems: "center",
    marginTop: 4,
    marginBottom: spacing.md,
  },
  primaryBtnText: { color: "#fff", fontSize: 15, fontWeight: "700" },
  errorText: { color: colors.danger, fontSize: 13, fontWeight: "600", marginBottom: 6 },
  okText: { color: colors.ok, fontSize: 13, fontWeight: "700", marginBottom: 6 },
});
