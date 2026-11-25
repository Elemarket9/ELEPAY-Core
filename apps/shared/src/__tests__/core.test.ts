import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  buildPosOrderPayload,
  canonicalV2,
  computeCartTotals,
  DeviceCredential,
  ElepayClient,
  emptyKdsState,
  formatMoney,
  hmacSha256Hex,
  isTerminalStatusPaid,
  kdsApplySync,
  kdsBump,
  kdsCancel,
  kdsSettle,
  kdsVisible,
  normalizeBaseUrl,
  normalizeGhanaPhone,
  parseMoney,
  SeatSplitResult,
} from "../index";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("@elepay/mobile-core", () => {
  it("formats and parses GHS minor units accurately", () => {
    expect(formatMoney(0)).toBe("GH₵0.00");
    expect(formatMoney(125050)).toBe("GH₵1,250.50");
    expect(formatMoney(-450)).toBe("-GH₵4.50");
    expect(parseMoney("12.50")).toBe(1250);
    expect(parseMoney("1,000")).toBe(100000);
    expect(parseMoney("abc")).toBeNull();
  });

  it("normalizes Ghana mobile numbers to 233XXXXXXXXX and rejects 2330...", () => {
    expect(normalizeGhanaPhone("024 123 4567")).toBe("233241234567");
    expect(normalizeGhanaPhone("+233551112222")).toBe("233551112222");
    expect(normalizeGhanaPhone("12345")).toBeNull();
    expect(normalizeGhanaPhone("233012345678")).toBeNull();
    expect(normalizeGhanaPhone("0012345678")).toBeNull();
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

  it("recognizes SETTLED as terminal paid and aligns SeatSplitResult totalPesewas and buildPosOrderPayload", () => {
    expect(isTerminalStatusPaid("SETTLED")).toBe(true);
    expect(isTerminalStatusPaid("settled")).toBe(true);
    expect(isTerminalStatusPaid("PAID")).toBe(true);
    expect(isTerminalStatusPaid("PENDING")).toBe(false);

    const split: SeatSplitResult = {
      orderId: "ord-1",
      totalPesewas: 9000,
      seatGroups: [
        {
          seatNumber: 1,
          subtotalPesewas: 9000,
          items: [{ id: "item-1", itemName: "Jollof", quantity: 2, totalPesewas: 9000 }],
        },
      ],
    };
    expect(split.seatGroups[0].items[0].totalPesewas).toBe(9000);

    const payload = buildPosOrderPayload({
      merchantId: "m1",
      branchId: "b1",
      terminalId: "term-1",
      cashierId: "c1",
      orderType: "DINE_IN",
      lines: [{ variantId: "v1", label: "Jollof", unitPricePesewas: 4500, qty: 2, seatNumber: 1 }],
      discountPesewas: 0,
      taxPesewas: 0,
    });
    expect("tableId" in payload).toBe(false);
    expect("notes" in payload).toBe(false);
    expect(payload.items[0].seatNumber).toBe(1);
  });

  it("manages offline-first KDS sync, optimistic bump, server settle, and 4xx full-resync recovery", () => {
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
      cursor: 5,
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

    // Optimistic cancel rejected with 4xx -> kdsSettle(state, actionId, null) resets cursor to 0 & flags needsFullResync
    state = kdsCancel(state, "t1", "mistake", "act-2");
    expect(state.tickets.t1.ticket.status).toBe("CANCELLED");
    expect(kdsVisible(state, "grill")).toHaveLength(0);

    state = kdsSettle(state, "act-2", null);
    expect(state.queue).toHaveLength(0);
    expect(state.cursor).toBe(0);
    expect(state.needsFullResync).toBe(true);

    // Next sync from since=0 restores authoritative PREPARING state even with same changeSeq=2
    const resynced = kdsApplySync(state, {
      cursor: 5,
      hasMore: false,
      serverTime: "2026-11-24T12:01:00Z",
      tickets: [
        {
          ticket: { ...ticket, status: "PREPARING", changeSeq: 2 },
          items: [{ ...item, itemStatus: "PREPARING" }],
        },
      ],
      printerFailures: [],
    });
    expect(resynced.state.needsFullResync).toBe(false);
    expect(resynced.state.cursor).toBe(5);
    expect(resynced.state.tickets.t1.ticket.status).toBe("PREPARING");
    expect(kdsVisible(resynced.state, "grill")).toHaveLength(1);
  });

  it("paginated full resync advances cursor across pages, preserves page-1 and page-2 tickets, and clears needsFullResync", () => {
    const mkView = (id: string, num: number, status: "NEW" | "PREPARING", changeSeq: number) => ({
      ticket: {
        id,
        merchantId: "m1",
        orderId: `o-${id}`,
        tableId: null,
        diningMode: "DINE_IN",
        serverName: "Ama",
        status,
        course: "MAIN",
        notes: null,
        createdAt: "2026-11-24T12:00:00Z",
        startedAt: null,
        readyAt: null,
        servedAt: null,
        ticketNumber: num,
        clientRef: null,
        cancelledAt: null,
        changeSeq,
      },
      items: [
        {
          id: `i-${id}`,
          ticketId: id,
          orderItemId: `oi-${id}`,
          itemName: `Dish ${num}`,
          quantity: 1,
          modifiers: null,
          course: "MAIN",
          itemStatus: status,
          stationId: "grill",
        },
      ],
    });

    // Initial state has t1 at changeSeq=10
    let state = kdsApplySync(emptyKdsState(), {
      cursor: 10,
      hasMore: false,
      serverTime: "2026-11-24T12:00:10Z",
      tickets: [mkView("t1", 1, "NEW", 10)],
      printerFailures: [],
    }).state;

    // Optimistically bump t1, then receive 4xx rejection -> cursor resets to 0, needsFullResync=true
    state = kdsBump(state, "t1", "grill", "act-4xx");
    expect(state.tickets.t1.ticket.status).toBe("PREPARING");
    state = kdsSettle(state, "act-4xx", null);
    expect(state.cursor).toBe(0);
    expect(state.needsFullResync).toBe(true);
    expect(state.resyncSeenIds).toEqual([]);

    // Page 1 (since=0): returns t1 (reverted to NEW at changeSeq=10) with hasMore=true, cursor=10
    state = kdsApplySync(state, {
      cursor: 10,
      hasMore: true,
      serverTime: "2026-11-24T12:00:20Z",
      tickets: [mkView("t1", 1, "NEW", 10)],
      printerFailures: [],
    }).state;
    expect(state.cursor).toBe(10);
    expect(state.needsFullResync).toBe(true);
    expect(state.resyncSeenIds).toEqual(["t1"]);
    expect(state.tickets.t1.ticket.status).toBe("NEW");

    // Page 2 (since=10): returns t2 with hasMore=false, cursor=20
    state = kdsApplySync(state, {
      cursor: 20,
      hasMore: false,
      serverTime: "2026-11-24T12:00:25Z",
      tickets: [mkView("t2", 2, "NEW", 20)],
      printerFailures: [],
    }).state;
    expect(state.cursor).toBe(20);
    expect(state.needsFullResync).toBe(false);
    expect(state.resyncSeenIds).toBeUndefined();
    expect(Object.keys(state.tickets).sort()).toEqual(["t1", "t2"]);
    expect(state.tickets.t1.ticket.status).toBe("NEW");
    expect(state.tickets.t2.ticket.status).toBe("NEW");
  });

  it("evicts 4xx-rejected ghost ticket absent from full resync once hasMore completes while preserving queued tickets", () => {
    const mkView = (id: string, num: number, changeSeq: number) => ({
      ticket: {
        id,
        merchantId: "m1",
        orderId: `o-${id}`,
        tableId: null,
        diningMode: "DINE_IN",
        serverName: "Kofi",
        status: "NEW" as const,
        course: "MAIN",
        notes: null,
        createdAt: "2026-11-24T12:00:00Z",
        startedAt: null,
        readyAt: null,
        servedAt: null,
        ticketNumber: num,
        clientRef: null,
        cancelledAt: null,
        changeSeq,
      },
      items: [
        {
          id: `i-${id}`,
          ticketId: id,
          orderItemId: `oi-${id}`,
          itemName: `Dish ${num}`,
          quantity: 1,
          modifiers: null,
          course: "MAIN",
          itemStatus: "NEW" as const,
          stationId: "grill",
        },
      ],
    });

    let state = kdsApplySync(emptyKdsState(), {
      cursor: 5,
      hasMore: false,
      serverTime: "2026-11-24T12:00:05Z",
      tickets: [mkView("t-ghost", 1, 4), mkView("t-queued", 2, 5)],
      printerFailures: [],
    }).state;

    // Optimistically bump both tickets; t-ghost gets 404 (settled with null), t-queued still has pending action
    state = kdsBump(state, "t-ghost", "grill", "act-ghost");
    state = kdsBump(state, "t-queued", "grill", "act-queued");
    state = kdsSettle(state, "act-ghost", null);
    expect(state.needsFullResync).toBe(true);
    expect(state.cursor).toBe(0);

    // Full resync from since=0 returns empty tickets (t-ghost was deleted/archived on server)
    state = kdsApplySync(state, {
      cursor: 6,
      hasMore: false,
      serverTime: "2026-11-24T12:00:30Z",
      tickets: [],
      printerFailures: [],
    }).state;

    expect(state.needsFullResync).toBe(false);
    expect(state.resyncSeenIds).toBeUndefined();
    expect(state.tickets["t-ghost"]).toBeUndefined();
    expect(state.tickets["t-queued"]).toBeDefined();
    expect(kdsVisible(state, "grill").map((v) => v.ticket.id)).toEqual(["t-queued"]);
  });

  it("signs callAsKitchenDevice with uppercased method, Idempotency-Key, excluding baseUrl path prefix", async () => {
    const captured: { url: string; init: RequestInit }[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init: RequestInit) => {
        captured.push({ url, init });
        return new Response(JSON.stringify({ ok: true }), { status: 200 });
      }),
    );

    const client = new ElepayClient("https://api.elepay.gh/edge");
    const device: DeviceCredential = {
      apiKey: "kds_key_1",
      signingSecret: "super-secret",
      deviceId: "dev-1",
      merchantId: "m1",
      merchantName: "Osu Kitchen",
      stationId: null,
      stationName: null,
      hasPrinter: true,
    };

    await client.callAsKitchenDevice(device, "/v1/restaurant/kds/tickets/t1/advance?stationId=s1", {
      method: "post",
      idempotencyKey: "idem-123",
      body: { from: "NEW", actionId: "a1" },
    });

    expect(captured).toHaveLength(1);
    const { url, init } = captured[0];
    expect(url).toBe("https://api.elepay.gh/edge/v1/restaurant/kds/tickets/t1/advance?stationId=s1");
    expect(init.method).toBe("POST");
    const headers = init.headers as Record<string, string>;
    expect(headers["Idempotency-Key"]).toBe("idem-123");
    const expectedSig = hmacSha256Hex(
      device.signingSecret,
      canonicalV2([
        headers["X-Elepay-Timestamp"],
        "POST",
        // Server signs servletPath + pathInfo: the base-URL prefix (/edge) is not part of it.
        "/v1/restaurant/kds/tickets/t1/advance",
        "stationId=s1",
        headers["X-Elepay-Nonce"],
        "idem-123",
        JSON.stringify({ from: "NEW", actionId: "a1" }),
      ]),
    );
    expect(headers["X-Elepay-Signature"]).toBe(expectedSig);
  });

  it("falls back to HTTP status when statusText is empty and appends ProblemDetail errors[]", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn()
        .mockResolvedValueOnce(new Response("", { status: 401, statusText: "" }))
        .mockResolvedValueOnce(
          new Response(JSON.stringify({ code: "validation_error", detail: "Invalid request", errors: ["amount > 0", "phone invalid"] }), {
            status: 400,
            statusText: "",
          }),
        ),
    );

    const client = new ElepayClient("https://api.elepay.gh");
    await expect(client.call("/v1/pos/cashiers/auth", { method: "POST" })).rejects.toMatchObject({
      status: 401,
      message: "HTTP 401",
    });

    await expect(client.call("/v1/pos/orders", { method: "POST" })).rejects.toThrowError(
      new ApiError(400, "validation_error", "Invalid request: amount > 0; phone invalid"),
    );
  });

  it("normalizes trailing slashes from base URLs", () => {
    expect(normalizeBaseUrl("https://api.elepay.gh///")).toBe("https://api.elepay.gh");
  });

  it("createAttemptKeys reuses a key for the same attempt and rotates on change/reset", async () => {
    const { createAttemptKeys } = await import("../api");
    const keys = createAttemptKeys();
    const a = keys.keyFor("w:dest1:5000");
    expect(keys.keyFor("w:dest1:5000")).toBe(a);
    const b = keys.keyFor("w:dest1:6000");
    expect(b).not.toBe(a);
    keys.reset();
    expect(keys.keyFor("w:dest1:6000")).not.toBe(b);
  });

  it("signingPathAndQuery splits path and raw query without parsing absolute URLs", async () => {
    const { signingPathAndQuery } = await import("../api");
    expect(signingPathAndQuery("/v1/x?a=1&b=2")).toEqual({ pathname: "/v1/x", query: "a=1&b=2" });
    expect(signingPathAndQuery("v1/x")).toEqual({ pathname: "/v1/x", query: null });
    expect(signingPathAndQuery("/v1/x?")).toEqual({ pathname: "/v1/x", query: null });
  });
});
