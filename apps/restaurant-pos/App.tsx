import React, { useEffect, useMemo, useState } from "react";
import { Platform, Pressable, SafeAreaView, StyleSheet, Text, View } from "react-native";
import * as SecureStore from "expo-secure-store";
import { StatusBar } from "expo-status-bar";
import { colors, ElepayClient, MerchantView, radius, spacing } from "@elepay/mobile-core";
import { SignInScreen } from "./src/screens/SignInScreen";
import { PosRegisterScreen } from "./src/screens/PosRegisterScreen";
import { FloorPlanScreen } from "./src/screens/FloorPlanScreen";

const KEY_URL = "elepay_pos_base_url";
const KEY_TOKEN = "elepay_pos_token";
const DEFAULT_URL = Platform.OS === "android" ? "http://10.0.2.2:8080" : "http://localhost:8080";

export default function App() {
  const [booted, setBooted] = useState(false);
  const [baseUrl, setBaseUrl] = useState(DEFAULT_URL);
  const [token, setToken] = useState<string | null>(null);
  const [merchants, setMerchants] = useState<MerchantView[]>([]);
  const [merchantId, setMerchantId] = useState<string | null>(null);
  const [tab, setTab] = useState<"register" | "floor">("register");
  const [preselectedTableId, setPreselectedTableId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([SecureStore.getItemAsync(KEY_URL), SecureStore.getItemAsync(KEY_TOKEN)])
      .then(([savedUrl, savedToken]) => {
        if (savedUrl) setBaseUrl(savedUrl);
        if (savedToken) setToken(savedToken);
      })
      .finally(() => setBooted(true));
  }, []);

  const client = useMemo(() => new ElepayClient(baseUrl, token), [baseUrl, token]);

  useEffect(() => {
    if (!token) {
      setMerchants([]);
      setMerchantId(null);
      return;
    }
    client
      .call<MerchantView[]>("/v1/merchants")
      .then((list) => {
        setMerchants(list);
        setMerchantId((prev) => (prev && list.some((m) => m.id === prev) ? prev : (list[0]?.id ?? null)));
        setError(null);
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, [client, token]);

  async function handleSignedIn(url: string, newToken: string) {
    setBaseUrl(url);
    setToken(newToken);
    await SecureStore.setItemAsync(KEY_URL, url);
    await SecureStore.setItemAsync(KEY_TOKEN, newToken);
  }

  async function handleSignOut() {
    setToken(null);
    await SecureStore.deleteItemAsync(KEY_TOKEN);
  }

  if (!booted) return null;

  if (!token) {
    return (
      <SafeAreaView style={styles.root}>
        <StatusBar style="dark" />
        <SignInScreen initialBaseUrl={baseUrl} onSignedIn={(u, t) => void handleSignedIn(u, t)} />
      </SafeAreaView>
    );
  }

  const merchant = merchants.find((m) => m.id === merchantId) ?? null;

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar style="light" />
      <View style={styles.header}>
        <View style={{ flex: 1 }}>
          <Text style={styles.headerBrand}>ELEPAY RESTAURANT POS</Text>
          <Text style={styles.headerName} numberOfLines={1}>
            {merchant ? merchant.tradingName : "Loading restaurant…"}
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

      <View style={{ flex: 1 }}>
        {merchant && tab === "register" && (
          <PosRegisterScreen
            client={client}
            merchant={merchant}
            preselectedTableId={preselectedTableId}
            onClearPreselectedTable={() => setPreselectedTableId(null)}
          />
        )}
        {merchant && tab === "floor" && (
          <FloorPlanScreen
            client={client}
            merchant={merchant}
            onStartTableOrder={(tid) => {
              setPreselectedTableId(tid);
              setTab("register");
            }}
          />
        )}
      </View>

      <View style={styles.tabBar}>
        <Pressable style={[styles.tabItem, tab === "register" && styles.tabItemActive]} onPress={() => setTab("register")}>
          <Text style={[styles.tabLabel, tab === "register" && styles.tabLabelActive]}>POS Register</Text>
        </Pressable>
        <Pressable style={[styles.tabItem, tab === "floor" && styles.tabItemActive]} onPress={() => setTab("floor")}>
          <Text style={[styles.tabLabel, tab === "floor" && styles.tabLabelActive]}>Floor Plan</Text>
        </Pressable>
      </View>
    </SafeAreaView>
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
  tabBar: {
    flexDirection: "row",
    backgroundColor: colors.surface,
    borderTopWidth: 1,
    borderTopColor: colors.line,
    paddingHorizontal: 8,
    paddingVertical: 6,
    gap: 6,
  },
  tabItem: { flex: 1, alignItems: "center", paddingVertical: 10, borderRadius: radius.sm },
  tabItemActive: { backgroundColor: colors.greenSoft },
  tabLabel: { fontSize: 14, fontWeight: "600", color: colors.muted },
  tabLabelActive: { color: colors.greenDeep, fontWeight: "800" },
});
