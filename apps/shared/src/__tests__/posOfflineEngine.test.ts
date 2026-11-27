import { describe, expect, it, vi } from "vitest";
import {
  ApiError,
  buildKdsTicketPayload,
  buildPosOrderPayload,
  cartLineKey,
  CatalogRow,
  computeNextKdsRound,
  DiningTable,
  emptyPosOfflineState,
  filterUnfiredOrderItems,
  isOfflineNetworkError,
  isPermanentSyncRejection,
  isTenderAllowedOffline,
  kdsRoundClientRef,
  OfflineOrderPayload,
  OrderItemKdsMeta,
  posCacheBootstrap,
  posEnqueueOfflineCashOrder,
  posMarkQueueAttemptError,
  posOfflineBackoff,
  posOfflineStorageKey,
  PosOfflineSyncResult,
  PosOrderItem,
  posRetryFailedOrder,
  posSettleOfflineOrder,
  PosTerminal,
  replayOfflineQueue,
} from "../index";

const sampleCatalog: CatalogRow[] = [
  {
    variantId: "var-jollof",
    productId: "prod-1",
    productName: "Jollof Rice",
    categoryId: "cat-1",
    categoryName: "Mains",
    sku: "JOL-01",
    barcode: null,
    variantName: "Standard",
    costPricePesewas: 2000,
    retailPricePesewas: 4500,
    trackInventory: true,
    onHand: 25,
    reorderPoint: 5,
  },
  {
    variantId: "var-sobolo",
    productId: "prod-2",
    productName: "Sobolo",
    categoryId: "cat-2",
    categoryName: "Drinks",
    sku: "SOB-01",
    barcode: null,
    variantName: "500ml",
    costPricePesewas: 500,
    retailPricePesewas: 1500,
    trackInventory: false,
    onHand: 0,
    reorderPoint: 0,
  },
];

const sampleTables: DiningTable[] = [
  {
    id: "tbl-1",
    sectionId: "sec-1",
    tableNumber: "1",
    capacity: 4,
    status: "AVAILABLE",
    currentOrderId: null,
    branchId: "br-1",
  },
  {
    id: "tbl-2",
    sectionId: "sec-1",
    tableNumber: "2",
    capacity: 6,
    status: "BILL_PRINTED",
    currentOrderId: "ord-99",
    branchId: "br-1",
  },
];

const sampleTerminals: PosTerminal[] = [
  {
    id: "term-1",
    merchantId: "m-1",
    branchId: "br-1",
    serialNumber: "POS-ACCRA-01",
    model: "Sunmi T2",
    status: "ACTIVE",
  },
];

