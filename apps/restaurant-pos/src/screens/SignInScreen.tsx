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
import { colors, ElepayClient, radius, spacing } from "@elepay/mobile-core";

interface Props {
  initialBaseUrl: string;
  onSignedIn: (baseUrl: string, token: string) => void;
}

export function SignInScreen({ initialBaseUrl, onSignedIn }: Props) {
  const [baseUrl, setBaseUrl] = useState(initialBaseUrl);
  const [mode, setMode] = useState<"dev" | "token">("dev");
  const [subject, setSubject] = useState("");
  const [bearerToken, setBearerToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    const cleanUrl = baseUrl.trim();
    if (!cleanUrl) {
      setError("Enter your ELEPAY server URL.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const client = new ElepayClient(cleanUrl);
      if (mode === "dev") {
        if (!subject.trim()) throw new Error("Enter a staff/owner subject (e.g. cashier@restaurant.gh).");
        const token = await client.signInDev(subject.trim());
        onSignedIn(client.getBaseUrl(), token);
      } else {
        const token = bearerToken.trim();
        if (!token) throw new Error("Paste a valid OIDC/JWT access token.");
        client.setToken(token);
        await client.call("/v1/merchants");
        onSignedIn(client.getBaseUrl(), token);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
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
            <View>
              <Text style={styles.title}>ELEPAY Restaurant POS</Text>
              <Text style={styles.subtitle}>Tables, Kitchen Routing, Cash, MoMo &amp; GhQR</Text>
            </View>
          </View>

          <Text style={styles.label}>ELEPAY API Server URL</Text>
          <TextInput
            style={styles.input}
            value={baseUrl}
            onChangeText={setBaseUrl}
            autoCapitalize="none"
            autoCorrect={false}
            placeholder="https://api.elepay.gh"
            placeholderTextColor={colors.subtle}
          />

          <View style={styles.segment}>
            <Pressable style={[styles.segBtn, mode === "dev" && styles.segBtnActive]} onPress={() => setMode("dev")}>
              <Text style={[styles.segText, mode === "dev" && styles.segTextActive]}>Sandbox / Dev Sign-in</Text>
            </Pressable>
            <Pressable style={[styles.segBtn, mode === "token" && styles.segBtnActive]} onPress={() => setMode("token")}>
              <Text style={[styles.segText, mode === "token" && styles.segTextActive]}>Production Token</Text>
            </Pressable>
          </View>

          {mode === "dev" ? (
            <>
              <Text style={styles.label}>Staff / Manager Subject</Text>
              <TextInput
                style={styles.input}
                value={subject}
                onChangeText={setSubject}
                autoCapitalize="none"
                autoCorrect={false}
                placeholder="manager@accrakitchen.gh"
                placeholderTextColor={colors.subtle}
              />
            </>
          ) : (
            <>
              <Text style={styles.label}>Bearer Access Token</Text>
              <TextInput
                style={[styles.input, { minHeight: 72 }]}
                value={bearerToken}
                onChangeText={setBearerToken}
                autoCapitalize="none"
                autoCorrect={false}
                multiline
                placeholder="eyJhbGciOi..."
                placeholderTextColor={colors.subtle}
              />
            </>
          )}

          {error && (
            <View style={styles.errorBox}>
              <Text style={styles.errorText}>{error}</Text>
            </View>
          )}

          <Pressable style={[styles.primaryBtn, busy && { opacity: 0.6 }]} disabled={busy} onPress={() => void submit()}>
            {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.primaryBtnText}>Open POS Register</Text>}
          </Pressable>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  scroll: { flexGrow: 1, justifyContent: "center", alignItems: "center", padding: spacing.lg },
  card: {
    width: "100%",
    maxWidth: 460,
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.xl,
    borderWidth: 1,
    borderColor: colors.line,
  },
  brandRow: { flexDirection: "row", alignItems: "center", gap: 12, marginBottom: spacing.md },
  brandBadge: {
    width: 46,
    height: 46,
    borderRadius: 12,
    backgroundColor: colors.greenDeep,
    alignItems: "center",
    justifyContent: "center",
  },
  brandMark: { color: colors.gold, fontSize: 24, fontWeight: "800" },
  title: { fontSize: 20, fontWeight: "800", color: colors.ink },
  subtitle: { fontSize: 12, color: colors.muted, marginTop: 2 },
  label: { fontSize: 12, fontWeight: "700", color: colors.inkSecondary, marginTop: spacing.sm, marginBottom: 6 },
  input: {
    borderWidth: 1,
    borderColor: colors.lineStrong,
    borderRadius: radius.sm,
    paddingHorizontal: 12,
    paddingVertical: 11,
    fontSize: 15,
    color: colors.ink,
    backgroundColor: colors.surface,
  },
  segment: {
    flexDirection: "row",
    backgroundColor: "#EAEFF0",
    padding: 3,
    borderRadius: radius.sm,
    marginTop: spacing.md,
  },
  segBtn: { flex: 1, paddingVertical: 8, alignItems: "center", borderRadius: 7 },
  segBtnActive: { backgroundColor: colors.surface },
  segText: { fontSize: 12, fontWeight: "600", color: colors.muted },
  segTextActive: { color: colors.greenDeep, fontWeight: "700" },
  errorBox: {
    marginTop: spacing.md,
    padding: 10,
    borderRadius: radius.sm,
    backgroundColor: colors.dangerBg,
    borderWidth: 1,
    borderColor: colors.dangerBorder,
  },
  errorText: { color: colors.danger, fontSize: 13, fontWeight: "600" },
  primaryBtn: {
    marginTop: spacing.lg,
    backgroundColor: colors.green,
    paddingVertical: 14,
    borderRadius: radius.sm,
    alignItems: "center",
  },
  primaryBtnText: { color: "#fff", fontSize: 15, fontWeight: "700" },
});
