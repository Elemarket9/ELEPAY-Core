import React, { useEffect, useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
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
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    client
      .callAsKitchenDevice<KitchenStation[]>(device, `/v1/restaurant/kds/stations/merchant/${device.merchantId}`)
      .then(setStations)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, [client, device]);

  return (
    <ScrollView style={styles.root} contentContainerStyle={styles.content}>
      <View style={styles.card}>
        <Text style={styles.kicker}>STATION SETUP</Text>
        <Text style={styles.title}>{device.merchantName}</Text>
        <Text style={styles.subtitle}>Choose which kitchen station this tablet displays.</Text>

        {error && <Text style={styles.errorText}>{error}</Text>}

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
                hasPrinter: Boolean(s.printerAddress),
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
  errorText: { color: "#FCA5A5", fontSize: 13, marginBottom: spacing.md },
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
