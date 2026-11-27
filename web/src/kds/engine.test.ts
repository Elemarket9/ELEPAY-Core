import { describe, expect, it } from "vitest";
import {
  applySync, backoff, bump, cancel, DEFAULT_URGENCY, emptyState, groupByCourse, groupByLane,
  hasAutoReadyAtStation, load, save, settle, stationStatus, statusToLane, urgency, visible,
  type Item, type KdsState, type SyncResult, type TicketView,
} from "./engine";

const T0 = Date.parse("2026-11-23T12:00:00Z");

function view(id: string, status: TicketView["ticket"]["status"], seq: number, items: Partial<Item>[] = [{}]): TicketView {
  return {
    ticket: {
      id, merchantId: "m", orderId: "o-" + id, tableId: null, diningMode: "TAKEOUT", serverName: "Ama", status,
      course: "ALL", notes: null, createdAt: new Date(T0).toISOString(), startedAt: null, readyAt: null,
      servedAt: null, ticketNumber: 1, clientRef: null, cancelledAt: null, changeSeq: seq,
    },
    items: items.map((p, n) => ({
      id: `${id}-i${n}`, ticketId: id, orderItemId: null, itemName: "Waakye", quantity: 1, modifiers: null,
      course: "MAIN", itemStatus: status, stationId: null, ...p,
    })),
  };
}

const sync = (cursor: number, tickets: TicketView[], extra: Partial<SyncResult> = {}): SyncResult =>
  ({ cursor, hasMore: false, serverTime: "", tickets, printerFailures: [], ...extra });

