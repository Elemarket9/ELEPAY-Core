import { describe, expect, it } from "vitest";
import { addCashLocally, newOfflineOrder, offlinePayload, reconcileQueued, type OfflineOrder, type OfflineStore } from "./offline-pos";

function make() {
  return newOfflineOrder({
    merchantId: "merchant1", branchId: null, terminalId: null, cashierId: null, customerId: null,
    orderType: "DINE_IN", tableId: "table1", totalPesewas: 3700,
    items: [{ variantId: null, itemName: "Jollof", quantity: 1, unitPricePesewas: 3700,
      discountPesewas: 0, taxPesewas: 0, modifiersJson: "[]", seatNumber: 1, course: "MAIN", notes: null }],
    discountPesewas: 0, taxPesewas: 0, tipPesewas: 0, payments: [],
  });
}
function memory(initial: OfflineOrder[]): OfflineStore {
  const rows = new Map(initial.map((o) => [o.offlineTxId, o]));
  return { put: async (o) => { rows.set(o.offlineTxId, o); },
    list: async (m) => [...rows.values()].filter((o) => o.merchantId === m) };
}
describe("durable POS reconciliation", () => {
  it("creates unique stable replay keys and no invented digital payment", () => {
    const first = make(), second = make();
    expect(first.offlineTxId).not.toBe(second.offlineTxId);
    expect(offlinePayload(first).idempotencyKey).toBe(first.idempotencyKey);
    expect(offlinePayload(first)).not.toHaveProperty("status");
    expect(first.payments).toEqual([]);
  });
  it("preserves cash as local collection until the server reconciles", () => {
    const cash = addCashLocally(make(), 1500);
    expect(cash.status).toBe("QUEUED");
    expect(cash.payments).toEqual([{ tenderType: "CASH", amountPesewas: 1500, reference: null }]);
    expect(() => addCashLocally(cash, 0)).toThrow();
  });
  it("keeps original IDs on retry after loss of network", async () => {
    const local = make(), store = memory([local]); let calls = 0;
    const first = await reconcileQueued(store, local.merchantId, async () => { calls++; throw new Error("offline"); });
    expect(first.remaining).toBe(1);
    const second = await reconcileQueued(store, local.merchantId, async (payload) => {
      calls++; expect(payload.offlineTxId).toBe(local.offlineTxId);
      expect(payload.idempotencyKey).toBe(local.idempotencyKey);
      return { status: "SYNCED", serverOrderId: "server1", message: "ok" };
    });
    expect(second.synced).toBe(1);
    expect(calls).toBe(2);
    expect((await store.list(local.merchantId))[0].serverOrderId).toBe("server1");
  });
  it("never silently discards conflicts or fabricates success", async () => {
    const local = make(), store = memory([local]);
    const outcome = await reconcileQueued(store, local.merchantId, async () => ({
      status: "REJECTED", serverOrderId: null, message: "Table already occupied",
    }));
    expect(outcome.conflicts).toBe(1);
    expect((await store.list(local.merchantId))[0].status).toBe("CONFLICT");
  });
});
