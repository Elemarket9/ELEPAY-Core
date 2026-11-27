/**
 * Offline-first Kitchen Display System (KDS) state engine for React Native tablets.
 * Mirrors web/src/kds/engine.ts so server sync cursors and idempotent action queues match 1:1.
 */

export type KdsStatus = "NEW" | "PREPARING" | "READY" | "SERVED" | "CANCELLED";
export const KDS_FLOW: KdsStatus[] = ["NEW", "PREPARING", "READY", "SERVED"];

export interface KdsUrgencyThresholds {
  warningMinutes: number;
  criticalMinutes: number;
}

export const DEFAULT_KDS_URGENCY: KdsUrgencyThresholds = {
  warningMinutes: 10,
  criticalMinutes: 15,
};

export const KDS_CANCEL_REASONS = [
  "Out of stock",
  "Customer cancelled",
  "Duplicate order",
  "Wrong item",
  "Kitchen error",
  "Other",
] as const;

export type KdsLane = "NEW" | "COOKING" | "READY";
export const KDS_LANES: KdsLane[] = ["NEW", "COOKING", "READY"];

export interface KdsTicket {
  id: string;
  merchantId: string;
  orderId: string;
  tableId: string | null;
  tableNumber?: string | null;
  diningMode: string;
  serverName: string | null;
  status: KdsStatus;
  course: string;
  notes: string | null;
  createdAt: string;
  startedAt: string | null;
  readyAt: string | null;
  servedAt: string | null;
  ticketNumber: number | null;
  clientRef: string | null;
  cancelledAt: string | null;
  changeSeq: number;
  round?: number;
}

export interface KdsItem {
  id: string;
  ticketId: string;
  orderItemId: string | null;
  itemName: string;
  quantity: number;
  modifiers: string | null;
  course: string;
  itemStatus: KdsStatus;
  stationId: string | null;
  autoReady?: boolean;
}

export interface KdsTicketView {
  ticket: KdsTicket;
  items: KdsItem[];
}

export interface KdsPrintFailure {
  id: string;
  stationName: string;
  ticketId: string;
  lastError: string | null;
  createdAt: string;
}

export interface KdsSyncResult {
  cursor: number;
  hasMore: boolean;
  serverTime: string;
  tickets: KdsTicketView[];
  printerFailures: KdsPrintFailure[];
  urgencyThresholds?: KdsUrgencyThresholds | null;
}

export interface KdsBumpResult {
  view: KdsTicketView;
  applied: boolean;
  reason: string | null;
}

export type KdsActionKind = "advance" | "cancel";

export interface KdsQueuedAction {
  actionId: string;
  kind: KdsActionKind;
  ticketId: string;
  stationId: string | null;
  from: KdsStatus | null;
  reason: string | null;
  queuedAt: number;
}

export interface KdsState {
  cursor: number;
  tickets: Record<string, KdsTicketView>;
  queue: KdsQueuedAction[];
  lastSyncAt: number | null;
  printerFailures: KdsPrintFailure[];
  urgencyThresholds?: KdsUrgencyThresholds;
  needsFullResync?: boolean;
  resyncSeenIds?: string[];
}

export const emptyKdsState = (): KdsState => ({
  cursor: 0,
  tickets: {},
  queue: [],
  lastSyncAt: null,
  printerFailures: [],
  urgencyThresholds: { ...DEFAULT_KDS_URGENCY },
  needsFullResync: false,
});

const isLive = (s: KdsStatus) => s !== "SERVED" && s !== "CANCELLED";

export function kdsStationStatus(view: KdsTicketView, stationId: string | null): KdsStatus {
  if (view.ticket.status === "CANCELLED") return "CANCELLED";
  if (!stationId) return view.ticket.status;
  const mine = view.items.filter(
    (i) => (i.stationId === stationId || i.stationId === null) && i.itemStatus !== "CANCELLED",
  );
  if (mine.length === 0) return view.ticket.status;
  return KDS_FLOW[Math.min(...mine.map((i) => KDS_FLOW.indexOf(i.itemStatus)))];
}

export function kdsNextStatus(s: KdsStatus): KdsStatus | null {
  const i = KDS_FLOW.indexOf(s);
  return i < 0 || i === KDS_FLOW.length - 1 ? null : KDS_FLOW[i + 1];
}

