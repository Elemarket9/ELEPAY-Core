/**
 * Offline-first Kitchen Display System (KDS) state engine for React Native tablets.
 * Mirrors web/src/kds/engine.ts so server sync cursors and idempotent action queues match 1:1.
 */

export type KdsStatus = "NEW" | "PREPARING" | "READY" | "SERVED" | "CANCELLED";
export const KDS_FLOW: KdsStatus[] = ["NEW", "PREPARING", "READY", "SERVED"];

export interface KdsTicket {
  id: string;
  merchantId: string;
  orderId: string;
  tableId: string | null;
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
}

export const emptyKdsState = (): KdsState => ({
  cursor: 0,
  tickets: {},
  queue: [],
  lastSyncAt: null,
  printerFailures: [],
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
  for (const v of result.tickets) {
    const known = tickets[v.ticket.id];
    if (known && known.ticket.changeSeq > v.ticket.changeSeq) continue;
    if (!known && v.ticket.status === "NEW") arrived.push(v.ticket.id);
    tickets[v.ticket.id] = withPending(v, state.queue);
  }
  return {
    state: {
      ...state,
      tickets: prune(tickets, now),
      cursor: Math.max(state.cursor, result.cursor),
      lastSyncAt: now,
      printerFailures: result.printerFailures,
    },
    arrived,
  };
}

export function kdsSettle(state: KdsState, actionId: string, result: KdsBumpResult | null): KdsState {
  const queue = state.queue.filter((a) => a.actionId !== actionId);
  if (!result) return { ...state, queue };
  const known = state.tickets[result.view.ticket.id];
  const tickets = { ...state.tickets };
  if (!known || known.ticket.changeSeq <= result.view.ticket.changeSeq) {
    tickets[result.view.ticket.id] = withPending(result.view, queue);
  }
  return { ...state, queue, tickets };
}

export function kdsVisible(state: KdsState, stationId: string | null): KdsTicketView[] {
  return Object.values(state.tickets)
    .filter((v) => {
      const s = kdsStationStatus(v, stationId);
      if (!isLive(s)) return false;
      if (stationId && !v.items.some((i) => i.stationId === stationId || i.stationId === null)) return false;
      return stationId ? s !== "READY" : true;
    })
    .sort((a, b) => Date.parse(a.ticket.createdAt) - Date.parse(b.ticket.createdAt));
}

export function kdsUrgency(view: KdsTicketView, now = Date.now()): "normal" | "warning" | "critical" {
  const minutes = (now - Date.parse(view.ticket.createdAt)) / 60000;
  return minutes >= 25 ? "critical" : minutes >= 15 ? "warning" : "normal";
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
