/**
 * Kitchen screen state, kept separate from the UI so it can be tested.
 *
 * The screen keeps working through internet drops:
 *  - tickets and the sync cursor are cached on the device, so a restarted tablet shows the last known orders;
 *  - bumps are applied on screen immediately and queued, then sent in order when the network is back;
 *  - every queued bump carries an action id and the status it expects to move from, so the server applies it
 *    at most once and never lets two screens skip a stage. When the server says another screen moved the
 *    ticket first, the server's version wins.
 */

export type Status = "NEW" | "PREPARING" | "READY" | "SERVED" | "CANCELLED";
export const FLOW: Status[] = ["NEW", "PREPARING", "READY", "SERVED"];

export interface Ticket {
  id: string; merchantId: string; orderId: string; tableId: string | null; diningMode: string;
  serverName: string | null; status: Status; course: string; notes: string | null;
  createdAt: string; startedAt: string | null; readyAt: string | null; servedAt: string | null;
  ticketNumber: number | null; clientRef: string | null; cancelledAt: string | null; changeSeq: number;
  paymentStatus?: string | null; tableNumber?: string | null;
}

export interface Item {
  id: string; ticketId: string; orderItemId: string | null; itemName: string; quantity: number;
  modifiers: string | null; course: string; itemStatus: Status; stationId: string | null;
}

export interface TicketView { ticket: Ticket; items: Item[] }

export interface PrintFailure { id: string; stationName: string; ticketId: string; lastError: string | null; createdAt: string }

export interface SyncResult {
  cursor: number; hasMore: boolean; serverTime: string; tickets: TicketView[]; printerFailures: PrintFailure[];
}

export interface BumpResult { view: TicketView; applied: boolean; reason: string | null }

export type ActionKind = "advance" | "cancel";

export interface QueuedAction {
  actionId: string; kind: ActionKind; ticketId: string; stationId: string | null;
  from: Status | null; reason: string | null; queuedAt: number;
}

export interface KdsState {
  cursor: number;
  tickets: Record<string, TicketView>;
  queue: QueuedAction[];
  lastSyncAt: number | null;
  printerFailures: PrintFailure[];
}

export const emptyState = (): KdsState => ({ cursor: 0, tickets: {}, queue: [], lastSyncAt: null, printerFailures: [] });

const LIVE = (s: Status) => s !== "SERVED" && s !== "CANCELLED";

/** Status of a ticket as one station sees it: its slowest live item; the whole ticket when no station is chosen. */
export function stationStatus(view: TicketView, stationId: string | null): Status {
  if (view.ticket.status === "CANCELLED") return "CANCELLED";
  if (!stationId) return view.ticket.status;
  const mine = view.items.filter((i) => (i.stationId === stationId || i.stationId === null) && i.itemStatus !== "CANCELLED");
  if (mine.length === 0) return view.ticket.status;
  return FLOW[Math.min(...mine.map((i) => FLOW.indexOf(i.itemStatus)))];
}

export function nextStatus(s: Status): Status | null {
  const i = FLOW.indexOf(s);
  return i < 0 || i === FLOW.length - 1 ? null : FLOW[i + 1];
}

/** What a bump does locally, before the server confirms. Mirrors the server's rules. */
export function applyLocally(view: TicketView, a: QueuedAction): TicketView {
  if (a.kind === "cancel") {
    if (!LIVE(view.ticket.status)) return view;
    return {
      ticket: { ...view.ticket, status: "CANCELLED", cancelledAt: new Date(a.queuedAt).toISOString() },
      items: view.items.map((i) => (i.itemStatus === "SERVED" ? i : { ...i, itemStatus: "CANCELLED" })),
    };
  }
  const from = a.from!;
  const to = nextStatus(from);
  if (!to) return view;
  if (!a.stationId) {
    if (view.ticket.status !== from) return view;
    const rank = FLOW.indexOf(to);
    return {
      ticket: { ...view.ticket, status: to },
      items: view.items.map((i) => (FLOW.indexOf(i.itemStatus) >= 0 && FLOW.indexOf(i.itemStatus) < rank ? { ...i, itemStatus: to } : i)),
    };
  }
  const items = view.items.map((i) =>
    (i.stationId === a.stationId || i.stationId === null) && i.itemStatus === from ? { ...i, itemStatus: to } : i);
  const live = items.filter((i) => i.itemStatus !== "CANCELLED");
  let status = view.ticket.status;
  if (live.length && FLOW.includes(status)) {
    const slowest = Math.min(...live.map((i) => FLOW.indexOf(i.itemStatus)));
    const started = live.some((i) => FLOW.indexOf(i.itemStatus) >= 1);
    const target = FLOW[Math.max(slowest, started ? 1 : 0)];
    if (FLOW.indexOf(target) > FLOW.indexOf(status)) status = target;
  }
  return { ticket: { ...view.ticket, status }, items };
}

