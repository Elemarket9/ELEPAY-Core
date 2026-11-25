import { describe, expect, it } from "vitest";
import {
  canonicalV2,
  computeCartTotals,
  emptyKdsState,
  formatMoney,
  hmacSha256Hex,
  kdsApplySync,
  kdsBump,
  kdsSettle,
  kdsVisible,
  normalizeBaseUrl,
  normalizeGhanaPhone,
  parseMoney,
} from "../index";

describe("@elepay/mobile-core", () => {
  it("formats and parses GHS minor units accurately", () => {
    expect(formatMoney(0)).toBe("GH₵0.00");
    expect(formatMoney(125050)).toBe("GH₵1,250.50");
    expect(formatMoney(-450)).toBe("-GH₵4.50");
    expect(parseMoney("12.50")).toBe(1250);
    expect(parseMoney("1,000")).toBe(100000);
    expect(parseMoney("abc")).toBeNull();
  });

  it("normalizes Ghana mobile numbers to 233XXXXXXXXX", () => {
    expect(normalizeGhanaPhone("024 123 4567")).toBe("233241234567");
    expect(normalizeGhanaPhone("+233551112222")).toBe("233551112222");
    expect(normalizeGhanaPhone("12345")).toBeNull();
  });

  it("computes RFC 4231 test vector HMAC-SHA256 and ELEPAY canonical v2 payload", () => {
    // RFC 4231 Test Case 2: Key = "Jefe", Data = "what do ya want for nothing?"
    expect(hmacSha256Hex("Jefe", "what do ya want for nothing?")).toBe(
      "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
    );
    expect(canonicalV2(["1700000000", "POST", "/v1/restaurant/kds/tickets/1/advance", null, "n1", null, "{}"])).toBe(
      "elepay-request:v2:10:1700000000:4:POST:36:/v1/restaurant/kds/tickets/1/advance:0::2:n1:0::2:{}",
    );
  });

  it("computes POS cart totals with discount capping and tax rounding", () => {
    const totals = computeCartTotals(
      [
        { variantId: "v1", label: "Jollof Rice", unitPricePesewas: 4500, qty: 2 },
        { variantId: "v2", label: "Sobolo", unitPricePesewas: 1500, qty: 1 },
      ],
      500,
      15,
    );
    expect(totals).toEqual({
      subtotal: 10500,
      effectiveDiscount: 500,
      tax: 1500,
      total: 11500,
    });
  });

  it("manages offline-first KDS sync, optimistic bump, and server settle", () => {
    let state = emptyKdsState();
    const ticket = {
      id: "t1",
      merchantId: "m1",
      orderId: "o1",
      tableId: null,
      diningMode: "DINE_IN",
      serverName: "Ama",
      status: "NEW" as const,
      course: "MAIN",
      notes: "Extra spicy",
      createdAt: "2026-11-24T12:00:00Z",
      startedAt: null,
      readyAt: null,
      servedAt: null,
      ticketNumber: 1,
      clientRef: null,
      cancelledAt: null,
      changeSeq: 1,
    };
    const item = {
      id: "i1",
      ticketId: "t1",
      orderItemId: "oi1",
      itemName: "Grilled Tilapia",
      quantity: 1,
      modifiers: null,
      course: "MAIN",
      itemStatus: "NEW" as const,
      stationId: "grill",
    };

    const synced = kdsApplySync(state, {
      cursor: 1,
      hasMore: false,
      serverTime: "2026-11-24T12:00:05Z",
      tickets: [{ ticket, items: [item] }],
      printerFailures: [],
    });
    state = synced.state;
    expect(synced.arrived).toEqual(["t1"]);
    expect(kdsVisible(state, "grill")).toHaveLength(1);

    state = kdsBump(state, "t1", "grill", "act-1");
    expect(state.queue).toHaveLength(1);
    expect(state.tickets.t1.items[0].itemStatus).toBe("PREPARING");

    state = kdsSettle(state, "act-1", {
      applied: true,
      reason: null,
      view: {
        ticket: { ...ticket, status: "PREPARING", changeSeq: 2 },
        items: [{ ...item, itemStatus: "PREPARING" }],
      },
    });
    expect(state.queue).toHaveLength(0);
    expect(state.tickets.t1.ticket.status).toBe("PREPARING");
  });

  it("normalizes trailing slashes from base URLs", () => {
    expect(normalizeBaseUrl("https://api.elepay.gh///")).toBe("https://api.elepay.gh");
  });
});