export function kdsApplyLocally(view: KdsTicketView, a: KdsQueuedAction): KdsTicketView {
  if (a.kind === "cancel") {
    if (!isLive(view.ticket.status)) return view;
    return {
      ticket: { ...view.ticket, status: "CANCELLED", cancelledAt: new Date(a.queuedAt).toISOString() },
      items: view.items.map((i) => (i.itemStatus === "SERVED" ? i : { ...i, itemStatus: "CANCELLED" })),
    };
  }
  const from = a.from!;
  const to = kdsNextStatus(from);
  if (!to) return view;
  if (!a.stationId) {
    if (view.ticket.status !== from) return view;
    const rank = KDS_FLOW.indexOf(to);
    return {
      ticket: { ...view.ticket, status: to },
      items: view.items.map((i) =>
        KDS_FLOW.indexOf(i.itemStatus) >= 0 && KDS_FLOW.indexOf(i.itemStatus) < rank ? { ...i, itemStatus: to } : i,
      ),
    };
  }
  const items = view.items.map((i) =>
    (i.stationId === a.stationId || i.stationId === null) && i.itemStatus === from ? { ...i, itemStatus: to } : i,
  );
  const live = items.filter((i) => i.itemStatus !== "CANCELLED");
  let status = view.ticket.status;
  if (live.length && KDS_FLOW.includes(status)) {
    const slowest = Math.min(...live.map((i) => KDS_FLOW.indexOf(i.itemStatus)));
    const started = live.some((i) => KDS_FLOW.indexOf(i.itemStatus) >= 1);
    const target = KDS_FLOW[Math.max(slowest, started ? 1 : 0)];
    if (KDS_FLOW.indexOf(target) > KDS_FLOW.indexOf(status)) status = target;
  }
  return { ticket: { ...view.ticket, status }, items };
}

export function kdsBump(
  state: KdsState,
  ticketId: string,
  stationId: string | null,
  actionId: string,
  now = Date.now(),
): KdsState {
  const view = state.tickets[ticketId];
  if (!view) return state;
  const from = kdsStationStatus(view, stationId);
  if (!kdsNextStatus(from) || from === "CANCELLED") return state;
  const action: KdsQueuedAction = { actionId, kind: "advance", ticketId, stationId, from, reason: null, queuedAt: now };
  return {
    ...state,
    queue: [...state.queue, action],
    tickets: { ...state.tickets, [ticketId]: kdsApplyLocally(view, action) },
  };
}

export function kdsCancel(
  state: KdsState,
  ticketId: string,
  reason: string | null,
  actionId: string,
  now = Date.now(),
): KdsState {
  const view = state.tickets[ticketId];
  if (!view || !isLive(view.ticket.status)) return state;
  const action: KdsQueuedAction = { actionId, kind: "cancel", ticketId, stationId: null, from: null, reason, queuedAt: now };
  return {
    ...state,
    queue: [...state.queue, action],
    tickets: { ...state.tickets, [ticketId]: kdsApplyLocally(view, action) },
  };
}

function withPending(server: KdsTicketView, queue: KdsQueuedAction[]): KdsTicketView {
  return queue.filter((a) => a.ticketId === server.ticket.id).reduce(kdsApplyLocally, server);
}

const KEEP_FINISHED_MS = 2 * 60 * 1000;
function prune(tickets: Record<string, KdsTicketView>, now: number): Record<string, KdsTicketView> {
  const out: Record<string, KdsTicketView> = {};
  for (const [id, v] of Object.entries(tickets)) {
    const t = v.ticket;
    const doneAt = t.servedAt ?? t.cancelledAt;
    if (!isLive(t.status) && doneAt && now - Date.parse(doneAt) > KEEP_FINISHED_MS) continue;
    out[id] = v;
  }
  return out;
}

export function kdsApplySync(
  state: KdsState,
  result: KdsSyncResult,
  now = Date.now(),
): { state: KdsState; arrived: string[] } {
  const tickets = { ...state.tickets };
  const arrived: string[] = [];
  const isResyncing = Boolean(state.needsFullResync);
  const seenIds = isResyncing ? new Set<string>(state.resyncSeenIds ?? []) : null;

  for (const v of result.tickets) {
    if (seenIds) seenIds.add(v.ticket.id);
    const known = tickets[v.ticket.id];
    if (!isResyncing && known && known.ticket.changeSeq > v.ticket.changeSeq) continue;
    if (!known && v.ticket.status === "NEW") arrived.push(v.ticket.id);
    tickets[v.ticket.id] = withPending(v, state.queue);
  }

  if (isResyncing && !result.hasMore && seenIds) {
    const pendingTicketIds = new Set(state.queue.map((a) => a.ticketId));
    for (const id of Object.keys(tickets)) {
      if (!seenIds.has(id) && !pendingTicketIds.has(id)) {
        delete tickets[id];
      }
    }
  }

  const stillResyncing = isResyncing && result.hasMore;
  const thresholds =
    result.urgencyThresholds &&
    result.urgencyThresholds.warningMinutes > 0 &&
    result.urgencyThresholds.criticalMinutes > result.urgencyThresholds.warningMinutes
      ? result.urgencyThresholds
      : (state.urgencyThresholds ?? { ...DEFAULT_KDS_URGENCY });

  return {
    state: {
      ...state,
      tickets: prune(tickets, now),
      cursor: Math.max(state.cursor, result.cursor),
      lastSyncAt: now,
      printerFailures: result.printerFailures,
      urgencyThresholds: thresholds,
      needsFullResync: stillResyncing,
      resyncSeenIds: stillResyncing && seenIds ? Array.from(seenIds) : undefined,
    },
    arrived,
  };
}

