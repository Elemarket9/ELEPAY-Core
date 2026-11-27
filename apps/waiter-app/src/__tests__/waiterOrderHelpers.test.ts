import { describe, expect, it } from "vitest";
import {
  TERMINAL_WAITER_SUBMIT_ERROR_CODES,
  WaiterOrder,
  WaiterTable,
  buildWaiterSubmission,
  canAddToTable,
  canSelectTable,
  formatTableButtonLabel,
  normalizeStoredDraft,
  normalizeWaiterCourse,
} from "../waiterOrderHelpers";

const baseOrder: WaiterOrder = {
  orderId: "ord-1",
  ticketId: "tkt-1",
  tableNumber: "5",
  orderNumber: "POS-001",
  orderStatus: "PENDING",
  kitchenStatus: "COOKING",
  totalPesewas: 5000,
  billRequested: false,
};

describe("waiterOrderHelpers", () => {
  it("allows selecting AVAILABLE tables and waiter-owned OCCUPIED/BILL_PRINTED tables", () => {
    const available: WaiterTable = {
      id: "t-1",
      tableNumber: "1",
      capacity: 4,
      status: "AVAILABLE",
      currentOrderId: null,
      ownedByMe: false,
    };
    const ownedOccupied: WaiterTable = {
      id: "t-5",
      tableNumber: "5",
      capacity: 4,
      status: "OCCUPIED",
      currentOrderId: "ord-1",
      ownedByMe: true,
    };
    const ownedBillPrinted: WaiterTable = {
      id: "t-6",
      tableNumber: "6",
      capacity: 2,
      status: "BILL_PRINTED",
      currentOrderId: "ord-2",
      ownedByMe: true,
    };
    const otherWaiterOccupied: WaiterTable = {
      id: "t-7",
      tableNumber: "7",
      capacity: 4,
      status: "OCCUPIED",
      currentOrderId: "ord-other",
      ownedByMe: false,
    };
    const dirtyTable: WaiterTable = {
      id: "t-8",
      tableNumber: "8",
      capacity: 4,
      status: "DIRTY",
      currentOrderId: null,
      ownedByMe: false,
    };

    expect(canSelectTable(available, [baseOrder])).toBe(true);
    expect(canAddToTable(available, [baseOrder])).toBe(false);

    expect(canSelectTable(ownedOccupied, [baseOrder])).toBe(true);
    expect(canAddToTable(ownedOccupied, [baseOrder])).toBe(true);

    expect(canSelectTable(ownedBillPrinted, [baseOrder])).toBe(true);
    expect(canAddToTable(ownedBillPrinted, [baseOrder])).toBe(true);

    expect(canSelectTable(otherWaiterOccupied, [baseOrder])).toBe(false);
    expect(canAddToTable(otherWaiterOccupied, [baseOrder])).toBe(false);

    expect(canSelectTable(dirtyTable, [baseOrder])).toBe(false);
  });

  it("blocks adding to a waiter-owned table whose order is already PAID or VOIDED", () => {
    const ownedOccupied: WaiterTable = {
      id: "t-5",
      tableNumber: "5",
      capacity: 4,
      status: "OCCUPIED",
      currentOrderId: "ord-1",
      ownedByMe: true,
    };
    const paidOrder: WaiterOrder = { ...baseOrder, orderStatus: "PAID" };
    const partialOrder: WaiterOrder = { ...baseOrder, orderStatus: "PARTIAL" };

    expect(canAddToTable(ownedOccupied, [paidOrder])).toBe(false);
    expect(canSelectTable(ownedOccupied, [paidOrder])).toBe(false);

    expect(canAddToTable(ownedOccupied, [partialOrder])).toBe(true);
    expect(canSelectTable(ownedOccupied, [partialOrder])).toBe(true);
  });

  it("falls back to matching orders list when ownedByMe is omitted by server", () => {
    const legacyTable: WaiterTable = {
      id: "t-5",
      tableNumber: "5",
      capacity: 4,
      status: "OCCUPIED",
      currentOrderId: "ord-1",
    };
    expect(canAddToTable(legacyTable, [baseOrder])).toBe(true);
    expect(canAddToTable(legacyTable, [])).toBe(false);
  });

  it("formats table button labels with Add to Table for owned occupied tables", () => {
    const ownedOccupied: WaiterTable = {
      id: "t-5",
      tableNumber: "5",
      capacity: 4,
      status: "OCCUPIED",
      currentOrderId: "ord-1",
      ownedByMe: true,
    };
    const available: WaiterTable = {
      id: "t-1",
      tableNumber: "1",
      capacity: 4,
      status: "AVAILABLE",
      currentOrderId: null,
      ownedByMe: false,
    };

    expect(formatTableButtonLabel(ownedOccupied, "t-5", [baseOrder])).toBe(
      "✓ Add to Table 5 · OCCUPIED · 4 seats"
    );
    expect(formatTableButtonLabel(ownedOccupied, "", [baseOrder])).toBe(
      "Add to Table 5 · OCCUPIED · 4 seats"
    );
    expect(formatTableButtonLabel(available, "t-1", [baseOrder])).toBe(
      "✓ Table 1 · AVAILABLE · 4 seats"
    );
  });

  it("routes add-on drafts to /orders/{orderId}/items with Idempotency-Key and per-item course", () => {
    const prefix = "/v1/restaurant/waiter/m-1";
    const addOn = buildWaiterSubmission(prefix, {
      merchant: "m-1",
      tableId: "t-5",
      orderId: "ord-1",
      items: [
        { variantId: "v-1", quantity: 2, notes: "no ice", course: "BEVERAGE" },
        { variantId: "v-2", quantity: 1, notes: "", course: "DESSERT" },
      ],
      notes: "round 2",
      key: "idem-add-1",
    });

    expect(addOn.isAddOn).toBe(true);
    expect(addOn.path).toBe("/v1/restaurant/waiter/m-1/orders/ord-1/items");
    expect(addOn.idempotencyKey).toBe("idem-add-1");
    expect(addOn.body).toEqual({
      items: [
        { variantId: "v-1", quantity: 2, notes: "no ice", course: "BEVERAGE" },
        { variantId: "v-2", quantity: 1, notes: "", course: "DESSERT" },
      ],
      notes: "round 2",
    });

    const freshOrder = buildWaiterSubmission(prefix, {
      merchant: "m-1",
      tableId: "t-1",
      orderId: null,
      items: [{ variantId: "v-3", quantity: 1, notes: "", course: "APPETIZER" }],
      notes: "",
      key: "idem-new-1",
    });

    expect(freshOrder.isAddOn).toBe(false);
    expect(freshOrder.path).toBe("/v1/restaurant/waiter/m-1/orders");
    expect(freshOrder.idempotencyKey).toBe("idem-new-1");
    expect(freshOrder.body).toEqual({
      tableId: "t-1",
      items: [{ variantId: "v-3", quantity: 1, notes: "", course: "APPETIZER" }],
      notes: "",
    });
  });

  it("normalizes courses and recovers legacy stored drafts without course/orderId", () => {
    expect(normalizeWaiterCourse(undefined)).toBe("MAIN");
    expect(normalizeWaiterCourse("  side ")).toBe("SIDE");
    expect(normalizeWaiterCourse("UNKNOWN")).toBe("MAIN");

    const recovered = normalizeStoredDraft({
      merchant: "m-1",
      tableId: "t-1",
      items: [{ variantId: "v-1", quantity: 1, notes: "spicy" }],
      notes: "",
      key: "idem-legacy",
    });
    expect(recovered.orderId).toBeNull();
    expect(recovered.items[0].course).toBe("MAIN");
    expect(TERMINAL_WAITER_SUBMIT_ERROR_CODES.has("order_not_modifiable")).toBe(true);
    expect(TERMINAL_WAITER_SUBMIT_ERROR_CODES.has("invalid_course")).toBe(true);
  });
});
