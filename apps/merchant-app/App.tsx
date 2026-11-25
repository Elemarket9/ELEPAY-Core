import React, { useEffect, useMemo, useState } from "react";
import { Platform, Pressable, StyleSheet, Text, View } from "react-native";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import * as SecureStore from "expo-secure-store";
import { StatusBar } from "expo-status-bar";
import { colors, ElepayClient, MerchantView, radius, spacing } from "@elepay/mobile-core";
import { SignInScreen } from "./src/screens/SignInScreen";
import { HomeScreen } from "./src/screens/HomeScreen";
import { SalesScreen } from "./src/screens/SalesScreen";
import { CollectScreen } from "./src/screens/CollectScreen";
import { WithdrawScreen } from "./src/screens/WithdrawScreen";

const KEY_URL = "elepay_merchant_base_url";
const KEY_TOKEN = "elepay_merchant_token";
const DEFAULT_URL = Platform.OS === "android" ? "http://10.0.2.2:8080" : "http://localhost:8080";

type Tab = "home" | "sales" | "collect" | "withdraw";

export default function App() {
  const [booted, setBooted] = useState(false);
  const [baseUrl, setBaseUrl] = useState(DEFAULT_URL);
  const [token, setToken] = useState<string | null>(null);
  const [merchants, setMerchants] = useState<MerchantView[]>([]);
  const [loadingMerchants, setLoadingMerchants] = useState(false);
  const [merchantId, setMerchantId] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>("home");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([SecureStore.getItemAsync(KEY_URL), SecureStore.getItemAsync(KEY_TOKEN)])
      .then(([savedUrl, savedToken]) => {
        if (savedUrl) setBaseUrl(savedUrl);
        if (savedToken) setToken(savedToken);
      })
      .catch(() => {})
      .finally(() => setBooted(true));
  }, []);

  const client = useMemo(() => new ElepayClient(baseUrl, token), [baseUrl, token]);

  useEffect(() => {
    if (!token) {
      setMerchants([]);
      setMerchantId(null);
      setLoadingMerchants(false);
      return;
    }
    let cancelled = false;
    setLoadingMerchants(true);
    client
      .call<MerchantView[]>("/v1/merchants")
      .then((list) => {
        if (cancelled) return;
        setMerchants(list);
        setMerchantId((prev) => (prev && list.some((m) => m.id === prev) ? prev : (list[0]?.id ?? null)));
        setError(null);
      })
      .catch((e) => {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!cancelled) setLoadingMerchants(false);
      });
    return () => {
      cancelled = true;
    };
  }, [client, token]);

  async function handleSignedIn(url: string, newToken: string) {
    setBaseUrl(url);
    setToken(newToken);
    await SecureStore.setItemAsync(KEY_URL, url).catch(() => {});
    await SecureStore.setItemAsync(KEY_TOKEN, newToken).catch(() => {});
  }

  async function handleSignOut() {
    setToken(null);
    await SecureStore.deleteItemAsync(KEY_TOKEN).catch(() => {});
  }

  if (!booted) return null;

  if (!token) {
    return (
      <SafeAreaProvider>
        <SafeAreaView style={styles.root}>
          <StatusBar style="dark" />
          <SignInScreen initialBaseUrl={baseUrl} onSignedIn={(u, t) => void handleSignedIn(u, t)} />
        </SafeAreaView>
      </SafeAreaProvider>
    );
  }

  const merchant = merchants.find((m) => m.id === merchantId) ?? null;

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.root}>
        <StatusBar style="light" />
        <View style={styles.header}>
          <View style={{ flex: 1 }}>
            <Text style={styles.headerBrand}>ELEPAY MERCHANT</Text>
            <Text style={styles.headerName} numberOfLines={1}>
              {merchant ? merchant.tradingName : loadingMerchants ? "Loading merchant…" : "No merchant assigned"}
            </Text>
          </View>
          {merchants.length > 1 && merchant && (
            <Pressable
              style={styles.switchBtn}
              onPress={() => {
                const idx = merchants.findIndex((m) => m.id === merchant.id);
                setMerchantId(merchants[(idx + 1) % merchants.length].id);
              }}
            >
              <Text style={styles.switchBtnText}>Switch</Text>
            </Pressable>
          )}
          <Pressable style={styles.signOutBtn} onPress={() => void handleSignOut()}>
            <Text style={styles.signOutText}>Sign out</Text>
          </Pressable>
        </View>

        {error && (
          <View style={styles.errorBanner}>
            <Text style={styles.errorText}>{error}</Text>
          </View>
        )}

        <View style={{ flex: 1, backgroundColor: colors.bg }}>
          {!loadingMerchants && !merchant && !error && (
            <View style={styles.emptyState}>
              <Text style={styles.emptyTitle}>No Merchant Accounts Found</Text>
              <Text style={styles.emptySub}>
                This account does not have any merchant memberships assigned yet. Ask an administrator to invite you or sign in with another account.
              </Text>
            </View>
          )}
          {merchant && tab === "home" && <HomeScreen key={merchant.id} client={client} merchant={merchant} onNavigate={setTab} />}
          {merchant && tab === "sales" && <SalesScreen key={merchant.id} client={client} merchant={merchant} />}
          {merchant && tab === "collect" && <CollectScreen key={merchant.id} client={client} merchant={merchant} />}
          {merchant && tab === "withdraw" && <WithdrawScreen key={merchant.id} client={client} merchant={merchant} />}
        </View>

        <View style={styles.tabBar}>
          {(
            [
              ["home", "Home"],
              ["sales", "Sales"],
              ["collect", "Collect"],
              ["withdraw", "Withdraw"],
            ] as const
          ).map(([key, label]) => (
            <Pressable key={key} style={[styles.tabItem, tab === key && styles.tabItemActive]} onPress={() => setTab(key)}>
              <Text style={[styles.tabLabel, tab === key && styles.tabLabelActive]}>{label}</Text>
            </Pressable>
          ))}
        </View>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.greenDeep },
  header: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.greenDeep,
    paddingHorizontal: spacing.md,
    paddingVertical: 12,
    gap: 8,
  },
  headerBrand: { color: colors.gold, fontSize: 10, fontWeight: "800", letterSpacing: 1 },
  headerName: { color: "#fff", fontSize: 17, fontWeight: "800", marginTop: 1 },
  switchBtn: {
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: radius.xs,
    backgroundColor: "rgba(255,255,255,0.14)",
  },
  switchBtnText: { color: "#fff", fontSize: 12, fontWeight: "700" },
  signOutBtn: {
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: radius.xs,
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.22)",
  },
  signOutText: { color: "rgba(255,255,255,0.85)", fontSize: 12, fontWeight: "600" },
  errorBanner: { backgroundColor: colors.dangerBg, padding: 10 },
  errorText: { color: colors.danger, fontSize: 13, fontWeight: "600" },
  emptyState: {
    margin: spacing.lg,
    padding: spacing.lg,
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.line,
    alignItems: "center",
    gap: 8,
  },
  emptyTitle: { fontSize: 16, fontWeight: "800", color: colors.ink },
  emptySub: { fontSize: 13, color: colors.muted, textAlign: "center", lineHeight: 19 },
  tabBar: {
    flexDirection: "row",
    backgroundColor: colors.surface,
    borderTopWidth: 1,
    borderTopColor: colors.line,
    paddingHorizontal: 6,
    paddingVertical: 6,
  },
  tabItem: {
    flex: 1,
    alignItems: "center",
    paddingVertical: 10,
    borderRadius: radius.sm,
  },
  tabItemActive: { backgroundColor: colors.greenSoft },
  tabLabel: { fontSize: 13, fontWeight: "600", color: colors.muted },
  tabLabelActive: { color: colors.greenDeep, fontWeight: "800" },
});
