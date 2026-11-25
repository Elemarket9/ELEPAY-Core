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

type Mode = "login" | "register" | "dev" | "token";

export function SignInScreen({ initialBaseUrl, onSignedIn }: Props) {
  const [baseUrl, setBaseUrl] = useState(initialBaseUrl);
  const [mode, setMode] = useState<Mode>("login");

  const [identifier, setIdentifier] = useState("");
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");

  const [challengeId, setChallengeId] = useState<string | null>(null);
  const [maskedEmail, setMaskedEmail] = useState<string | null>(null);
  const [maskedPhone, setMaskedPhone] = useState<string | null>(null);
  const [emailCode, setEmailCode] = useState("");
  const [smsCode, setSmsCode] = useState("");

  const [subject, setSubject] = useState("");
  const [bearerToken, setBearerToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function submit() {
    const cleanUrl = baseUrl.trim();
    if (!cleanUrl) {
      setError("Enter your ELEPAY server URL.");
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const client = new ElepayClient(cleanUrl);
      if (challengeId) {
        if (emailCode.trim().length !== 6 || smsCode.trim().length !== 6) {
          throw new Error("Enter both the 6-digit email code and the 6-digit SMS code.");
        }
        const res = await client.verifyEmailAndSms(challengeId, emailCode, smsCode);
        if (res.verified && res.accessToken) {
          onSignedIn(client.getBaseUrl(), res.accessToken);
        } else {
          setNotice("One channel verified — enter the remaining code to finish signing in.");
        }
      } else if (mode === "login") {
        if (!identifier.trim() || !password) {
          throw new Error("Enter your email or phone number and password.");
        }
        const ch = await client.loginWithPassword(identifier.trim(), password);
        setChallengeId(ch.challengeId);
        setMaskedEmail(ch.maskedEmail);
        setMaskedPhone(ch.maskedPhone);
        setEmailCode("");
        setSmsCode("");
        setNotice("Verification codes sent to your email and SMS.");
      } else if (mode === "register") {
        if (!fullName.trim() || !email.trim() || !phone.trim() || password.length < 8) {
          throw new Error("Fill in name, email, phone, and a password (min. 8 characters).");
        }
        const ch = await client.registerAccount({
          fullName: fullName.trim(),
          email: email.trim(),
          phone: phone.trim(),
          password,
        });
        setChallengeId(ch.challengeId);
        setMaskedEmail(ch.maskedEmail);
        setMaskedPhone(ch.maskedPhone);
        setEmailCode("");
        setSmsCode("");
        setNotice("Account created. Enter both your email and SMS codes to activate.");
      } else if (mode === "dev") {
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

  async function resendCodes() {
    if (!challengeId || busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const client = new ElepayClient(baseUrl.trim());
      await client.resendVerificationCodes(challengeId);
      setEmailCode("");
      setSmsCode("");
      setNotice("Fresh verification codes sent to your email and SMS.");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  function resetChallenge() {
    setChallengeId(null);
    setEmailCode("");
    setSmsCode("");
    setError(null);
    setNotice(null);
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
            editable={!challengeId}
            placeholder="https://api.elepay.gh"
            placeholderTextColor={colors.subtle}
          />

          {!challengeId && (
            <View style={styles.segment}>
              {(["login", "register", "dev", "token"] as Mode[]).map((m) => (
                <Pressable
                  key={m}
                  style={[styles.segBtn, mode === m && styles.segBtnActive]}
                  onPress={() => { setMode(m); setError(null); setNotice(null); }}
                >
                  <Text style={[styles.segText, mode === m && styles.segTextActive]}>
                    {m === "login" ? "Sign In" : m === "register" ? "Sign Up" : m === "dev" ? "Dev" : "Token"}
                  </Text>
                </Pressable>
              ))}
            </View>
          )}

          {challengeId ? (
            <>
              <Text style={styles.verifyHint}>
                Enter the 6-digit codes sent to {maskedEmail ?? "your email"} and {maskedPhone ?? "your phone"}.
              </Text>
              <Text style={styles.label}>Email Verification Code</Text>
              <TextInput
                style={styles.input}
                value={emailCode}
                onChangeText={(v) => setEmailCode(v.replace(/\D/g, "").slice(0, 6))}
                keyboardType="number-pad"
                maxLength={6}
                placeholder="6-digit email code"
                placeholderTextColor={colors.subtle}
              />
              <Text style={styles.label}>SMS Verification Code</Text>
              <TextInput
                style={styles.input}
                value={smsCode}
                onChangeText={(v) => setSmsCode(v.replace(/\D/g, "").slice(0, 6))}
                keyboardType="number-pad"
                maxLength={6}
                placeholder="6-digit SMS code"
                placeholderTextColor={colors.subtle}
              />
              <View style={styles.linkRow}>
                <Pressable disabled={busy} onPress={() => void resendCodes()}>
                  <Text style={styles.linkText}>Resend Email &amp; SMS codes</Text>
                </Pressable>
                <Pressable disabled={busy} onPress={resetChallenge}>
                  <Text style={styles.linkText}>Back</Text>
                </Pressable>
              </View>
            </>
          ) : mode === "login" ? (
            <>
              <Text style={styles.label}>Email or Phone Number</Text>
              <TextInput
                style={styles.input}
                value={identifier}
                onChangeText={setIdentifier}
                autoCapitalize="none"
                autoCorrect={false}
                placeholder="manager@restaurant.gh or 0241234567"
                placeholderTextColor={colors.subtle}
              />
              <Text style={styles.label}>Password</Text>
              <TextInput
                style={styles.input}
                value={password}
                onChangeText={setPassword}
                secureTextEntry
                placeholder="Enter your password"
                placeholderTextColor={colors.subtle}
              />
            </>
          ) : mode === "register" ? (
            <>
              <Text style={styles.label}>Full Name</Text>
              <TextInput
                style={styles.input}
                value={fullName}
                onChangeText={setFullName}
                placeholder="Kofi Mensah"
                placeholderTextColor={colors.subtle}
              />
              <Text style={styles.label}>Email Address</Text>
              <TextInput
                style={styles.input}
                value={email}
                onChangeText={setEmail}
                autoCapitalize="none"
                keyboardType="email-address"
                placeholder="manager@restaurant.gh"
                placeholderTextColor={colors.subtle}
              />
              <Text style={styles.label}>Phone Number (SMS)</Text>
              <TextInput
                style={styles.input}
                value={phone}
                onChangeText={setPhone}
                keyboardType="phone-pad"
                placeholder="+233241234567 or 0241234567"
                placeholderTextColor={colors.subtle}
              />
              <Text style={styles.label}>Password (min. 8 chars)</Text>
              <TextInput
                style={styles.input}
                value={password}
                onChangeText={setPassword}
                secureTextEntry
                placeholder="Create a strong password"
                placeholderTextColor={colors.subtle}
              />
            </>
          ) : mode === "dev" ? (
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

          {notice && (
            <View style={styles.noticeBox}>
              <Text style={styles.noticeText}>{notice}</Text>
            </View>
          )}
          {error && (
            <View style={styles.errorBox}>
              <Text style={styles.errorText}>{error}</Text>
            </View>
          )}

          <Pressable style={[styles.primaryBtn, busy && { opacity: 0.6 }]} disabled={busy} onPress={() => void submit()}>
            {busy ? (
              <ActivityIndicator color="#fff" />
            ) : (
              <Text style={styles.primaryBtnText}>
                {challengeId
                  ? "Verify & Open POS"
                  : mode === "login"
                    ? "Continue with Email & SMS Code"
                    : mode === "register"
                      ? "Create Account & Send Codes"
                      : "Open POS Register"}
              </Text>
            )}
          </Pressable>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.greenDeep },
  scroll: { flexGrow: 1, justifyContent: "center", alignItems: "center", padding: spacing.lg },
  card: {
    width: "100%",
    maxWidth: 480,
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.xl,
    gap: spacing.sm,
  },
  brandRow: { flexDirection: "row", alignItems: "center", gap: spacing.md, marginBottom: spacing.sm },
  brandBadge: {
    width: 48,
    height: 48,
    borderRadius: 14,
    backgroundColor: colors.gold,
    alignItems: "center",
    justifyContent: "center",
  },
  brandMark: { fontSize: 24, fontWeight: "800", color: colors.greenDeep },
  title: { fontSize: 20, fontWeight: "800", color: colors.ink },
  subtitle: { fontSize: 13, color: colors.muted, marginTop: 2 },
  label: { fontSize: 13, fontWeight: "600", color: colors.inkSecondary, marginTop: spacing.xs },
  input: {
    borderWidth: 1,
    borderColor: colors.lineStrong,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: 11,
    fontSize: 15,
    color: colors.ink,
    backgroundColor: "#fff",
  },
  segment: {
    flexDirection: "row",
    backgroundColor: colors.surfaceAlt,
    borderRadius: radius.sm,
    padding: 4,
    gap: 4,
    marginVertical: spacing.xs,
  },
  segBtn: { flex: 1, paddingVertical: 8, alignItems: "center", borderRadius: 6 },
  segBtnActive: { backgroundColor: colors.surface, elevation: 1 },
  segText: { fontSize: 12, fontWeight: "600", color: colors.muted },
  segTextActive: { color: colors.green },
  verifyHint: { fontSize: 13, color: colors.muted, lineHeight: 19 },
  linkRow: { flexDirection: "row", justifyContent: "space-between", marginTop: 4 },
  linkText: { fontSize: 13, fontWeight: "600", color: colors.green },
  noticeBox: { backgroundColor: colors.greenSoft, borderRadius: radius.sm, padding: spacing.sm },
  noticeText: { color: colors.greenDeep, fontSize: 13, fontWeight: "600" },
  errorBox: { backgroundColor: colors.dangerBg, borderRadius: radius.sm, padding: spacing.sm },
  errorText: { color: colors.danger, fontSize: 13 },
  primaryBtn: {
    backgroundColor: colors.green,
    borderRadius: radius.sm,
    paddingVertical: 14,
    alignItems: "center",
    marginTop: spacing.md,
  },
  primaryBtnText: { color: "#fff", fontSize: 15, fontWeight: "700" },
});
