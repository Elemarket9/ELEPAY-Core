import React, { useCallback, useEffect, useRef, useState } from "react";
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { colors, DeviceCredential, ElepayClient, KitchenStation, radius, spacing } from "@elepay/mobile-core";

export interface KdsSetup {
  merchantId: string;
  merchantName: string;
  stationId: string | null;
  stationName: string;
  hasPrinter: boolean;
}

interface Props {
  client: ElepayClient;
  device: DeviceCredential;
  onSelect: (setup: KdsSetup) => void;
  onUnpair: () => void;
}

export function StationSelectScreen({ client, device, onSelect, onUnpair }: Props) {
  const [stations, setStations] = useState<KitchenStation[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const loadStations = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await client.callAsKitchenDevice<KitchenStation[]>(
        device,
        `/v1/restaurant/kds/stations/merchant/${device.merchantId}`,
      );
      if (!mountedRef.current) return;
      setStations(list);
    } catch (e) {
      if (!mountedRef.current) return;
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (mountedRef.current) {
        setLoading(false);
      }
    }
  }, [client, device]);

  useEffect(() => {
    void loadStations();
  }, [loadStations]);

  return (
    <ScrollView style={styles.root} contentContainerStyle={styles.content}>
      <View style={styles.card}>
        <Text style={styles.kicker}>STATION SETUP</Text>
        <Text style={styles.title}>{device.merchantName}</Text>
        <Text style={styles.subtitle}>Choose which kitchen station this tablet displays.</Text>

        {loading && (
          <View style={styles.loadingRow}>
            <ActivityIndicator color={colors.gold} />
            <Text style={styles.loadingText}>Loading kitchen stations…</Text>
          </View>
        )}

        {error && (
          <View style={styles.errorBox}>
            <Text style={styles.errorText}>{error}</Text>
            <Pressable style={styles.retryBtn} onPress={() => void loadStations()}>
              <Text style={styles.retryBtnText}>Retry</Text>
            </Pressable>
          </View>
        )}

        <Pressable
          style={styles.primaryBtn}
          onPress={() =>
            onSelect({
              merchantId: device.merchantId,
              merchantName: device.merchantName,
              stationId: null,
              stationName: "All orders (Expo / Pass)",
              hasPrinter: false,
            })
          }
        >
          <Text style={styles.primaryBtnText}>All orders (Expo / Pass)</Text>
        </Pressable>

        {stations.map((s) => (
          <Pressable
            key={s.id}
            style={styles.stationBtn}
            onPress={() =>
              onSelect({
                merchantId: device.merchantId,
                merchantName: device.merchantName,
                stationId: s.id,
                stationName: s.name,
                hasPrinter: Boolean(s.printerAddress || s.output === "PRINTER" || s.output === "BOTH"),
              })
            }
          >
            <Text style={styles.stationBtnText}>{s.name}</Text>
            <Text style={styles.stationMeta}>{s.output}</Text>
          </Pressable>
        ))}

        <Pressable style={styles.unpairBtn} onPress={onUnpair}>
          <Text style={styles.unpairText}>Unpair this device</Text>
        </Pressable>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.kdsBg },
  content: { flexGrow: 1, justifyContent: "center", alignItems: "center", padding: spacing.lg },
  card: {
    width: "100%",
    maxWidth: 540,
    backgroundColor: colors.kdsSurface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.kdsBorder,
    padding: spacing.xl,
  },
  kicker: { color: colors.gold, fontSize: 11, fontWeight: "800", letterSpacing: 1 },
  title: { color: "#fff", fontSize: 24, fontWeight: "800", marginTop: 4 },
  subtitle: { color: colors.kdsMuted, fontSize: 14, marginTop: 4, marginBottom: spacing.lg },
  loadingRow: { flexDirection: "row", alignItems: "center", gap: 10, marginBottom: spacing.md },
  loadingText: { color: colors.kdsMuted, fontSize: 13, fontWeight: "600" },
  errorBox: {
    backgroundColor: "rgba(185,28,28,0.2)",
    borderWidth: 1,
    borderColor: "rgba(252,165,165,0.4)",
    borderRadius: radius.sm,
    padding: 12,
    marginBottom: spacing.md,
    gap: 8,
  },
  errorText: { color: "#FCA5A5", fontSize: 13 },
  retryBtn: {
    alignSelf: "flex-start",
    backgroundColor: colors.kdsSurfaceAlt,
    borderWidth: 1,
    borderColor: colors.gold,
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: radius.xs,
  },
  retryBtnText: { color: colors.gold, fontSize: 12, fontWeight: "700" },
  primaryBtn: {
    backgroundColor: colors.green,
    paddingVertical: 16,
    paddingHorizontal: 18,
    borderRadius: radius.md,
    marginBottom: 10,
  },
  primaryBtnText: { color: "#fff", fontSize: 17, fontWeight: "800", textAlign: "center" },
  stationBtn: {
    backgroundColor: colors.kdsSurfaceAlt,
    borderWidth: 1,
    borderColor: "#374841",
    paddingVertical: 15,
    paddingHorizontal: 18,
    borderRadius: radius.md,
    marginBottom: 10,
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
  },
  stationBtnText: { color: "#fff", fontSize: 16, fontWeight: "700" },
  stationMeta: { color: colors.kdsMuted, fontSize: 12, fontWeight: "600" },
  unpairBtn: { marginTop: spacing.md, alignItems: "center", padding: 8 },
  unpairText: { color: colors.gold, fontSize: 13, fontWeight: "700" },
});
