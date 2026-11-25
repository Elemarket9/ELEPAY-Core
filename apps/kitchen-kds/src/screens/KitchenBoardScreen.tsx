import React, { useCallback, useEffect, useRef, useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, useWindowDimensions, View } from "react-native";
import AsyncStorage from "@react-native-async-storage/async-storage";
import { useKeepAwake } from "expo-keep-awake";
import {
  ApiError,
  colors,
  DeviceCredential,
  ElepayClient,
  emptyKdsState,
  kdsApplySync,
  kdsBackoff,
  kdsBump,
  KdsBumpResult,
  kdsCancel,
  kdsElapsed,
  kdsNextStatus,
  KdsQueuedAction,
  kdsSettle,
  KdsState,
  kdsStationStatus,
  KdsStatus,
  kdsStorageKey,
  KdsSyncResult,
  KdsTicketView,
  kdsUrgency,
  kdsVisible,
  newKey,
  radius,
  spacing,
} from "@elepay/mobile-core";
import type { KdsSetup } from "./StationSelectScreen";

const BASE = "/v1/restaurant/kds";

const NEXT_LABEL: Record<KdsStatus, string> = {
  NEW: "",
  PREPARING: "Start",
  READY: "Ready",
  SERVED: "Served",
  CANCELLED: "",
};

interface Props {
  client: ElepayClient;
  device: DeviceCredential;
  setup: KdsSetup;
  onChangeStation: () => void;
  onUnpair: () => void;
}

