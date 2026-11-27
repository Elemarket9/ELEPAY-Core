import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  ActivityIndicator,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from "react-native";
import {
  colors,
  ElepayClient,
  formatMoney,
  MerchantView,
  radius,
  spacing,
} from "@elepay/mobile-core";

export interface LiveOrderSummary {
  id: string;
  orderNumber: string;
  orderType: string;
  branchId: string | null;
  tableId: string | null;
  tableNumber: string | null;
  serverName: string | null;
  itemCount: number;
  totalQuantity: number;
  subtotalPesewas: number;
  discountPesewas: number;
  taxPesewas: number;
  tipPesewas: number;
  totalPesewas: number;
  paidPesewas: number;
  balancePesewas: number;
  paymentStatus: string;
  kitchenStatus: string;
  kdsTicketCount: number;
  createdAt: string;
  ageSeconds: number;
}

export interface LiveOrderPage {
  nextCursor: string | null;
  hasMore: boolean;
  serverTime: string;
  orders: LiveOrderSummary[];
}

export interface OrdersScreenProps {
  client: ElepayClient;
  merchant: MerchantView;
  branchId?: string | null;
  onOpenOrder: (orderId: string, tableId?: string | null) => void;
}

type StatusFilter = "ALL" | "OPEN" | "PENDING" | "PARTIAL" | "PAID" | "VOIDED";
type TypeFilter = "ALL" | "DINE_IN" | "TAKEAWAY" | "DELIVERY";

const STATUS_FILTERS: { key: StatusFilter; label: string }[] = [
  { key: "ALL", label: "All" },
  { key: "OPEN", label: "Open" },
  { key: "PENDING", label: "Pending" },
  { key: "PARTIAL", label: "Partial" },
  { key: "PAID", label: "Paid" },
  { key: "VOIDED", label: "Voided" },
];

const TYPE_FILTERS: { key: TypeFilter; label: string }[] = [
  { key: "ALL", label: "All types" },
  { key: "DINE_IN", label: "Dine-in" },
  { key: "TAKEAWAY", label: "Takeaway" },
  { key: "DELIVERY", label: "Delivery" },
];

function formatOrderAge(ageSeconds: number): string {
  const sec = Math.max(0, Math.floor(Number.isFinite(ageSeconds) ? ageSeconds : 0));
  if (sec < 60) return `${sec}s`;
  const mins = Math.floor(sec / 60);
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  const remMins = mins % 60;
  if (hours < 24) return remMins > 0 ? `${hours}h ${remMins}m` : `${hours}h`;
  const days = Math.floor(hours / 24);
  const remHours = hours % 24;
  return remHours > 0 ? `${days}d ${remHours}h` : `${days}d`;
}

function orderTypeLabel(orderType: string): string {
  switch (orderType.toUpperCase()) {
    case "DINE_IN":
      return "Dine-in";
    case "TAKEAWAY":
      return "Takeaway";
    case "DELIVERY":
      return "Delivery";
    case "STANDARD":
      return "Counter";
    default:
      return orderType;
  }
}

function paymentColors(status: string): { bg: string; fg: string; border: string } {
  switch (status.toUpperCase()) {
    case "PAID":
      return { bg: colors.okBg, fg: colors.ok, border: colors.okBorder };
    case "PARTIAL":
      return { bg: colors.infoBg, fg: colors.info, border: colors.infoBorder };
    case "VOIDED":
      return { bg: colors.dangerBg, fg: colors.danger, border: colors.dangerBorder };
    default:
      return { bg: colors.warnBg, fg: colors.warn, border: colors.warnBorder };
  }
}

function kitchenColors(status: string): { bg: string; fg: string; border: string } {
  switch (status.toUpperCase()) {
    case "READY":
    case "SERVED":
      return { bg: colors.okBg, fg: colors.ok, border: colors.okBorder };
    case "NEW":
      return { bg: colors.infoBg, fg: colors.info, border: colors.infoBorder };
    case "PREPARING":
      return { bg: colors.warnBg, fg: colors.warn, border: colors.warnBorder };
    case "CANCELLED":
      return { bg: colors.dangerBg, fg: colors.danger, border: colors.dangerBorder };
    default:
      return { bg: colors.surfaceAlt, fg: colors.muted, border: colors.line };
  }
}

