import { describe, expect, it } from "vitest";
import {
  DEFAULT_KDS_URGENCY,
  emptyKdsState,
  KDS_CANCEL_REASONS,
  kdsApplySync,
  kdsBump,
  kdsCancel,
  kdsGroupByCourse,
  kdsGroupByLane,
  kdsHasAutoReadyAtStation,
  kdsSettle,
  kdsStationStatus,
  kdsStatusToLane,
  kdsUrgency,
  kdsVisible,
  type KdsItem,
  type KdsSyncResult,
  type KdsTicket,
  type KdsTicketView,
} from "../kdsEngine";

const T0 = Date.parse("2026-11-23T12:00:00Z");

function makeView(
  id: string,
  status: KdsTicket["status"],
  seq: number,
  items: Partial<KdsItem>[] = [{}],
  ticketOverrides: Partial<KdsTicket> = {},
): KdsTicketView {
  return {
    ticket: {
      id,
      merchantId: "m1",
      orderId: `o-${id}`,
      tableId: null,
      tableNumber: null,
      diningMode: "DINE_IN",
      serverName: "Ama",
      status,
      course: "ALL",
      notes: null,
      createdAt: new Date(T0).toISOString(),
      startedAt: null,
      readyAt: null,
      servedAt: null,
      ticketNumber: 1,
      clientRef: null,
      cancelledAt: null,
      changeSeq: seq,
      round: 1,
      ...ticketOverrides,
    },
    items: items.map((p, n) => ({
      id: `${id}-i${n}`,
      ticketId: id,
      orderItemId: null,
      itemName: "Jollof Rice",
      quantity: 1,
      modifiers: null,
      course: "MAIN",
      itemStatus: status,
      stationId: null,
      ...p,
    })),
  };
}

function makeSync(
  cursor: number,
  tickets: KdsTicketView[],
  overrides: Partial<KdsSyncResult> = {},
): KdsSyncResult {
  return {
    cursor,
    hasMore: false,
    serverTime: new Date(T0).toISOString(),
    tickets,
    printerFailures: [],
    ...overrides,
  };
}