/** A tap on the bump button: queue the action and show its effect at once. */
export function bump(state: KdsState, ticketId: string, stationId: string | null, actionId: string, now = Date.now()): KdsState {
  const view = state.tickets[ticketId];
  if (!view) return state;
  const from = stationStatus(view, stationId);
  if (!nextStatus(from) || from === "CANCELLED") return state;
  const action: QueuedAction = { actionId, kind: "advance", ticketId, stationId, from, reason: null, queuedAt: now };
  return { ...state, queue: [...state.queue, action], tickets: { ...state.tickets, [ticketId]: applyLocally(view, action) } };
}

export function cancel(state: KdsState, ticketId: string, reason: string | null, actionId: string, now = Date.now()): KdsState {
  const view = state.tickets[ticketId];
  if (!view || !LIVE(view.ticket.status)) return state;
  const action: QueuedAction = { actionId, kind: "cancel", ticketId, stationId: null, from: null, reason, queuedAt: now };
  return { ...state, queue: [...state.queue, action], tickets: { ...state.tickets, [ticketId]: applyLocally(view, action) } };
}

/** Server snapshot for a ticket, with this screen's still-unsent actions replayed on top. */
function withPending(server: TicketView, queue: QueuedAction[]): TicketView {
  return queue.filter((a) => a.ticketId === server.ticket.id).reduce(applyLocally, server);
}

/** Merge a sync page. Server snapshots win, except that unsent local bumps stay visible until they are sent. */
export function applySync(state: KdsState, result: SyncResult, now = Date.now()): { state: KdsState; arrived: string[] } {
  const tickets = { ...state.tickets };
  const arrived: string[] = [];
  for (const v of result.tickets) {
    const known = tickets[v.ticket.id];
    if (known && known.ticket.changeSeq > v.ticket.changeSeq) continue;
    if (!known && v.ticket.status === "NEW") arrived.push(v.ticket.id);
    tickets[v.ticket.id] = withPending(v, state.queue);
  }
  return {
    state: { ...state, tickets: prune(tickets, now), cursor: Math.max(state.cursor, result.cursor), lastSyncAt: now,
      printerFailures: result.printerFailures },
    arrived,
  };
}

/** The server answered a queued action: drop it from the queue and adopt the server's version of the ticket. */
export function settle(state: KdsState, actionId: string, result: BumpResult | null): KdsState {
  const queue = state.queue.filter((a) => a.actionId !== actionId);
  if (!result) return { ...state, queue };
  const known = state.tickets[result.view.ticket.id];
  const tickets = { ...state.tickets };
  if (!known || known.ticket.changeSeq <= result.view.ticket.changeSeq) tickets[result.view.ticket.id] = withPending(result.view, queue);
  return { ...state, queue, tickets };
}

/** Finished tickets stay briefly (so a mistaken bump can be seen) and are then dropped from the device. */
const KEEP_FINISHED_MS = 2 * 60 * 1000;
function prune(tickets: Record<string, TicketView>, now: number): Record<string, TicketView> {
  const out: Record<string, TicketView> = {};
  for (const [id, v] of Object.entries(tickets)) {
    const t = v.ticket;
    const doneAt = t.servedAt ?? t.cancelledAt;
    if (!LIVE(t.status) && doneAt && now - Date.parse(doneAt) > KEEP_FINISHED_MS) continue;
    out[id] = v;
  }
  return out;
}

/** Tickets a screen should show, oldest first. Expo (no station) also shows READY tickets awaiting pickup. */
export function visible(state: KdsState, stationId: string | null): TicketView[] {
  return Object.values(state.tickets)
    .filter((v) => {
      const s = stationStatus(v, stationId);
      if (!LIVE(s)) return false;
      if (stationId && !v.items.some((i) => i.stationId === stationId || i.stationId === null)) return false;
      return stationId ? s !== "READY" : true;
    })
    .sort((a, b) => Date.parse(a.ticket.createdAt) - Date.parse(b.ticket.createdAt));
}

export function urgency(view: TicketView, now = Date.now()): "normal" | "warning" | "critical" {
  const minutes = (now - Date.parse(view.ticket.createdAt)) / 60000;
  return minutes >= 15 ? "critical" : minutes >= 10 ? "warning" : "normal";
}

export function elapsed(view: TicketView, now = Date.now()): string {
  const secs = Math.max(0, Math.floor((now - Date.parse(view.ticket.createdAt)) / 1000));
  return `${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, "0")}`;
}

/** Retry delay after failed syncs: 3s, 6s, 12s, then every 20s. */
export function backoff(failures: number): number {
  return failures <= 0 ? 3000 : Math.min(20000, 3000 * 2 ** failures);
}

// ---------------------------------------------------------------- device storage

export interface Storage { getItem(k: string): string | null; setItem(k: string, v: string): void }

export function storageKey(merchantId: string, stationId: string | null) {
  return `elepay.kds.${merchantId}.${stationId ?? "all"}`;
}

export function load(store: Storage | null, key: string): KdsState {
  try {
    const raw = store?.getItem(key);
    if (!raw) return emptyState();
    const parsed = JSON.parse(raw) as KdsState;
    return { ...emptyState(), ...parsed };
  } catch {
    return emptyState();
  }
}

export function save(store: Storage | null, key: string, state: KdsState) {
  try {
    store?.setItem(key, JSON.stringify(state));
  } catch {
    /* storage full or blocked: the screen still works, it just will not survive a restart */
  }
}