describe("kitchen screen engine", () => {
  it("shows a bump immediately and keeps it while the internet is down", () => {
    let s = applySync(emptyState(), sync(5, [view("a", "NEW", 5)]), T0).state;
    s = bump(s, "a", null, "act-1", T0);
    expect(s.tickets.a.ticket.status).toBe("PREPARING");
    expect(s.queue).toHaveLength(1);
    expect(s.queue[0].from).toBe("NEW");

    // A sync arrives with the old server state while the bump is still unsent: the bump stays visible.
    s = applySync(s, sync(5, [view("a", "NEW", 5)]), T0).state;
    expect(s.tickets.a.ticket.status).toBe("PREPARING");
  });

  it("adopts the server's version when another screen moved the ticket first", () => {
    let s = applySync(emptyState(), sync(5, [view("a", "NEW", 5)]), T0).state;
    s = bump(s, "a", null, "act-1", T0);
    s = settle(s, "act-1", { view: view("a", "READY", 9), applied: false, reason: "STATUS_CHANGED" });
    expect(s.queue).toHaveLength(0);
    expect(s.tickets.a.ticket.status).toBe("READY");
  });

  it("never lets an older snapshot overwrite a newer one", () => {
    let s = applySync(emptyState(), sync(9, [view("a", "READY", 9)]), T0).state;
    s = applySync(s, sync(9, [view("a", "NEW", 4)]), T0).state;
    expect(s.tickets.a.ticket.status).toBe("READY");
  });

  it("reports newly arrived orders once, for the sound alert", () => {
    const first = applySync(emptyState(), sync(1, [view("a", "NEW", 1)]), T0);
    expect(first.arrived).toEqual(["a"]);
    expect(applySync(first.state, sync(1, [view("a", "NEW", 1)]), T0).arrived).toEqual([]);
  });

  it("station bump moves only that station's items; ticket ready when all stations are", () => {
    const v = view("a", "PREPARING", 3, [{ stationId: "drinks", itemStatus: "PREPARING" }, { stationId: "kitchen", itemStatus: "PREPARING" }]);
    let s = applySync(emptyState(), sync(3, [v]), T0).state;
    s = bump(s, "a", "drinks", "d1", T0);
    expect(stationStatus(s.tickets.a, "drinks")).toBe("READY");
    expect(s.tickets.a.ticket.status).toBe("PREPARING");
    expect(visible(s, "drinks")).toHaveLength(0);
    expect(visible(s, "kitchen")).toHaveLength(1);
    s = bump(s, "a", "kitchen", "k1", T0);
    expect(s.tickets.a.ticket.status).toBe("READY");
    expect(visible(s, null).map((x) => x.ticket.id)).toEqual(["a"]);   // expo still shows it for pickup
  });

  it("cancelled orders disappear from the screen", () => {
    let s = applySync(emptyState(), sync(1, [view("a", "NEW", 1)]), T0).state;
    s = cancel(s, "a", "Customer left", "c1", T0);
    expect(visible(s, null)).toHaveLength(0);
    expect(s.queue[0].kind).toBe("cancel");
    expect(s.queue[0].reason).toBe("Customer left");
  });

  it("survives a tablet restart through device storage", () => {
    const mem = new Map<string, string>();
    const store = { getItem: (k: string) => mem.get(k) ?? null, setItem: (k: string, v: string) => void mem.set(k, v) };
    let s: KdsState = applySync(emptyState(), sync(7, [view("a", "NEW", 7)]), T0).state;
    s = bump(s, "a", null, "act-9", T0);
    save(store, "k", s);
    const restored = load(store, "k");
    expect(restored.cursor).toBe(7);
    expect(restored.queue[0].actionId).toBe("act-9");
    expect(restored.tickets.a.ticket.status).toBe("PREPARING");
    expect(load({ getItem: () => "{broken", setItem: () => {} }, "k")).toEqual(emptyState());
  });

  it("drops finished tickets from the device after a short while", () => {
    const served = view("a", "SERVED", 3);
    served.ticket.servedAt = new Date(T0).toISOString();
    const s = applySync(emptyState(), sync(3, [served]), T0 + 10 * 60 * 1000).state;
    expect(s.tickets.a).toBeUndefined();
  });

  it("backs off between failed syncs without waiting too long", () => {
    expect([0, 1, 2, 3, 10].map(backoff)).toEqual([3000, 6000, 12000, 20000, 20000]);
  });

  it("computes urgency using 10m/15m defaults and per-merchant thresholds from sync", () => {
    const v = view("u", "NEW", 1);
    expect(DEFAULT_URGENCY).toEqual({ warningMinutes: 10, criticalMinutes: 15 });
    expect(urgency(v, T0 + 9 * 60_000)).toBe("normal");
    expect(urgency(v, T0 + 10 * 60_000)).toBe("warning");
    expect(urgency(v, T0 + 15 * 60_000)).toBe("critical");

    const s = applySync(emptyState(), sync(1, [v], { urgencyThresholds: { warningMinutes: 4, criticalMinutes: 7 } }), T0).state;
    expect(s.urgencyThresholds).toEqual({ warningMinutes: 4, criticalMinutes: 7 });
    expect(urgency(v, T0 + 4 * 60_000, s.urgencyThresholds)).toBe("warning");
    expect(urgency(v, T0 + 7 * 60_000, s.urgencyThresholds)).toBe("critical");
  });

  it("groups items by course matching KitchenTicketText order", () => {
    const v = view("c", "NEW", 1, [
      { itemName: "Water", course: "BEVERAGE" },
      { itemName: "Kelewele", course: "APPETIZER" },
      { itemName: "Jollof", course: "MAIN" },
      { itemName: "Cake", course: "DESSERT" },
      { itemName: "Cancelled", course: "MAIN", itemStatus: "CANCELLED" },
      { itemName: "Generic", course: "ALL" },
    ]);
    const groups = groupByCourse(v.items);
    expect(groups.map((g) => g.course)).toEqual(["APPETIZER", "MAIN", "DESSERT", "BEVERAGE", null]);
    expect(groups[1].items.map((i) => i.itemName)).toEqual(["Jollof"]);
  });

  it("groups tickets into NEW / COOKING / READY lanes", () => {
    expect(statusToLane("PREPARING")).toBe("COOKING");
    const s = applySync(emptyState(), sync(3, [
      view("n1", "NEW", 1),
      view("p1", "PREPARING", 2),
      view("r1", "READY", 3),
    ]), T0).state;
    const lanes = groupByLane(visible(s, null), null);
    expect(lanes.NEW.map((t) => t.ticket.id)).toEqual(["n1"]);
    expect(lanes.COOKING.map((t) => t.ticket.id)).toEqual(["p1"]);
    expect(lanes.READY.map((t) => t.ticket.id)).toEqual(["r1"]);
  });

  it("keeps autoReady READY items visible on station screens and allows serving them", () => {
    const water = view("w", "READY", 2, [{ itemName: "Bottled Water", stationId: "bar", itemStatus: "READY", autoReady: true }]);
    let s = applySync(emptyState(), sync(2, [water]), T0).state;
    expect(hasAutoReadyAtStation(s.tickets.w, "bar")).toBe(true);
    expect(visible(s, "bar").map((t) => t.ticket.id)).toEqual(["w"]);

    s = bump(s, "w", "bar", "serve-1", T0 + 1000);
    expect(stationStatus(s.tickets.w, "bar")).toBe("SERVED");
    expect(visible(s, "bar")).toHaveLength(0);
  });
});