describe("apps/shared kdsEngine (Restovax Phase 1, 2, 8)", () => {
  it("uses 10m warning / 15m critical default urgency thresholds and respects custom per-merchant thresholds", () => {
    const v = makeView("t1", "NEW", 1);
    expect(DEFAULT_KDS_URGENCY).toEqual({ warningMinutes: 10, criticalMinutes: 15 });

    // Default 10/15
    expect(kdsUrgency(v, T0 + 9 * 60_000)).toBe("normal");
    expect(kdsUrgency(v, T0 + 10 * 60_000)).toBe("warning");
    expect(kdsUrgency(v, T0 + 14 * 60_000 + 59_000)).toBe("warning");
    expect(kdsUrgency(v, T0 + 15 * 60_000)).toBe("critical");

    // Custom 5/8 synced from server payload
    const synced = kdsApplySync(
      emptyKdsState(),
      makeSync(1, [v], { urgencyThresholds: { warningMinutes: 5, criticalMinutes: 8 } }),
      T0,
    ).state;
    expect(synced.urgencyThresholds).toEqual({ warningMinutes: 5, criticalMinutes: 8 });
    expect(kdsUrgency(v, T0 + 4 * 60_000, synced.urgencyThresholds)).toBe("normal");
    expect(kdsUrgency(v, T0 + 5 * 60_000, synced.urgencyThresholds)).toBe("warning");
    expect(kdsUrgency(v, T0 + 8 * 60_000, synced.urgencyThresholds)).toBe("critical");

    // Subsequent sync without thresholds preserves previously synced thresholds
    const synced2 = kdsApplySync(synced, makeSync(2, [v]), T0 + 1000).state;
    expect(synced2.urgencyThresholds).toEqual({ warningMinutes: 5, criticalMinutes: 8 });
  });

  it("preserves tableNumber and round >= 2 for add-on tickets across sync and bump", () => {
    const addOn = makeView("t-r2", "NEW", 10, [{ itemName: "Grilled Tilapia" }], {
      tableId: "tbl-12",
      tableNumber: "12",
      round: 2,
    });
    let state = kdsApplySync(emptyKdsState(), makeSync(10, [addOn]), T0).state;
    expect(state.tickets["t-r2"].ticket.tableNumber).toBe("12");
    expect(state.tickets["t-r2"].ticket.round).toBe(2);

    state = kdsBump(state, "t-r2", null, "act-1", T0 + 5_000);
    expect(state.tickets["t-r2"].ticket.status).toBe("PREPARING");
    expect(state.tickets["t-r2"].ticket.tableNumber).toBe("12");
    expect(state.tickets["t-r2"].ticket.round).toBe(2);
  });

  it("groups items by course matching KitchenTicketText order and excludes cancelled items", () => {
    const v = makeView("t-courses", "NEW", 1, [
      { id: "i1", itemName: "Coke", course: "BEVERAGE" },
      { id: "i2", itemName: "Banku", course: "MAIN" },
      { id: "i3", itemName: "Spring Rolls", course: "APPETIZER" },
      { id: "i4", itemName: "Ice Cream", course: "DESSERT" },
      { id: "i5", itemName: "Extra Napkins", course: "ALL" },
      { id: "i6", itemName: "Sides Platter", course: "SIDES" },
      { id: "i7", itemName: "Cancelled Soup", course: "APPETIZER", itemStatus: "CANCELLED" },
    ]);

    const groups = kdsGroupByCourse(v.items);
    expect(groups.map((g) => g.course)).toEqual(["APPETIZER", "MAIN", "DESSERT", "BEVERAGE", "SIDES", null]);
    expect(groups[0].items.map((i) => i.itemName)).toEqual(["Spring Rolls"]);
    expect(groups[5].items.map((i) => i.itemName)).toEqual(["Extra Napkins"]);
  });

  it("maps PREPARING to COOKING lane and groups tickets into NEW / COOKING / READY lanes", () => {
    expect(kdsStatusToLane("NEW")).toBe("NEW");
    expect(kdsStatusToLane("PREPARING")).toBe("COOKING");
    expect(kdsStatusToLane("READY")).toBe("READY");
    expect(kdsStatusToLane("SERVED")).toBeNull();
    expect(kdsStatusToLane("CANCELLED")).toBeNull();

    const tNew = makeView("t1", "NEW", 1);
    const tPrep = makeView("t2", "PREPARING", 2);
    const tReady = makeView("t3", "READY", 3);
    const state = kdsApplySync(emptyKdsState(), makeSync(3, [tNew, tPrep, tReady]), T0).state;

    const lanes = kdsGroupByLane(kdsVisible(state, null), null);
    expect(lanes.NEW.map((v) => v.ticket.id)).toEqual(["t1"]);
    expect(lanes.COOKING.map((v) => v.ticket.id)).toEqual(["t2"]);
    expect(lanes.READY.map((v) => v.ticket.id)).toEqual(["t3"]);
  });

  it("queues cancel reason while offline and preserves it across stale syncs until settled", () => {
    expect(KDS_CANCEL_REASONS.length).toBeGreaterThanOrEqual(4);
    const v = makeView("t-cancel", "NEW", 5);
    let state = kdsApplySync(emptyKdsState(), makeSync(5, [v]), T0).state;

    state = kdsCancel(state, "t-cancel", "Out of stock: No tilapia left", "cancel-1", T0 + 1000);
    expect(kdsVisible(state, null)).toHaveLength(0);
    expect(state.queue).toHaveLength(1);
    expect(state.queue[0]).toMatchObject({
      actionId: "cancel-1",
      kind: "cancel",
      ticketId: "t-cancel",
      reason: "Out of stock: No tilapia left",
    });

    // Stale sync arrives while offline/before cancel flushes -> ticket stays cancelled locally
    state = kdsApplySync(state, makeSync(5, [v]), T0 + 2000).state;
    expect(kdsVisible(state, null)).toHaveLength(0);
    expect(state.queue[0].reason).toBe("Out of stock: No tilapia left");

    // Once server confirms cancel, queue empties
    const cancelledServer = makeView("t-cancel", "CANCELLED", 6, [{}], {
      cancelledAt: new Date(T0 + 3000).toISOString(),
    });
    state = kdsSettle(state, "cancel-1", { view: cancelledServer, applied: true, reason: null });
    expect(state.queue).toHaveLength(0);
  });

  it("keeps autoReady items visible at READY on station screens while hiding manually-bumped READY items", () => {
    // 1. Manually bumped item at station "drinks" hides at READY
    const manual = makeView("t-manual", "PREPARING", 1, [
      { stationId: "drinks", itemStatus: "PREPARING", autoReady: false },
      { stationId: "kitchen", itemStatus: "PREPARING", autoReady: false },
    ]);
    let state = kdsApplySync(emptyKdsState(), makeSync(1, [manual]), T0).state;
    state = kdsBump(state, "t-manual", "drinks", "b1", T0 + 1000);
    expect(kdsStationStatus(state.tickets["t-manual"], "drinks")).toBe("READY");
    expect(kdsHasAutoReadyAtStation(state.tickets["t-manual"], "drinks")).toBe(false);
    expect(kdsVisible(state, "drinks")).toHaveLength(0);

    // 2. autoReady item (e.g. bottled water) at bar arrives READY and remains visible on bar station screen
    const water = makeView("t-water", "READY", 2, [
      { itemName: "Bottled Water", stationId: "bar", itemStatus: "READY", autoReady: true },
    ]);
    state = kdsApplySync(state, makeSync(2, [water]), T0 + 2000).state;
    expect(kdsStationStatus(state.tickets["t-water"], "bar")).toBe("READY");
    expect(kdsHasAutoReadyAtStation(state.tickets["t-water"], "bar")).toBe(true);
    expect(kdsVisible(state, "bar").map((v) => v.ticket.id)).toEqual(["t-water"]);

    // 3. Bar screen bumps the autoReady item from READY -> SERVED, clearing it from the bar screen
    state = kdsBump(state, "t-water", "bar", "b-serve-water", T0 + 3000);
    expect(kdsStationStatus(state.tickets["t-water"], "bar")).toBe("SERVED");
    expect(kdsVisible(state, "bar")).toHaveLength(0);
    expect(state.queue.at(-1)).toMatchObject({
      actionId: "b-serve-water",
      stationId: "bar",
      from: "READY",
    });
  });
});