export function KitchenBoardScreen({ client, device, setup, onChangeStation, onUnpair }: Props) {
  useKeepAwake();
  const { width } = useWindowDimensions();
  const storageKey = kdsStorageKey(setup.merchantId, setup.stationId);

  const [state, setState] = useState<KdsState>(emptyKdsState);
  const stateRef = useRef<KdsState>(state);
  const [hydratedKey, setHydratedKey] = useState<string | null>(null);
  const kickRef = useRef<(() => void) | null>(null);
  const [online, setOnline] = useState(true);
  const [revoked, setRevoked] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [confirmCancel, setConfirmCancel] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // Hydrate cached offline KDS state BEFORE starting live sync
  useEffect(() => {
    let cancelled = false;
    const empty = emptyKdsState();
    stateRef.current = empty;
    setState(empty);
    setHydratedKey(null);

    AsyncStorage.getItem(storageKey)
      .then((raw) => {
        if (cancelled) return;
        if (raw) {
          const parsed = { ...emptyKdsState(), ...(JSON.parse(raw) as KdsState) };
          stateRef.current = parsed;
          setState(parsed);
        }
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setHydratedKey(storageKey);
      });

    return () => {
      cancelled = true;
    };
  }, [storageKey]);

  const update = useCallback(
    (fn: (s: KdsState) => KdsState) => {
      const next = fn(stateRef.current);
      stateRef.current = next;
      setState(next);
      void AsyncStorage.setItem(storageKey, JSON.stringify(next)).catch(() => {});
    },
    [storageKey],
  );

  // Live sync + offline action queue flush loop (starts only after hydration completes)
  useEffect(() => {
    if (hydratedKey !== storageKey) return;
    let stopped = false;
    let inFlight = false;
    let kickPending = false;
    let failures = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const sendAction = (a: KdsQueuedAction): Promise<KdsBumpResult> => {
      if (a.kind === "cancel") {
        return client.callAsKitchenDevice<KdsBumpResult>(device, `${BASE}/tickets/${a.ticketId}/cancel`, {
          body: { actionId: a.actionId, reason: a.reason },
        });
      }
      const path = a.stationId
        ? `${BASE}/tickets/${a.ticketId}/stations/${a.stationId}/advance`
        : `${BASE}/tickets/${a.ticketId}/advance`;
      return client.callAsKitchenDevice<KdsBumpResult>(device, path, {
        body: { from: a.from, actionId: a.actionId },
      });
    };

    const flush = async (): Promise<boolean> => {
      let triggeredResync = false;
      while (stateRef.current.queue.length > 0 && !stopped) {
        const action = stateRef.current.queue[0];
        try {
          const result = await sendAction(action);
          if (stopped) return triggeredResync;
          update((s) => kdsSettle(s, action.actionId, result));
          if (!result.applied && (result.reason === "STATUS_CHANGED" || result.reason === "NOTHING_TO_ADVANCE")) {
            setNotice(`Order ${ticketNum(result.view)} was already updated on another screen.`);
          }
        } catch (e) {
          if (stopped) return triggeredResync;
          if (e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 401 && e.status !== 429) {
            update((s) => kdsSettle(s, action.actionId, null));
            triggeredResync = true;
            setNotice(`Could not update order: ${e.message}`);
          } else {
            throw e;
          }
        }
      }
      return triggeredResync;
    };

    const syncFromServer = async () => {
      let more = true;
      while (more && !stopped) {
        const stationQ = setup.stationId ? `&stationId=${setup.stationId}` : "";
        const since = stateRef.current.cursor;
        const result = await client.callAsKitchenDevice<KdsSyncResult>(
          device,
          `${BASE}/sync?merchantId=${setup.merchantId}&since=${since}${stationQ}`,
        );
        if (stopped) return;
        client.learnServerTime(result.serverTime);
        update((s) => kdsApplySync(s, result).state);
        more = result.hasMore;
      }
    };

    const run = async () => {
      if (stopped) return;
      if (inFlight) {
        kickPending = true;
        return;
      }
      inFlight = true;
      if (timer) {
        clearTimeout(timer);
        timer = undefined;
      }
      try {
        await syncFromServer();
        if (stopped) return;
        failures = 0;
        setOnline(true);
        setRevoked(false);
        const needsResync = await flush();
        if (stopped) return;
        if (needsResync || stateRef.current.needsFullResync) {
          await syncFromServer();
          if (stopped) return;
        }
      } catch (e) {
        if (stopped) return;
        if (e instanceof ApiError && e.status === 401) setRevoked(true);
        failures++;
        setOnline(false);
      } finally {
        inFlight = false;
      }
      if (!stopped) {
        if (kickPending) {
          kickPending = false;
          void run();
        } else {
          timer = setTimeout(() => void run(), kdsBackoff(failures));
        }
      }
    };

    kickRef.current = () => {
      if (stopped) return;
      if (timer) {
        clearTimeout(timer);
        timer = undefined;
      }
      void run();
    };

    void run();
    return () => {
      stopped = true;
      kickRef.current = null;
      if (timer) clearTimeout(timer);
    };
  }, [client, device, hydratedKey, setup.merchantId, setup.stationId, storageKey, update]);

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    if (!notice) return;
    const t = setTimeout(() => setNotice(null), 6000);
    return () => clearTimeout(t);
  }, [notice]);

  const reprint = async (ticketId: string) => {
    setMenuFor(null);
    try {
      await client.callAsKitchenDevice(device, `${BASE}/tickets/${ticketId}/reprint?stationId=${setup.stationId}`, {
        method: "POST",
      });
      setNotice("Sent to kitchen printer.");
    } catch (e) {
      setNotice(`Could not reprint: ${e instanceof Error ? e.message : String(e)}`);
    }
  };

  const canReprint = Boolean(setup.hasPrinter || device.hasPrinter);
  const tickets = kdsVisible(state, setup.stationId);
  const columns = width >= 1024 ? 4 : width >= 720 ? 3 : width >= 480 ? 2 : 1;
  const cardWidth: number | "100%" =
    columns === 1 ? "100%" : Math.max(200, Math.floor((width - spacing.md * 2 - 12 * (columns - 1)) / columns));
  const clockStr = formatGhanaClock(now);

  return (
    <View style={styles.root}>
      {/* Sticky Top Telemetry Bar */}
      <View style={styles.topBar}>
        <View style={styles.topLeft}>
          <View style={styles.brandBadge}>
            <Text style={styles.brandMark}>E</Text>
          </View>
          <Text style={styles.stationTitle}>{setup.stationName}</Text>
          <Text style={styles.merchantSub}>{setup.merchantName}</Text>
        </View>

        <View style={styles.topRight}>
          <View style={styles.pill}>
            <Text style={[styles.pillText, { color: colors.gold }]}>{tickets.length} OPEN</Text>
          </View>
          <View style={[styles.pill, !online && styles.pillOffline]}>
            <Text style={[styles.pillText, { color: online ? "#86EFAC" : "#FCA5A5" }]}>
              {online ? "● ONLINE" : "● OFFLINE"}
              {state.queue.length > 0 ? ` · ${state.queue.length} QUEUED` : ""}
            </Text>
          </View>
          <Text style={styles.clockText}>{clockStr}</Text>
          <Pressable style={styles.barBtn} onPress={onChangeStation}>
            <Text style={styles.barBtnText}>Station</Text>
          </Pressable>
        </View>
      </View>

      {revoked && (
        <View style={styles.alertBar}>
          <Text style={styles.alertText}>This device was revoked in the dashboard.</Text>
          <Pressable onPress={onUnpair}>
            <Text style={[styles.alertText, { color: colors.gold, textDecorationLine: "underline" }]}>Pair again</Text>
          </Pressable>
        </View>
      )}

      {state.printerFailures.map((f) => (
        <View key={f.id} style={styles.alertBar}>
          <Text style={styles.alertText}>
            Printer problem at {f.stationName}: {f.lastError ?? "not printing"}. Check paper &amp; power.
          </Text>
        </View>
      ))}

      {notice && (
        <View style={styles.noticeBar}>
          <Text style={styles.noticeText}>{notice}</Text>
        </View>
      )}

      <ScrollView contentContainerStyle={styles.grid}>
        {tickets.length === 0 ? (
          <View style={styles.emptyWrap}>
            <Text style={styles.emptyText}>No open orders — kitchen is clear</Text>
          </View>
        ) : (
          tickets.map((v) => {
            const status = kdsStationStatus(v, setup.stationId);
            const next = kdsNextStatus(status);
            const urg = kdsUrgency(v, now);
            const mine = setup.stationId
              ? v.items.filter((i) => i.stationId === setup.stationId || i.stationId === null)
              : v.items;

            const topColor =
              status === "PREPARING" ? colors.kdsPreparing : status === "READY" ? colors.kdsReady : colors.kdsNew;

            return (
              <View
                key={v.ticket.id}
                style={[
                  styles.ticketCard,
                  { width: cardWidth, borderTopColor: topColor },
                  urg === "warning" && styles.ticketWarn,
                  urg === "critical" && styles.ticketCritical,
                ]}
              >
                <View style={styles.ticketHeader}>
                  <Text style={styles.ticketNum}>{ticketNum(v)}</Text>
                  <View style={styles.modeBadge}>
                    <Text style={styles.modeText}>{v.ticket.diningMode.replace("_", " ")}</Text>
                  </View>
                  <Text
                    style={[
                      styles.timerText,
                      urg === "warning" && { color: "#FCD34D" },
                      urg === "critical" && { color: "#FCA5A5" },
                    ]}
                  >
                    {kdsElapsed(v, now)}
                  </Text>
                </View>

                {v.ticket.serverName && <Text style={styles.serverText}>Server: {v.ticket.serverName}</Text>}

                <View style={styles.itemList}>
                  {mine
                    .filter((i) => i.itemStatus !== "CANCELLED")
                    .map((i) => {
                      const done = i.itemStatus === "READY" || i.itemStatus === "SERVED";
                      return (
                        <View key={i.id} style={styles.itemRow}>
                          <Text style={[styles.itemText, done && styles.itemDone]}>
                            <Text style={{ fontWeight: "800" }}>{formatQty(i.quantity)}× </Text>
                            {i.itemName}
                          </Text>
                          {i.modifiers && i.modifiers !== "[]" && (
                            <Text style={styles.modText}>+ {i.modifiers}</Text>
                          )}
                        </View>
                      );
                    })}
                </View>

                {v.ticket.notes && (
                  <View style={styles.noteBox}>
                    <Text style={styles.noteText}>{v.ticket.notes}</Text>
                  </View>
                )}

                <View style={styles.footerRow}>
                  {next && !(setup.stationId && next === "SERVED") && (
                    <Pressable
                      style={[
                        styles.bumpBtn,
                        next === "PREPARING" && { backgroundColor: colors.gold },
                        next === "READY" && { backgroundColor: "#16A34A" },
                        next === "SERVED" && { backgroundColor: "#0284C7" },
                      ]}
                      onPress={() => {
                        update((s) => kdsBump(s, v.ticket.id, setup.stationId, newKey()));
                        kickRef.current?.();
                      }}
                    >
                      <Text style={[styles.bumpBtnText, next === "PREPARING" && { color: colors.ink }]}>
                        {NEXT_LABEL[next]}
                      </Text>
                    </Pressable>
                  )}
                  <Pressable
                    style={styles.moreBtn}
                    onPress={() => {
                      setMenuFor(menuFor === v.ticket.id ? null : v.ticket.id);
                      setConfirmCancel(null);
                    }}
                  >
                    <Text style={styles.moreBtnText}>⋯</Text>
                  </Pressable>
                </View>

                {menuFor === v.ticket.id && (
                  <View style={styles.menuRow}>
                    {canReprint && (
                      <Pressable style={styles.menuBtn} onPress={() => void reprint(v.ticket.id)}>
                        <Text style={styles.menuBtnText}>Reprint</Text>
                      </Pressable>
                    )}
                    {confirmCancel === v.ticket.id ? (
                      <Pressable
                        style={[styles.menuBtn, { backgroundColor: "#B91C1C", borderColor: "#B91C1C" }]}
                        onPress={() => {
                          update((s) => kdsCancel(s, v.ticket.id, null, newKey()));
                          setMenuFor(null);
                          kickRef.current?.();
                        }}
                      >
                        <Text style={styles.menuBtnText}>Tap again to cancel</Text>
                      </Pressable>
                    ) : (
                      <Pressable style={styles.menuBtn} onPress={() => setConfirmCancel(v.ticket.id)}>
                        <Text style={styles.menuBtnText}>Cancel order</Text>
                      </Pressable>
                    )}
                  </View>
                )}
              </View>
            );
          })
        )}
      </ScrollView>
    </View>
  );
}