export function kdsSettle(state: KdsState, actionId: string, result: KdsBumpResult | null): KdsState {
  const queue = state.queue.filter((a) => a.actionId !== actionId);
  if (!result) {
    return { ...state, queue, cursor: 0, needsFullResync: true, resyncSeenIds: [] };
  }
  const known = state.tickets[result.view.ticket.id];
  const tickets = { ...state.tickets };
  if (!known || known.ticket.changeSeq <= result.view.ticket.changeSeq) {
    tickets[result.view.ticket.id] = withPending(result.view, queue);
  }
  return { ...state, queue, tickets };
}

export function kdsHasAutoReadyAtStation(view: KdsTicketView, stationId: string | null): boolean {
  if (!stationId) return false;
  return view.items.some(
    (i) =>
      (i.stationId === stationId || i.stationId === null) &&
      i.itemStatus === "READY" &&
      Boolean(i.autoReady),
  );
}

export function kdsVisible(state: KdsState, stationId: string | null): KdsTicketView[] {
  return Object.values(state.tickets)
    .filter((v) => {
      const s = kdsStationStatus(v, stationId);
      if (!isLive(s)) return false;
      if (stationId && !v.items.some((i) => i.stationId === stationId || i.stationId === null)) return false;
      if (stationId && s === "READY") {
        return kdsHasAutoReadyAtStation(v, stationId);
      }
      return true;
    })
    .sort((a, b) => Date.parse(a.ticket.createdAt) - Date.parse(b.ticket.createdAt));
}

export function kdsUrgency(
  view: KdsTicketView,
  now = Date.now(),
  thresholds?: KdsUrgencyThresholds | null,
): "normal" | "warning" | "critical" {
  const warn = thresholds?.warningMinutes ?? DEFAULT_KDS_URGENCY.warningMinutes;
  const crit = thresholds?.criticalMinutes ?? DEFAULT_KDS_URGENCY.criticalMinutes;
  const minutes = (now - Date.parse(view.ticket.createdAt)) / 60000;
  return minutes >= crit ? "critical" : minutes >= warn ? "warning" : "normal";
}

export interface KdsCourseGroup {
  course: string | null;
  items: KdsItem[];
}

const COURSE_ORDER: Record<string, number> = {
  APPETIZER: 1,
  MAIN: 2,
  DESSERT: 3,
  BEVERAGE: 4,
};

function courseRank(course: string | null): number {
  if (!course) return 6;
  return COURSE_ORDER[course] ?? 5;
}

/**
 * Group non-cancelled items by course in the same order as KitchenTicketText:
 * APPETIZER -> MAIN -> DESSERT -> BEVERAGE -> other named courses -> ungrouped (ALL/blank).
 */
export function kdsGroupByCourse(items: KdsItem[]): KdsCourseGroup[] {
  const buckets = new Map<string, KdsCourseGroup>();
  for (const item of items) {
    if (item.itemStatus === "CANCELLED") continue;
    const raw = (item.course ?? "").trim().toUpperCase();
    const normalized = !raw || raw === "ALL" ? null : raw;
    const key = normalized ?? "__ALL__";
    let group = buckets.get(key);
    if (!group) {
      group = { course: normalized, items: [] };
      buckets.set(key, group);
    }
    group.items.push(item);
  }
  return Array.from(buckets.values()).sort((a, b) => {
    const diff = courseRank(a.course) - courseRank(b.course);
    if (diff !== 0) return diff;
    return (a.course ?? "").localeCompare(b.course ?? "");
  });
}

export function kdsStatusToLane(status: KdsStatus): KdsLane | null {
  if (status === "NEW") return "NEW";
  if (status === "PREPARING") return "COOKING";
  if (status === "READY") return "READY";
  return null;
}

export function kdsGroupByLane(
  tickets: KdsTicketView[],
  stationId: string | null,
): Record<KdsLane, KdsTicketView[]> {
  const lanes: Record<KdsLane, KdsTicketView[]> = { NEW: [], COOKING: [], READY: [] };
  for (const v of tickets) {
    const lane = kdsStatusToLane(kdsStationStatus(v, stationId));
    if (lane) lanes[lane].push(v);
  }
  return lanes;
}

export function kdsElapsed(view: KdsTicketView, now = Date.now()): string {
  const secs = Math.max(0, Math.floor((now - Date.parse(view.ticket.createdAt)) / 1000));
  return `${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, "0")}`;
}

export function kdsBackoff(failures: number): number {
  return failures <= 0 ? 3000 : Math.min(20000, 3000 * 2 ** failures);
}

export function kdsStorageKey(merchantId: string, stationId: string | null): string {
  return `elepay.kds.${merchantId}.${stationId ?? "all"}`;
}
