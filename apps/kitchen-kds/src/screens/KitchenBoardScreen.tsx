import React, { useCallback, useEffect, useRef, useState } from "react";
import { Modal, Pressable, ScrollView, StyleSheet, Text, TextInput, useWindowDimensions, View } from "react-native";
import AsyncStorage from "@react-native-async-storage/async-storage";
import { useKeepAwake } from "expo-keep-awake";
import {
  ApiError,
  colors,
  DeviceCredential,
  ElepayClient,
  emptyKdsState,
  KDS_CANCEL_REASONS,
  KDS_LANES,
  kdsApplySync,
  kdsBackoff,
  kdsBump,
  KdsBumpResult,
  kdsCancel,
  kdsElapsed,
  kdsGroupByCourse,
  kdsGroupByLane,
  kdsHasAutoReadyAtStation,
  KdsLane,
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

const LANE_COLOR: Record<KdsLane, string> = {
  NEW: colors.kdsNew,
  COOKING: colors.kdsPreparing,
  READY: colors.kdsReady,
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
  const viewModeKey = `${storageKey}.viewMode`;

  const [state, setState] = useState<KdsState>(emptyKdsState);
  const stateRef = useRef<KdsState>(state);
  const [hydratedKey, setHydratedKey] = useState<string | null>(null);
  const kickRef = useRef<(() => void) | null>(null);
  const [online, setOnline] = useState(true);
  const [revoked, setRevoked] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [viewMode, setViewMode] = useState<"grid" | "lanes">("grid");
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [cancelTarget, setCancelTarget] = useState<KdsTicketView | null>(null);
  const [cancelPreset, setCancelPreset] = useState<string | null>(null);
  const [cancelNote, setCancelNote] = useState("");
  const [notice, setNotice] = useState<string | null>(null);

  // Hydrate cached offline KDS state BEFORE starting live sync
  useEffect(() => {
    let cancelled = false;
    const empty = emptyKdsState();
    stateRef.current = empty;
    setState(empty);
    setHydratedKey(null);

    Promise.all([AsyncStorage.getItem(storageKey), AsyncStorage.getItem(viewModeKey)])
      .then(([raw, savedViewMode]) => {
        if (cancelled) return;
        if (raw) {
          const parsed = { ...emptyKdsState(), ...(JSON.parse(raw) as KdsState) };
          stateRef.current = parsed;
          setState(parsed);
        }
        if (savedViewMode === "grid" || savedViewMode === "lanes") {
          setViewMode(savedViewMode);
        }
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setHydratedKey(storageKey);
      });

    return () => {
      cancelled = true;
    };
  }, [storageKey, viewModeKey]);

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
        if (e instanceof ApiError && e.status === 401) {
          if (e.code === "request_expired") {
            // Clock skew: the next unsigned sync re-learns server time, then signed calls retry.
            setNotice("Device clock is out of sync with the server — re-syncing…");
          } else if (e.code === "invalid_signature" || e.code === "signing_not_configured" || e.code === "signature_version_required") {
            setNotice(`Signing problem (${e.code}). Re-pair this tablet if it persists.`);
          } else {
            setRevoked(true);
          }
        }
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

  const setAndPersistViewMode = (mode: "grid" | "lanes") => {
    setViewMode(mode);
    void AsyncStorage.setItem(viewModeKey, mode).catch(() => {});
  };

  const openCancelModal = (v: KdsTicketView) => {
    setCancelTarget(v);
    setCancelPreset(null);
    setCancelNote("");
  };

  const closeCancelModal = () => {
    setCancelTarget(null);
    setCancelPreset(null);
    setCancelNote("");
  };

  const resolvedCancelReason = (() => {
    const trimmed = cancelNote.trim();
    if (cancelPreset === "Other") return trimmed;
    if (cancelPreset && trimmed) return `${cancelPreset}: ${trimmed}`;
    return (cancelPreset ?? trimmed).trim();
  })();

  const confirmCancelWithReason = () => {
    if (!cancelTarget || !resolvedCancelReason) return;
    const targetId = cancelTarget.ticket.id;
    update((s) => kdsCancel(s, targetId, resolvedCancelReason, newKey()));
    setMenuFor(null);
    closeCancelModal();
    kickRef.current?.();
  };

  // Reprint is station-scoped server-side (stationId is a required UUID); hide it on "All stations".
  const canReprint = Boolean(setup.stationId) && Boolean(setup.hasPrinter || device.hasPrinter);
  const tickets = kdsVisible(state, setup.stationId);
  const lanes = kdsGroupByLane(tickets, setup.stationId);
  const columns = width >= 1024 ? 4 : width >= 720 ? 3 : width >= 480 ? 2 : 1;
  const cardWidth: number | "100%" =
    columns === 1 ? "100%" : Math.max(200, Math.floor((width - spacing.md * 2 - 12 * (columns - 1)) / columns));
  const wideLanes = width >= 720;
  const clockStr = formatGhanaClock(now);

  const renderTicketCard = (v: KdsTicketView, cardLayoutWidth: number | "100%") => {
    const status = kdsStationStatus(v, setup.stationId);
    const next = kdsNextStatus(status);
    const urg = kdsUrgency(v, now, state.urgencyThresholds);
    const mine = setup.stationId
      ? v.items.filter((i) => i.stationId === setup.stationId || i.stationId === null)
      : v.items;
    const courseGroups = kdsGroupByCourse(mine);
    const isAddOn = (v.ticket.round ?? 1) >= 2;
    const tableLabel = v.ticket.tableNumber?.trim() ? `TABLE ${v.ticket.tableNumber.trim()}` : null;
    const canBump =
      Boolean(next) && (!(setup.stationId && next === "SERVED") || kdsHasAutoReadyAtStation(v, setup.stationId));

    const topColor =
      status === "PREPARING" ? colors.kdsPreparing : status === "READY" ? colors.kdsReady : colors.kdsNew;

    return (
      <View
        key={v.ticket.id}
        style={[
          styles.ticketCard,
          { width: cardLayoutWidth, borderTopColor: topColor },
          urg === "warning" && styles.ticketWarn,
          urg === "critical" && styles.ticketCritical,
        ]}
      >
        {isAddOn && (
          <View style={styles.addOnBanner}>
            <Text style={styles.addOnText}>ADD-ON - ROUND {v.ticket.round}</Text>
          </View>
        )}

        {tableLabel && (
          <View style={styles.tableHeaderRow}>
            <Text style={styles.tableHeaderText}>{tableLabel}</Text>
          </View>
        )}

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
          {courseGroups.map((group) => (
            <View key={group.course ?? "__ALL__"} style={styles.courseGroup}>
              {group.course && <Text style={styles.courseHeader}>[{group.course}]</Text>}
              {group.items.map((i) => {
                const isAutoReadyWaiting = Boolean(i.autoReady) && i.itemStatus === "READY";
                const done = (i.itemStatus === "READY" && !isAutoReadyWaiting) || i.itemStatus === "SERVED";
                return (
                  <View key={i.id} style={styles.itemRow}>
                    <View style={styles.itemMainRow}>
                      <Text style={[styles.itemText, done && styles.itemDone]}>
                        <Text style={{ fontWeight: "800" }}>{formatQty(i.quantity)}× </Text>
                        {i.itemName}
                      </Text>
                      {isAutoReadyWaiting && (
                        <View style={styles.autoReadyBadge}>
                          <Text style={styles.autoReadyText}>AUTO-READY</Text>
                        </View>
                      )}
                    </View>
                    {i.modifiers && i.modifiers !== "[]" && (
                      <Text style={styles.modText}>+ {i.modifiers}</Text>
                    )}
                  </View>
                );
              })}
            </View>
          ))}
        </View>

        {v.ticket.notes && (
          <View style={styles.noteBox}>
            <Text style={styles.noteText}>{v.ticket.notes}</Text>
          </View>
        )}

        <View style={styles.footerRow}>
          {canBump && next && (
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
            <Pressable
              style={[styles.menuBtn, styles.menuBtnDanger]}
              onPress={() => openCancelModal(v)}
            >
              <Text style={styles.menuBtnText}>Cancel order</Text>
            </Pressable>
          </View>
        )}
      </View>
    );
  };

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
          <View style={styles.viewToggle}>
            <Pressable
              style={[styles.viewToggleBtn, viewMode === "grid" && styles.viewToggleBtnActive]}
              onPress={() => setAndPersistViewMode("grid")}
            >
              <Text style={[styles.viewToggleText, viewMode === "grid" && styles.viewToggleTextActive]}>Grid</Text>
            </Pressable>
            <Pressable
              style={[styles.viewToggleBtn, viewMode === "lanes" && styles.viewToggleBtnActive]}
              onPress={() => setAndPersistViewMode("lanes")}
            >
              <Text style={[styles.viewToggleText, viewMode === "lanes" && styles.viewToggleTextActive]}>Lanes</Text>
            </Pressable>
          </View>
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

      {tickets.length === 0 ? (
        <View style={styles.emptyWrap}>
          <Text style={styles.emptyText}>No open orders — kitchen is clear</Text>
        </View>
      ) : viewMode === "lanes" ? (
        <ScrollView
          horizontal={!wideLanes}
          contentContainerStyle={[styles.lanesWrap, wideLanes && styles.lanesWrapWide]}
        >
          {KDS_LANES.map((lane) => {
            const laneTickets = lanes[lane];
            return (
              <View key={lane} style={[styles.laneCol, !wideLanes && styles.laneColNarrow]}>
                <View style={[styles.laneHeader, { borderTopColor: LANE_COLOR[lane] }]}>
                  <Text style={styles.laneTitle}>{lane}</Text>
                  <View style={styles.laneCountBadge}>
                    <Text style={styles.laneCountText}>{laneTickets.length}</Text>
                  </View>
                </View>
                <ScrollView contentContainerStyle={styles.laneList}>
                  {laneTickets.length === 0 ? (
                    <Text style={styles.laneEmpty}>No {lane.toLowerCase()} orders</Text>
                  ) : (
                    laneTickets.map((v) => renderTicketCard(v, "100%"))
                  )}
                </ScrollView>
              </View>
            );
          })}
        </ScrollView>
      ) : (
        <ScrollView contentContainerStyle={styles.grid}>
          {tickets.map((v) => renderTicketCard(v, cardWidth))}
        </ScrollView>
      )}

      {/* Cancel Reason Modal (works offline via queued cancel action) */}
      <Modal
        visible={cancelTarget !== null}
        transparent
        animationType="fade"
        onRequestClose={closeCancelModal}
      >
        <View style={styles.modalBackdrop}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>
              Cancel {cancelTarget ? ticketNum(cancelTarget) : ""}
              {cancelTarget?.ticket.tableNumber ? ` · TABLE ${cancelTarget.ticket.tableNumber.trim()}` : ""}
            </Text>
            <Text style={styles.modalSub}>Select a reason or enter details before cancelling.</Text>

            <View style={styles.reasonChips}>
              {KDS_CANCEL_REASONS.map((r) => {
                const selected = cancelPreset === r;
                return (
                  <Pressable
                    key={r}
                    style={[styles.reasonChip, selected && styles.reasonChipSelected]}
                    onPress={() => setCancelPreset(selected ? null : r)}
                  >
                    <Text style={[styles.reasonChipText, selected && styles.reasonChipTextSelected]}>{r}</Text>
                  </Pressable>
                );
              })}
            </View>

            <TextInput
              style={styles.reasonInput}
              placeholder={cancelPreset === "Other" ? "Enter cancel reason (required)" : "Optional extra details..."}
              placeholderTextColor="#6B7D75"
              value={cancelNote}
              onChangeText={setCancelNote}
              maxLength={200}
            />

            <View style={styles.modalActions}>
              <Pressable style={styles.modalBackBtn} onPress={closeCancelModal}>
                <Text style={styles.modalBackText}>Keep order</Text>
              </Pressable>
              <Pressable
                style={[styles.modalConfirmBtn, !resolvedCancelReason && styles.modalConfirmDisabled]}
                disabled={!resolvedCancelReason}
                onPress={confirmCancelWithReason}
              >
                <Text style={styles.modalConfirmText}>Confirm cancel</Text>
              </Pressable>
            </View>
          </View>
        </View>
      </Modal>
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
  menuBtnDanger: {
    backgroundColor: "#7F1D1D",
    borderColor: "#B91C1C",
  },
  menuBtnText: { color: "#fff", fontSize: 13, fontWeight: "700" },
  viewToggle: {
    flexDirection: "row",
    backgroundColor: "rgba(0,0,0,0.28)",
    borderRadius: radius.pill,
    padding: 2,
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.16)",
  },
  viewToggleBtn: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: radius.pill,
  },
  viewToggleBtnActive: {
    backgroundColor: colors.gold,
  },
  viewToggleText: { color: "#B7D7C8", fontSize: 12, fontWeight: "800" },
  viewToggleTextActive: { color: colors.ink },
  addOnBanner: {
    backgroundColor: "#7C2D12",
    borderWidth: 1,
    borderColor: "#FB923C",
    borderRadius: radius.xs,
    paddingHorizontal: 8,
    paddingVertical: 4,
    alignItems: "center",
  },
  addOnText: {
    color: "#FFEDD5",
    fontSize: 12,
    fontWeight: "900",
    letterSpacing: 0.8,
  },
  tableHeaderRow: {
    backgroundColor: "#0F241C",
    borderRadius: radius.xs,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderLeftWidth: 4,
    borderLeftColor: colors.gold,
  },
  tableHeaderText: {
    color: colors.gold,
    fontSize: 26,
    fontWeight: "900",
    letterSpacing: 1,
  },
  courseGroup: { gap: 2, marginTop: 4 },
  courseHeader: {
    color: "#93C5FD",
    fontSize: 12,
    fontWeight: "800",
    letterSpacing: 0.7,
    textTransform: "uppercase",
    paddingTop: 4,
    paddingBottom: 2,
  },
  itemMainRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 8,
  },
  autoReadyBadge: {
    backgroundColor: "#0C4A6E",
    borderWidth: 1,
    borderColor: "#38BDF8",
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  autoReadyText: {
    color: "#BAE6FD",
    fontSize: 10,
    fontWeight: "800",
    letterSpacing: 0.5,
  },
  lanesWrap: {
    flexDirection: "row",
    gap: 12,
    padding: spacing.md,
    flexGrow: 1,
  },
  lanesWrapWide: {
    width: "100%",
  },
  laneCol: {
    flex: 1,
    minWidth: 220,
    backgroundColor: "rgba(255,255,255,0.03)",
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.kdsBorder,
    overflow: "hidden",
  },
  laneColNarrow: {
    width: 300,
    flex: 0,
  },
  laneHeader: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: 12,
    paddingVertical: 10,
    backgroundColor: colors.kdsSurface,
    borderTopWidth: 4,
    borderBottomWidth: 1,
    borderBottomColor: colors.kdsBorder,
  },
  laneTitle: { color: "#fff", fontSize: 15, fontWeight: "900", letterSpacing: 0.8 },
  laneCountBadge: {
    backgroundColor: colors.kdsSurfaceAlt,
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: radius.pill,
  },
  laneCountText: { color: colors.gold, fontSize: 12, fontWeight: "800" },
  laneList: { padding: 10, gap: 10, flexGrow: 1 },
  laneEmpty: { color: "#6B7D75", fontSize: 13, fontWeight: "600", textAlign: "center", paddingVertical: 24 },
  modalBackdrop: {
    flex: 1,
    backgroundColor: "rgba(0,0,0,0.72)",
    alignItems: "center",
    justifyContent: "center",
    padding: spacing.md,
  },
  modalCard: {
    width: "100%",
    maxWidth: 460,
    backgroundColor: colors.kdsSurface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.kdsBorder,
    padding: spacing.md,
    gap: 12,
  },
  modalTitle: { color: "#fff", fontSize: 20, fontWeight: "800" },
  modalSub: { color: colors.kdsMuted, fontSize: 13 },
  reasonChips: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  reasonChip: {
    backgroundColor: colors.kdsSurfaceAlt,
    borderWidth: 1,
    borderColor: "#374841",
    borderRadius: radius.pill,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  reasonChipSelected: {
    backgroundColor: colors.gold,
    borderColor: colors.gold,
  },
  reasonChipText: { color: "#E2E8F0", fontSize: 13, fontWeight: "700" },
  reasonChipTextSelected: { color: colors.ink },
  reasonInput: {
    backgroundColor: "#111916",
    borderWidth: 1,
    borderColor: "#374841",
    borderRadius: radius.sm,
    color: "#fff",
    fontSize: 15,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  modalActions: { flexDirection: "row", gap: 10, marginTop: 4 },
  modalBackBtn: {
    flex: 1,
    backgroundColor: colors.kdsSurfaceAlt,
    borderWidth: 1,
    borderColor: "#374841",
    borderRadius: radius.sm,
    paddingVertical: 12,
    alignItems: "center",
  },
  modalBackText: { color: "#fff", fontSize: 15, fontWeight: "700" },
  modalConfirmBtn: {
    flex: 1,
    backgroundColor: "#B91C1C",
    borderRadius: radius.sm,
    paddingVertical: 12,
    alignItems: "center",
  },
  modalConfirmDisabled: { opacity: 0.45 },
  modalConfirmText: { color: "#fff", fontSize: 15, fontWeight: "800" },
});