export function OrdersScreen({ client, merchant, branchId, onOpenOrder }: OrdersScreenProps) {
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("OPEN");
  const [typeFilter, setTypeFilter] = useState<TypeFilter>("ALL");
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [orders, setOrders] = useState<LiveOrderSummary[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastFetchedAt, setLastFetchedAt] = useState<number>(Date.now());
  const [nowMs, setNowMs] = useState<number>(Date.now());

  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const buildPath = useCallback(
    (cursor?: string | null) => {
      const params: string[] = [`merchantId=${encodeURIComponent(merchant.id)}`, "limit=30"];
      if (branchId) params.push(`branchId=${encodeURIComponent(branchId)}`);
      if (statusFilter !== "ALL") params.push(`status=${encodeURIComponent(statusFilter)}`);
      if (typeFilter !== "ALL") params.push(`type=${encodeURIComponent(typeFilter)}`);
      if (cursor) params.push(`cursor=${encodeURIComponent(cursor)}`);
      return `/v1/pos/orders?${params.join("&")}`;
    },
    [merchant.id, branchId, statusFilter, typeFilter],
  );

  const loadPage = useCallback(
    async (opts: { silent?: boolean; cursor?: string | null } = {}) => {
      const { silent = false, cursor = null } = opts;
      if (cursor) {
        setLoadingMore(true);
      } else if (!silent) {
        setRefreshing(true);
      }
      try {
        const page = await client.call<LiveOrderPage>(buildPath(cursor));
        if (!mountedRef.current) return;
        if (page.serverTime) client.learnServerTime(page.serverTime);
        setOrders((prev) => {
          if (!cursor) return page.orders;
          const seen = new Set(prev.map((o) => o.id));
          return [...prev, ...page.orders.filter((o) => !seen.has(o.id))];
        });
        setNextCursor(page.nextCursor);
        setHasMore(page.hasMore);
        setLastFetchedAt(Date.now());
        setError(null);
      } catch (e) {
        if (!mountedRef.current) return;
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        if (mountedRef.current) {
          setRefreshing(false);
          setLoadingMore(false);
        }
      }
    },
    [client, buildPath],
  );

  useEffect(() => {
    void loadPage();
  }, [loadPage]);

  // Auto-refresh every 10s while enabled
  useEffect(() => {
    if (!autoRefresh) return;
    const timer = setInterval(() => {
      void loadPage({ silent: true });
    }, 10_000);
    return () => clearInterval(timer);
  }, [autoRefresh, loadPage]);

  // Keep order ages ticking smoothly every 15s
  useEffect(() => {
    const tick = setInterval(() => setNowMs(Date.now()), 15_000);
    return () => clearInterval(tick);
  }, []);

  const elapsedSinceFetchSec = Math.max(0, Math.floor((nowMs - lastFetchedAt) / 1000));

  return (
    <ScrollView
      style={styles.root}
      contentContainerStyle={styles.content}
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={() => void loadPage()}
          tintColor={colors.green}
        />
      }
    >
      <View style={styles.headerRow}>
        <View>
          <Text style={styles.heading}>Live Orders</Text>
          <Text style={styles.subheading}>
            {orders.length} order{orders.length === 1 ? "" : "s"} shown
          </Text>
        </View>
        <View style={styles.headerActions}>
          <Pressable
            style={[styles.autoPill, autoRefresh ? styles.autoPillOn : styles.autoPillOff]}
            onPress={() => setAutoRefresh((v) => !v)}
          >
            <View style={[styles.dot, { backgroundColor: autoRefresh ? colors.ok : colors.muted }]} />
            <Text style={[styles.autoPillText, { color: autoRefresh ? colors.ok : colors.muted }]}>
              {autoRefresh ? "Live 10s" : "Paused"}
            </Text>
          </Pressable>
          <Pressable style={styles.refreshBtn} onPress={() => void loadPage()}>
            <Text style={styles.refreshBtnText}>Refresh</Text>
          </Pressable>
        </View>
      </View>

      {/* Status filter chips */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chipRow}>
        {STATUS_FILTERS.map((f) => {
          const active = statusFilter === f.key;
          return (
            <Pressable
              key={f.key}
              style={[styles.chip, active && styles.chipActive]}
              onPress={() => setStatusFilter(f.key)}
            >
              <Text style={[styles.chipText, active && styles.chipTextActive]}>{f.label}</Text>
            </Pressable>
          );
        })}
      </ScrollView>

      {/* Order type filter chips */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chipRow}>
        {TYPE_FILTERS.map((f) => {
          const active = typeFilter === f.key;
          return (
            <Pressable
              key={f.key}
              style={[styles.chip, styles.chipSecondary, active && styles.chipSecondaryActive]}
              onPress={() => setTypeFilter(f.key)}
            >
              <Text style={[styles.chipText, active && styles.chipSecondaryTextActive]}>{f.label}</Text>
            </Pressable>
          );
        })}
      </ScrollView>

      {error && (
        <View style={styles.errorBox}>
          <Text style={styles.errorText}>{error}</Text>
        </View>
      )}

      {orders.length === 0 && !refreshing ? (
        <View style={styles.emptyCard}>
          <Text style={styles.emptyTitle}>No orders match this filter</Text>
          <Text style={styles.mutedText}>
            Try switching the status or order type filter, or pull down to refresh.
          </Text>
        </View>
      ) : (
        <View style={styles.list}>
          {orders.map((o) => {
            const payTheme = paymentColors(o.paymentStatus);
            const kdsTheme = kitchenColors(o.kitchenStatus);
            const liveAge = formatOrderAge(o.ageSeconds + elapsedSinceFetchSec);
            return (
              <Pressable
                key={o.id}
                style={({ pressed }) => [styles.orderCard, pressed && styles.orderCardPressed]}
                onPress={() => onOpenOrder(o.id, o.tableId)}
              >
                <View style={styles.rowBetween}>
                  <View style={styles.titleCluster}>
                    <Text style={styles.orderNumber}>{o.orderNumber}</Text>
                    <View style={styles.typeBadge}>
                      <Text style={styles.typeBadgeText}>{orderTypeLabel(o.orderType)}</Text>
                    </View>
                    {o.tableNumber && (
                      <View style={styles.tableBadge}>
                        <Text style={styles.tableBadgeText}>Table {o.tableNumber}</Text>
                      </View>
                    )}
                  </View>
                  <Text style={styles.ageText}>{liveAge}</Text>
                </View>

                <View style={styles.metaRow}>
                  <Text style={styles.metaText}>
                    {o.itemCount} item{o.itemCount === 1 ? "" : "s"}
                    {o.serverName ? ` · ${o.serverName}` : ""}
                  </Text>
                  <Text style={styles.totalText}>{formatMoney(o.totalPesewas)}</Text>
                </View>

                <View style={styles.rowBetween}>
                  <View style={styles.pillRow}>
                    <View
                      style={[
                        styles.statusPill,
                        { backgroundColor: payTheme.bg, borderColor: payTheme.border },
                      ]}
                    >
                      <Text style={[styles.statusPillText, { color: payTheme.fg }]}>
                        {o.paymentStatus}
                      </Text>
                    </View>
                    <View
                      style={[
                        styles.statusPill,
                        { backgroundColor: kdsTheme.bg, borderColor: kdsTheme.border },
                      ]}
                    >
                      <Text style={[styles.statusPillText, { color: kdsTheme.fg }]}>
                        KDS: {o.kitchenStatus}
                      </Text>
                    </View>
                  </View>
                  <Text style={styles.paidText}>
                    Paid {formatMoney(o.paidPesewas)}
                    {o.balancePesewas > 0 ? ` · Due ${formatMoney(o.balancePesewas)}` : ""}
                  </Text>
                </View>
              </Pressable>
            );
          })}
        </View>
      )}

      {hasMore && nextCursor && (
        <Pressable
          style={styles.loadMoreBtn}
          disabled={loadingMore}
          onPress={() => void loadPage({ cursor: nextCursor })}
        >
          {loadingMore ? (
            <ActivityIndicator size="small" color={colors.green} />
          ) : (
            <Text style={styles.loadMoreText}>Load more orders</Text>
          )}
        </Pressable>
      )}
    </ScrollView>
  );
}

