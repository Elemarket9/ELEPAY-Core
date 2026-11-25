import React, { useState } from "react";
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import { ApiError, colors, DeviceCredential, ElepayClient, radius, spacing } from "@elepay/mobile-core";

interface Props {
  initialBaseUrl: string;
  onPaired: (baseUrl: string, device: DeviceCredential) => void;
}

export function PairDeviceScreen({ initialBaseUrl, onPaired }: Props) {
  const [baseUrl, setBaseUrl] = useState(initialBaseUrl);
  const [merchantCode, setMerchantCode] = useState("");
  const [pairingCode, setPairingCode] = useState("");
  const [deviceName, setDeviceName] = useState("Kitchen Tablet");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handlePair() {
    if (busy) return;
    const cleanUrl = baseUrl.trim();
    if (!cleanUrl) {
      setError("Enter the ELEPAY server URL.");
      return;
    }
    if (!/^\d{6}$/.test(merchantCode.trim())) {
      setError("Merchant code must be 6 digits.");
      return;
    }
    const cleanCode = pairingCode.replace(/\s/g, "");
    if (!/^\d{6}$/.test(cleanCode)) {
      setError("Pairing code must be 6 digits.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const client = new ElepayClient(cleanUrl);
      const device = await client.pairKitchenDevice(merchantCode.trim(), cleanCode, deviceName);
      onPaired(client.getBaseUrl(), device);
    } catch (e) {
      if (e instanceof ApiError && e.status === 400) {
        setError("Those pairing codes did not work. Check them or generate a new code in the Merchant Dashboard.");
      } else {
        setError(e instanceof Error ? e.message : String(e));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <KeyboardAvoidingView style={styles.root} behavior={Platform.OS === "ios" ? "padding" : undefined}>
      <ScrollView contentContainerStyle={styles.scroll} keyboardShouldPersistTaps="handled">
        <View style={styles.card}>
          <View style={styles.brandRow}>
            <View style={styles.brandBadge}>
              <Text style={styles.brandMark}>E</Text>
            </View>
            <Text style={styles.brandLabel}>ELEPAY KITCHEN DISPLAY</Text>
          </View>

          <Text style={styles.title}>Pair this kitchen screen</Text>
          <Text style={styles.subtitle}>
            In the ELEPAY Merchant Dashboard, open Restaurant &amp; KDS → Kitchen Display System and tap Pair a kitchen device.
          </Text>

          <Text style={styles.label}>ELEPAY Server URL</Text>
          <TextInput
            style={styles.input}
            value={baseUrl}
            onChangeText={setBaseUrl}
            autoCapitalize="none"
            autoCorrect={false}
            placeholder="https://api.elepay.gh"
            placeholderTextColor="#667A71"
          />

          <View style={styles.row}>
            <View style={{ flex: 1 }}>
              <Text style={styles.label}>Merchant Code</Text>
              <TextInput
                style={[styles.input, styles.codeInput]}
                keyboardType="number-pad"
                maxLength={6}
                value={merchantCode}
                onChangeText={setMerchantCode}
                placeholder="123456"
                placeholderTextColor="#667A71"
              />
            </View>
            <View style={{ flex: 1 }}>
              <Text style={styles.label}>Pairing Code</Text>
              <TextInput
                style={[styles.input, styles.codeInput]}
                keyboardType="number-pad"
                maxLength={7}
                value={pairingCode}
                onChangeText={setPairingCode}
                placeholder="000 000"
                placeholderTextColor="#667A71"
              />
            </View>
          </View>

          <Text style={styles.label}>Device Name</Text>
          <TextInput
            style={styles.input}
            value={deviceName}
            onChangeText={setDeviceName}
            maxLength={64}
            placeholder="Grill Station Tablet"
            placeholderTextColor="#667A71"
          />

          {error && (
            <View style={styles.errorBox}>
              <Text style={styles.errorText}>{error}</Text>
            </View>
          )}

          <Pressable style={[styles.primaryBtn, busy && { opacity: 0.6 }]} disabled={busy} onPress={() => void handlePair()}>
            {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.primaryBtnText}>Pair Kitchen Screen</Text>}
          </Pressable>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.kdsBg },
  scroll: { flexGrow: 1, justifyContent: "center", alignItems: "center", padding: spacing.lg },
  card: {
    width: "100%",
    maxWidth: 560,
    backgroundColor: colors.kdsSurface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.kdsBorder,
    padding: spacing.xl,
  },
  brandRow: { flexDirection: "row", alignItems: "center", gap: 10, marginBottom: spacing.md },
  brandBadge: {
    width: 34,
    height: 34,
    borderRadius: 9,
    backgroundColor: colors.green,
    alignItems: "center",
    justifyContent: "center",
  },
  brandMark: { color: colors.gold, fontSize: 20, fontWeight: "800" },
  brandLabel: { color: colors.kdsMuted, fontSize: 12, fontWeight: "800", letterSpacing: 1 },
  title: { color: "#fff", fontSize: 24, fontWeight: "800" },
  subtitle: { color: colors.kdsMuted, fontSize: 13, marginTop: 6, marginBottom: spacing.md },
  row: { flexDirection: "row", gap: 12 },
  label: { color: "#CBD6D1", fontSize: 12, fontWeight: "700", marginTop: spacing.sm, marginBottom: 6 },
  input: {
    backgroundColor: "#0F1513",
    borderWidth: 1,
    borderColor: "#32423C",
    borderRadius: radius.sm,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 16,
    color: "#fff",
  },
  codeInput: { fontSize: 20, fontWeight: "800", letterSpacing: 3 },
  errorBox: {
    marginTop: spacing.md,
    padding: 12,
    borderRadius: radius.sm,
    backgroundColor: "#451A1A",
    borderWidth: 1,
    borderColor: "#991B1B",
  },
  errorText: { color: "#FECACA", fontSize: 13, fontWeight: "600" },
  primaryBtn: {
    marginTop: spacing.lg,
    backgroundColor: colors.green,
    paddingVertical: 16,
    borderRadius: radius.sm,
    alignItems: "center",
  },
  primaryBtnText: { color: "#fff", fontSize: 16, fontWeight: "800" },
});