function formatGhanaClock(ts: number): string {
  try {
    return new Date(ts).toLocaleTimeString("en-GH", {
      hour: "2-digit",
      minute: "2-digit",
      timeZone: "Africa/Accra",
    });
  } catch {
    const d = new Date(ts);
    return `${String(d.getUTCHours()).padStart(2, "0")}:${String(d.getUTCMinutes()).padStart(2, "0")}`;
  }
}

function ticketNum(v: KdsTicketView): string {
  return v.ticket.ticketNumber != null ? `#${String(v.ticket.ticketNumber).padStart(3, "0")}` : "#—";
}

function formatQty(q: number): string {
  return Number.isInteger(q) ? String(q) : String(Number(q.toFixed(3)));
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.kdsBg },
  topBar: {
    flexDirection: "row",
    flexWrap: "wrap",
    justifyContent: "space-between",
    alignItems: "center",
    backgroundColor: colors.greenDeep,
    paddingHorizontal: spacing.md,
    paddingVertical: 10,
    gap: 10,
  },
  topLeft: { flexDirection: "row", alignItems: "center", gap: 10 },
  brandBadge: {
    width: 28,
    height: 28,
    borderRadius: 7,
    backgroundColor: colors.green,
    alignItems: "center",
    justifyContent: "center",
  },
  brandMark: { color: colors.gold, fontSize: 16, fontWeight: "800" },
  stationTitle: { color: "#fff", fontSize: 18, fontWeight: "800" },
  merchantSub: { color: "#B7D7C8", fontSize: 14 },
  topRight: { flexDirection: "row", alignItems: "center", gap: 8, flexWrap: "wrap" },
  pill: {
    backgroundColor: "rgba(0,0,0,0.28)",
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: radius.pill,
  },
  pillOffline: { backgroundColor: "rgba(180,35,24,0.45)" },
  pillText: { fontSize: 12, fontWeight: "800" },
  clockText: { color: "#fff", fontSize: 16, fontWeight: "800", marginHorizontal: 4 },
  barBtn: {
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.28)",
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: radius.xs,
  },
  barBtnText: { color: "#fff", fontSize: 12, fontWeight: "700" },
  alertBar: {
    backgroundColor: "#991B1B",
    paddingHorizontal: spacing.md,
    paddingVertical: 10,
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
  },
  alertText: { color: "#fff", fontSize: 14, fontWeight: "700" },
  noticeBar: { backgroundColor: colors.gold, paddingHorizontal: spacing.md, paddingVertical: 8 },
  noticeText: { color: colors.ink, fontSize: 14, fontWeight: "800" },
  grid: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 12,
    padding: spacing.md,
    flexGrow: 1,
  },
  emptyWrap: { flex: 1, width: "100%", alignItems: "center", justifyContent: "center", paddingTop: 120 },
  emptyText: { color: "#6B7D75", fontSize: 22, fontWeight: "700" },
  ticketCard: {
    backgroundColor: colors.kdsSurface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.kdsBorder,
    borderTopWidth: 6,
    padding: 12,
    gap: 8,
  },
  ticketWarn: { borderColor: colors.kdsWarn, borderWidth: 2 },
  ticketCritical: { borderColor: colors.kdsCritical, borderWidth: 3 },
  ticketHeader: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    paddingBottom: 8,
    borderBottomWidth: 1,
    borderBottomColor: "#26332E",
  },
  ticketNum: { color: "#fff", fontSize: 24, fontWeight: "800" },
  modeBadge: { backgroundColor: colors.kdsSurfaceAlt, paddingHorizontal: 7, paddingVertical: 3, borderRadius: 5 },
  modeText: { color: "#B7C8C0", fontSize: 11, fontWeight: "800", textTransform: "uppercase" },
  timerText: { marginLeft: "auto", color: "#CBD6D1", fontSize: 18, fontWeight: "800" },
  serverText: { color: colors.kdsMuted, fontSize: 13 },
  itemList: { gap: 4 },
  itemRow: { paddingVertical: 5, borderBottomWidth: 1, borderBottomColor: "#23302B" },
  itemText: { color: "#F3F7F5", fontSize: 18, lineHeight: 24 },
  itemDone: { textDecorationLine: "line-through", color: "#6B7D75" },
  modText: { color: colors.gold, fontSize: 14, fontWeight: "700", paddingLeft: 16, marginTop: 2 },
  noteBox: {
    backgroundColor: "#362B10",
    borderLeftWidth: 3,
    borderLeftColor: colors.gold,
    padding: 8,
    borderRadius: 6,
  },
  noteText: { color: "#FDE68A", fontSize: 14, fontWeight: "700" },
  footerRow: { flexDirection: "row", gap: 8, marginTop: 4 },
  bumpBtn: { flex: 1, paddingVertical: 14, borderRadius: radius.sm, alignItems: "center" },
  bumpBtnText: { color: "#fff", fontSize: 18, fontWeight: "800" },
  moreBtn: {
    width: 48,
    backgroundColor: colors.kdsSurfaceAlt,
    borderWidth: 1,
    borderColor: "#374841",
    borderRadius: radius.sm,
    alignItems: "center",
    justifyContent: "center",
  },
  moreBtnText: { color: "#fff", fontSize: 20, fontWeight: "800" },
  menuRow: { flexDirection: "row", gap: 8, marginTop: 4 },
  menuBtn: {
    flex: 1,
    backgroundColor: colors.kdsSurfaceAlt,
    borderWidth: 1,
    borderColor: "#374841",
    paddingVertical: 10,
    borderRadius: radius.xs,
    alignItems: "center",
  },
  menuBtnText: { color: "#fff", fontSize: 13, fontWeight: "700" },
});