export default OrdersScreen;

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  content: { padding: spacing.md, gap: spacing.sm, paddingBottom: 36 },
  headerRow: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  heading: { fontSize: 18, fontWeight: "800", color: colors.ink },
  subheading: { fontSize: 12, color: colors.muted, marginTop: 2 },
  headerActions: { flexDirection: "row", alignItems: "center", gap: 8 },
  autoPill: {
    flexDirection: "row",
    alignItems: "center",
    gap: 5,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: radius.pill,
    borderWidth: 1,
  },
  autoPillOn: { backgroundColor: colors.okBg, borderColor: colors.okBorder },
  autoPillOff: { backgroundColor: colors.surfaceAlt, borderColor: colors.line },
  dot: { width: 7, height: 7, borderRadius: 4 },
  autoPillText: { fontSize: 12, fontWeight: "700" },
  refreshBtn: {
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.lineStrong,
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: radius.xs,
  },
  refreshBtnText: { fontSize: 12, fontWeight: "700", color: colors.ink },
  chipRow: { flexDirection: "row", gap: 8, paddingVertical: 2 },
  chip: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: radius.pill,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.line,
  },
  chipActive: { backgroundColor: colors.green, borderColor: colors.green },
  chipSecondary: { backgroundColor: colors.surfaceAlt },
  chipSecondaryActive: { backgroundColor: colors.greenSoft, borderColor: colors.green },
  chipText: { fontSize: 12, fontWeight: "700", color: colors.inkSecondary },
  chipTextActive: { color: "#FFFFFF" },
  chipSecondaryTextActive: { color: colors.green },
  errorBox: {
    backgroundColor: colors.dangerBg,
    borderColor: colors.dangerBorder,
    borderWidth: 1,
    borderRadius: radius.sm,
    padding: spacing.sm,
  },
  errorText: { color: colors.danger, fontSize: 13, fontWeight: "600" },
  emptyCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.line,
    padding: spacing.lg,
    alignItems: "center",
    gap: 6,
    marginTop: spacing.xs,
  },
  emptyTitle: { fontSize: 15, fontWeight: "700", color: colors.ink },
  mutedText: { fontSize: 13, color: colors.muted, textAlign: "center" },
  list: { gap: spacing.sm },
  orderCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.line,
    padding: spacing.md,
    gap: 8,
  },
  orderCardPressed: { backgroundColor: colors.surfaceAlt, borderColor: colors.green },
  rowBetween: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", gap: 8 },
  titleCluster: { flexDirection: "row", alignItems: "center", flexWrap: "wrap", gap: 6, flex: 1 },
  orderNumber: { fontSize: 15, fontWeight: "800", color: colors.ink },
  typeBadge: {
    backgroundColor: colors.surfaceAlt,
    borderWidth: 1,
    borderColor: colors.line,
    paddingHorizontal: 7,
    paddingVertical: 2,
    borderRadius: radius.xs,
  },
  typeBadgeText: { fontSize: 11, fontWeight: "700", color: colors.inkSecondary },
  tableBadge: {
    backgroundColor: colors.goldSoft,
    borderWidth: 1,
    borderColor: colors.gold,
    paddingHorizontal: 7,
    paddingVertical: 2,
    borderRadius: radius.xs,
  },
  tableBadgeText: { fontSize: 11, fontWeight: "800", color: colors.warn },
  ageText: { fontSize: 12, fontWeight: "700", color: colors.muted },
  metaRow: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  metaText: { fontSize: 13, color: colors.muted, flex: 1 },
  totalText: { fontSize: 16, fontWeight: "800", color: colors.ink },
  pillRow: { flexDirection: "row", alignItems: "center", gap: 6, flexWrap: "wrap" },
  statusPill: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: radius.pill,
    borderWidth: 1,
  },
  statusPillText: { fontSize: 11, fontWeight: "800" },
  paidText: { fontSize: 12, fontWeight: "600", color: colors.muted },
  loadMoreBtn: {
    marginTop: spacing.xs,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.green,
    borderRadius: radius.sm,
    paddingVertical: 11,
    alignItems: "center",
  },
  loadMoreText: { fontSize: 13, fontWeight: "700", color: colors.green },
});