describe("posOfflineEngine (Phase 8 POS Offline Queue + Phases 2 & 5 helpers)", () => {
  it("initializes empty state and builds merchant-scoped storage key", () => {
    const state = emptyPosOfflineState();
    expect(state).toEqual({
      catalog: [],
      tables: [],
      terminals: [],
      catalogCachedAt: null,
      queue: [],
      failed: [],
      history: [],
      lastSyncAt: null,
    });
    expect(posOfflineStorageKey("merch-123")).toBe("elepay.pos.offline.merch-123");
  });

  it("caches catalog, tables, and terminals with timestamp while preserving omitted fields", () => {
    let state = emptyPosOfflineState();
    state = posCacheBootstrap(
      state,
      { catalog: sampleCatalog, tables: sampleTables, terminals: sampleTerminals },
      1_700_000_000_000,
    );
    expect(state.catalog).toHaveLength(2);
    expect(state.tables).toHaveLength(2);
    expect(state.tables[1].status).toBe("BILL_PRINTED");
    expect(state.terminals).toHaveLength(1);
    expect(state.catalogCachedAt).toBe(1_700_000_000_000);

    // Updating catalog only preserves cached tables and terminals
    const updated = posCacheBootstrap(state, { catalog: [sampleCatalog[0]] }, 1_700_000_005_000);
    expect(updated.catalog).toHaveLength(1);
    expect(updated.tables).toHaveLength(2);
    expect(updated.terminals).toHaveLength(1);
    expect(updated.catalogCachedAt).toBe(1_700_000_005_000);
  });

  it("allows ONLY CASH offline and blocks MoMo, Card, GhQR, and Bank Transfer", () => {
    expect(isTenderAllowedOffline("CASH")).toBe(true);
    expect(isTenderAllowedOffline("cash")).toBe(true);
    expect(isTenderAllowedOffline("  Cash  ")).toBe(true);
    expect(isTenderAllowedOffline("MOMO")).toBe(false);
    expect(isTenderAllowedOffline("CARD")).toBe(false);
    expect(isTenderAllowedOffline("GHQR")).toBe(false);
    expect(isTenderAllowedOffline("BANK_TRANSFER")).toBe(false);
    expect(isTenderAllowedOffline("")).toBe(false);
  });

  it("classifies network errors vs permanent 4xx rejections accurately", () => {
    expect(isOfflineNetworkError(new TypeError("Network request failed"))).toBe(true);
    expect(isOfflineNetworkError(new ApiError(503, "unavailable", "Service unavailable"))).toBe(true);
    expect(isOfflineNetworkError(new ApiError(502, "bad_gateway", "Bad gateway"))).toBe(true);
    expect(isOfflineNetworkError(new ApiError(504, "timeout", "Gateway timeout"))).toBe(true);
    expect(isOfflineNetworkError(new ApiError(400, "bad_request", "Invalid payload"))).toBe(false);
    expect(isOfflineNetworkError(new ApiError(401, "unauthorized", "Expired token"))).toBe(false);

    expect(isPermanentSyncRejection(new ApiError(400, "validation_error", "Bad input"))).toBe(true);
    expect(isPermanentSyncRejection(new ApiError(404, "not_found", "Unknown variant"))).toBe(true);
    expect(isPermanentSyncRejection(new ApiError(409, "idempotency_conflict", "Hash mismatch"))).toBe(true);
    expect(isPermanentSyncRejection(new ApiError(422, "unprocessable", "Unprocessable"))).toBe(true);
    expect(isPermanentSyncRejection(new ApiError(401, "unauthorized", "Auth required"))).toBe(false);
    expect(isPermanentSyncRejection(new ApiError(429, "rate_limited", "Too many requests"))).toBe(false);
    expect(isPermanentSyncRejection(new ApiError(500, "internal", "Server error"))).toBe(false);
    expect(isPermanentSyncRejection(new Error("offline"))).toBe(false);
  });

  it("enqueues valid offline cash orders matching PosOfflineSyncService contract and deduplicates keys", () => {
    let state = emptyPosOfflineState();
    const res = posEnqueueOfflineCashOrder(
      state,
      {
        offlineTxId: "off-tx-1",
        idempotencyKey: "idem-1",
        merchantId: "m-1",
        branchId: "br-1",
        terminalId: "term-1",
        cashierId: "cash-1",
        lines: [
          { variantId: "var-jollof", label: "Jollof Rice", unitPricePesewas: 4500, qty: 2, seatNumber: 1, course: "main" },
          { variantId: "var-sobolo", label: "Sobolo (500ml)", unitPricePesewas: 1500, qty: 1, seatNumber: 2, course: "drink" },
        ],
        discountPesewas: 500,
        taxPesewas: 1000,
        cashReceivedPesewas: 12000,
      },
      1_700_000_010_000,
    );

    state = res.state;
    expect(state.queue).toHaveLength(1);
    expect(res.changePesewas).toBe(1000); // 9000 + 1500 - 500 + 1000 = 11000; 12000 - 11000 = 1000
    expect(res.queued.payload).toEqual({
      offlineTxId: "off-tx-1",
      idempotencyKey: "idem-1",
      merchantId: "m-1",
      branchId: "br-1",
      terminalId: "term-1",
      cashierId: "cash-1",
      customerId: null,
      items: [
        {
          variantId: "var-jollof",
          itemName: "Jollof Rice",
          quantity: 2,
          unitPricePesewas: 4500,
          discountPesewas: 0,
          taxPesewas: 0,
          modifiersJson: null,
          seatNumber: 1,
          course: "MAIN",
          notes: null,
        },
        {
          variantId: "var-sobolo",
          itemName: "Sobolo (500ml)",
          quantity: 1,
          unitPricePesewas: 1500,
          discountPesewas: 0,
          taxPesewas: 0,
          modifiersJson: null,
          seatNumber: 2,
          course: "DRINK",
          notes: null,
        },
      ],
      discountPesewas: 500,
      taxPesewas: 1000,
      tipPesewas: 0,
      totalPesewas: 11000,
      payments: [
        {
          tenderType: "CASH",
          amountPesewas: 12000,
          reference: "OFFLINE:off-tx-1",
        },
      ],
      conflictStrategy: "SERVER_ADJUSTED",
    });

    // Re-enqueuing with same idempotencyKey or offlineTxId is a no-op
    const dupByKey = posEnqueueOfflineCashOrder(state, {
      offlineTxId: "off-tx-different",
      idempotencyKey: "idem-1",
      merchantId: "m-1",
      branchId: "br-1",
      terminalId: "term-1",
      cashierId: "cash-1",
      lines: [{ variantId: "var-jollof", label: "Jollof Rice", unitPricePesewas: 4500, qty: 1 }],
      discountPesewas: 0,
      taxPesewas: 0,
      cashReceivedPesewas: 5000,
    });
    expect(dupByKey.state.queue).toHaveLength(1);
    expect(dupByKey.queued.payload.offlineTxId).toBe("off-tx-1");

    const dupByTx = posEnqueueOfflineCashOrder(state, {
      offlineTxId: "off-tx-1",
      idempotencyKey: "idem-different",
      merchantId: "m-1",
      branchId: "br-1",
      terminalId: "term-1",
      cashierId: "cash-1",
      lines: [{ variantId: "var-jollof", label: "Jollof Rice", unitPricePesewas: 4500, qty: 1 }],
      discountPesewas: 0,
      taxPesewas: 0,
      cashReceivedPesewas: 5000,
    });
    expect(dupByTx.state.queue).toHaveLength(1);
  });

  it("rejects offline enqueue when terminalId is missing, cart is empty, or cash is insufficient", () => {
    const state = emptyPosOfflineState();
    const base = {
      offlineTxId: "off-1",
      idempotencyKey: "idem-1",
      merchantId: "m-1",
      branchId: "br-1",
      terminalId: "term-1",
      cashierId: "cash-1",
      lines: [{ variantId: "var-jollof", label: "Jollof Rice", unitPricePesewas: 4500, qty: 1 }],
      discountPesewas: 0,
      taxPesewas: 0,
      cashReceivedPesewas: 4500,
    };

    expect(() => posEnqueueOfflineCashOrder(state, { ...base, terminalId: "" })).toThrow(/terminal/i);
    expect(() => posEnqueueOfflineCashOrder(state, { ...base, lines: [] })).toThrow(/empty cart/i);
    expect(() => posEnqueueOfflineCashOrder(state, { ...base, cashReceivedPesewas: 4000 })).toThrow(/Cash received/i);
    expect(() =>
      posEnqueueOfflineCashOrder(state, {
        ...base,
        lines: [{ variantId: "var-jollof", label: "Jollof Rice", unitPricePesewas: 4500, qty: 0 }],
      }),
    ).toThrow(/Invalid quantity/i);
  });

  it("settles synced orders into history, moves permanent failures into failed, and supports retry", () => {
    let state = emptyPosOfflineState();
    state = posEnqueueOfflineCashOrder(state, {
      offlineTxId: "tx-1",
      idempotencyKey: "k-1",
      merchantId: "m-1",
      branchId: "br-1",
      terminalId: "term-1",
      cashierId: "c-1",
      lines: [{ variantId: "var-jollof", label: "Jollof Rice", unitPricePesewas: 4500, qty: 1 }],
      discountPesewas: 0,
      taxPesewas: 0,
      cashReceivedPesewas: 5000,
    }).state;
    state = posEnqueueOfflineCashOrder(state, {
      offlineTxId: "tx-2",
      idempotencyKey: "k-2",
      merchantId: "m-1",
      branchId: "br-1",
      terminalId: "term-1",
      cashierId: "c-1",
      lines: [{ variantId: "var-sobolo", label: "Sobolo", unitPricePesewas: 1500, qty: 1 }],
      discountPesewas: 0,
      taxPesewas: 0,
      cashReceivedPesewas: 2000,
    }).state;

    // Mark transient error on k-1
    state = posMarkQueueAttemptError(state, "k-1", "Network timeout");
    expect(state.queue[0].attempts).toBe(1);
    expect(state.queue[0].lastError).toBe("Network timeout");

    // Settle k-1 as synced
    state = posSettleOfflineOrder(
      state,
      "k-1",
      {
        offlineTxId: "tx-1",
        idempotencyKey: "k-1",
        status: "SYNCED",
        serverOrderId: "srv-ord-1",
        resolutionApplied: null,
        message: "Order synced cleanly",
      },
      1_700_000_020_000,
    );
    expect(state.queue).toHaveLength(1);
    expect(state.history).toHaveLength(1);
    expect(state.history[0].serverOrderId).toBe("srv-ord-1");
    expect(state.lastSyncAt).toBe(1_700_000_020_000);

    // Permanently reject k-2 (null result) -> moves to failed
    state = posSettleOfflineOrder(state, "k-2", null, 1_700_000_030_000);
    expect(state.queue).toHaveLength(0);
    expect(state.failed).toHaveLength(1);
    expect(state.failed[0].payload.idempotencyKey).toBe("k-2");

    // Retry moves failed entry back to queue with reset attempts
    state = posRetryFailedOrder(state, "k-2");
    expect(state.failed).toHaveLength(0);
    expect(state.queue).toHaveLength(1);
    expect(state.queue[0].attempts).toBe(0);
    expect(state.queue[0].lastError).toBeNull();
  });

  it("scales offline backoff exponentially and caps at 30 seconds", () => {
    expect(posOfflineBackoff(0)).toBe(5000);
    expect(posOfflineBackoff(1)).toBe(10000);
    expect(posOfflineBackoff(2)).toBe(20000);
    expect(posOfflineBackoff(3)).toBe(30000);
    expect(posOfflineBackoff(10)).toBe(30000);
  });

  it("replays offline queue FIFO on reconnect, isolates permanent 4xx, and stops on transient network failure", async () => {
    let state = emptyPosOfflineState();
    for (const n of [1, 2, 3]) {
      state = posEnqueueOfflineCashOrder(state, {
        offlineTxId: `tx-${n}`,
        idempotencyKey: `idem-${n}`,
        merchantId: "m-1",
        branchId: "br-1",
        terminalId: "term-1",
        cashierId: "c-1",
        lines: [{ variantId: "var-jollof", label: "Jollof Rice", unitPricePesewas: 4500, qty: n }],
        discountPesewas: 0,
        taxPesewas: 0,
        cashReceivedPesewas: 4500 * n,
      }).state;
    }

    const sentKeys: string[] = [];
    const sendFn = vi.fn(async (payload: OfflineOrderPayload): Promise<PosOfflineSyncResult> => {
      sentKeys.push(payload.idempotencyKey);
      if (payload.idempotencyKey === "idem-1") {
        // Permanent 400 rejection -> should move to failed and NOT block idem-2
        throw new ApiError(400, "variant_not_found", "Variant deleted");
      }
      if (payload.idempotencyKey === "idem-2") {
        return {
          offlineTxId: payload.offlineTxId,
          idempotencyKey: payload.idempotencyKey,
          status: "SYNCED",
          serverOrderId: "srv-2",
          resolutionApplied: null,
          message: "Synced",
        };
      }
      // Transient 503 on idem-3 -> should stop replay and keep idem-3 at queue head
      throw new ApiError(503, "service_unavailable", "Temporary outage");
    });

    const snapshots: number[] = [];
    const firstPass = await replayOfflineQueue(
      state,
      sendFn,
      (next) => snapshots.push(next.queue.length),
      () => 1_700_000_050_000,
    );

    expect(sentKeys).toEqual(["idem-1", "idem-2", "idem-3"]);
    expect(firstPass.failed).toBe(1);
    expect(firstPass.synced).toBe(1);
    expect(firstPass.stoppedByError).toBeInstanceOf(ApiError);
    expect(firstPass.state.failed.map((f) => f.payload.idempotencyKey)).toEqual(["idem-1"]);
    expect(firstPass.state.history.map((h) => h.idempotencyKey)).toEqual(["idem-2"]);
    expect(firstPass.state.queue).toHaveLength(1);
    expect(firstPass.state.queue[0].payload.idempotencyKey).toBe("idem-3");
    expect(firstPass.state.queue[0].attempts).toBe(1);
    expect(snapshots).toEqual([2, 1, 1]);

    // Second pass after reconnect succeeds and drains idem-3 with same idempotencyKey
    const secondPass = await replayOfflineQueue(
      firstPass.state,
      async (payload) => ({
        offlineTxId: payload.offlineTxId,
        idempotencyKey: payload.idempotencyKey,
        status: "SYNCED",
        serverOrderId: "srv-3",
        resolutionApplied: null,
        message: "Synced after reconnect",
      }),
      undefined,
      () => 1_700_000_060_000,
    );

    expect(secondPass.stoppedByError).toBeNull();
    expect(secondPass.synced).toBe(1);
    expect(secondPass.state.queue).toHaveLength(0);
    expect(secondPass.state.history.map((h) => h.idempotencyKey)).toEqual(["idem-3", "idem-2"]);
  });

  it("Phase 2 helpers: filters unfired items only and computes multi-round KDS clientRef pos:{id}:r{round}", () => {
    const items: (PosOrderItem & OrderItemKdsMeta)[] = [
      {
        id: "oi-1",
        orderId: "ord-1",
        variantId: "var-jollof",
        itemName: "Jollof Rice",
        quantity: 1,
        unitPricePesewas: 4500,
        discountPesewas: 0,
        taxPesewas: 0,
        totalPesewas: 4500,
        modifiersJson: null,
        seatNumber: 1,
        course: "MAIN",
        notes: null,
        kdsRound: 1,
        sentAt: "2026-11-27T12:00:00Z",
      },
      {
        id: "oi-2",
        orderId: "ord-1",
        variantId: "var-sobolo",
        itemName: "Sobolo",
        quantity: 2,
        unitPricePesewas: 1500,
        discountPesewas: 0,
        taxPesewas: 0,
        totalPesewas: 3000,
        modifiersJson: null,
        seatNumber: 2,
        course: "DRINK",
        notes: null,
        kdsRound: null,
        sentAt: null,
      },
      {
        id: "oi-3",
        orderId: "ord-1",
        variantId: "var-void",
        itemName: "Cancelled Soup",
        quantity: 1,
        unitPricePesewas: 2000,
        discountPesewas: 0,
        taxPesewas: 0,
        totalPesewas: 2000,
        modifiersJson: null,
        seatNumber: 1,
        course: "STARTER",
        notes: null,
        kdsRound: null,
        sentAt: null,
        voided: true,
      },
    ];

    const unfired = filterUnfiredOrderItems(items);
    expect(unfired.map((i) => i.id)).toEqual(["oi-2"]);

    const nextRound = computeNextKdsRound(items);
    expect(nextRound).toBe(2);
    expect(kdsRoundClientRef("ord-1", nextRound)).toBe("pos:ord-1:r2");

    const kdsPayload = buildKdsTicketPayload({
      merchantId: "m-1",
      orderId: "ord-1",
      tableId: "tbl-1",
      orderType: "DINE_IN",
      serverName: "Ama",
      orderItems: unfired,
      clientRef: kdsRoundClientRef("ord-1", nextRound),
    });
    expect(kdsPayload.clientRef).toBe("pos:ord-1:r2");
    expect(kdsPayload.items).toHaveLength(1);
    expect(kdsPayload.items[0].orderItemId).toBe("oi-2");
    expect(kdsPayload.course).toBe("DRINK");

    // Local fired IDs guard prevents duplicate firing even if server hasn't populated sentAt yet
    expect(filterUnfiredOrderItems(items, new Set(["oi-2"]))).toEqual([]);
  });

  it("Phase 5 helpers: distinguishes same variant across seats and preserves per-item seatNumber", () => {
    expect(cartLineKey("var-jollof", null)).toBe("var-jollof::0");
    expect(cartLineKey("var-jollof", 1)).toBe("var-jollof::1");
    expect(cartLineKey("var-jollof", 2)).toBe("var-jollof::2");
    expect(cartLineKey("var-jollof", 1)).not.toBe(cartLineKey("var-jollof", 2));

    const payload = buildPosOrderPayload({
      merchantId: "m-1",
      branchId: "br-1",
      terminalId: "term-1",
      cashierId: "c-1",
      orderType: "DINE_IN",
      lines: [
        { variantId: "var-jollof", label: "Jollof Rice", unitPricePesewas: 4500, qty: 1, seatNumber: 1 },
        { variantId: "var-jollof", label: "Jollof Rice", unitPricePesewas: 4500, qty: 2, seatNumber: 2 },
      ],
      discountPesewas: 0,
      taxPesewas: 0,
    });
    expect(payload.items).toHaveLength(2);
    expect(payload.items[0].seatNumber).toBe(1);
    expect(payload.items[1].seatNumber).toBe(2);
  });
});
