import React, { useEffect, useMemo, useState } from "react";
import { Platform, StyleSheet } from "react-native";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import AsyncStorage from "@react-native-async-storage/async-storage";
import * as SecureStore from "expo-secure-store";
import { StatusBar } from "expo-status-bar";
import { colors, DeviceCredential, ElepayClient } from "@elepay/mobile-core";
import { PairDeviceScreen } from "./src/screens/PairDeviceScreen";
import { KdsSetup, StationSelectScreen } from "./src/screens/StationSelectScreen";
import { KitchenBoardScreen } from "./src/screens/KitchenBoardScreen";

const KEY_URL = "elepay_kds_base_url";
const KEY_DEVICE = "elepay_kds_device";
const KEY_SETUP = "elepay_kds_setup";
const DEFAULT_URL = Platform.OS === "android" ? "http://10.0.2.2:8080" : "http://localhost:8080";

export default function App() {
  const [booted, setBooted] = useState(false);
  const [baseUrl, setBaseUrl] = useState(DEFAULT_URL);
  const [device, setDevice] = useState<DeviceCredential | null>(null);
  const [setup, setSetup] = useState<KdsSetup | null>(null);

  useEffect(() => {
    Promise.all([
      SecureStore.getItemAsync(KEY_URL),
      SecureStore.getItemAsync(KEY_DEVICE),
      AsyncStorage.getItem(KEY_SETUP),
    ])
      .then(([savedUrl, savedDevice, savedSetup]) => {
        if (savedUrl) setBaseUrl(savedUrl);
        if (savedDevice) {
          const parsedDevice = JSON.parse(savedDevice) as DeviceCredential;
          setDevice(parsedDevice);
          if (savedSetup) {
            const parsedSetup = JSON.parse(savedSetup) as KdsSetup;
            if (parsedSetup.merchantId === parsedDevice.merchantId) {
              setSetup(parsedSetup);
            }
          } else if (parsedDevice.stationId && parsedDevice.stationName) {
            setSetup({
              merchantId: parsedDevice.merchantId,
              merchantName: parsedDevice.merchantName,
              stationId: parsedDevice.stationId,
              stationName: parsedDevice.stationName,
              hasPrinter: Boolean(parsedDevice.hasPrinter),
            });
          }
        }
      })
      .catch(() => {})
      .finally(() => setBooted(true));
  }, []);

  const client = useMemo(() => new ElepayClient(baseUrl), [baseUrl]);

  async function handlePaired(url: string, d: DeviceCredential) {
    setBaseUrl(url);
    setDevice(d);
    await SecureStore.setItemAsync(KEY_URL, url).catch(() => {});
    await SecureStore.setItemAsync(KEY_DEVICE, JSON.stringify(d)).catch(() => {});
    if (d.stationId && d.stationName) {
      const s: KdsSetup = {
        merchantId: d.merchantId,
        merchantName: d.merchantName,
        stationId: d.stationId,
        stationName: d.stationName,
        hasPrinter: Boolean(d.hasPrinter),
      };
      setSetup(s);
      await AsyncStorage.setItem(KEY_SETUP, JSON.stringify(s)).catch(() => {});
    } else {
      setSetup(null);
      await AsyncStorage.removeItem(KEY_SETUP).catch(() => {});
    }
  }

  async function handleSelectStation(s: KdsSetup) {
    setSetup(s);
    await AsyncStorage.setItem(KEY_SETUP, JSON.stringify(s)).catch(() => {});
  }

  async function handleUnpair() {
    setDevice(null);
    setSetup(null);
    await SecureStore.deleteItemAsync(KEY_DEVICE).catch(() => {});
    await AsyncStorage.removeItem(KEY_SETUP).catch(() => {});
  }

  if (!booted) return null;

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.root}>
        <StatusBar style="light" />
        {!device ? (
          <PairDeviceScreen initialBaseUrl={baseUrl} onPaired={(u, d) => void handlePaired(u, d)} />
        ) : !setup ? (
          <StationSelectScreen
            client={client}
            device={device}
            onSelect={(s) => void handleSelectStation(s)}
            onUnpair={() => void handleUnpair()}
          />
        ) : (
          <KitchenBoardScreen
            client={client}
            device={device}
            setup={setup}
            onChangeStation={() => {
              setSetup(null);
              void AsyncStorage.removeItem(KEY_SETUP).catch(() => {});
            }}
            onUnpair={() => void handleUnpair()}
          />
        )}
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.kdsBg },
});
