import { describe, expect, it } from "vitest";
import {
  applySync, bump, cancel, emptyState, load, save, settle, stationStatus, visible, backoff,
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

const sync = (cursor: number, tickets: TicketView[]): SyncResult =>
  ({ cursor, hasMore: false, serverTime: "", tickets, printerFailures: [] });

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
});
