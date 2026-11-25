import { describe, expect, it } from "vitest";
import { registerTotals } from "../posHelpers";

const cart = { subtotal: 5000, effectiveDiscount: 0, tax: 750, total: 5750 };

describe("registerTotals", () => {
  it("uses the cart before an order exists", () => {
    expect(registerTotals(null, cart)).toEqual({ subtotal: 5000, discount: 0, tax: 750, paid: 0, due: 5750, fromOrder: false });
  });

  it("uses the saved order's own figures so a waiter order's tax and total add up", () => {
    const order = { id: "o", orderNumber: "ORD", subtotalPesewas: 4000, discountPesewas: 0, taxPesewas: 600, tipPesewas: 0,
      totalPesewas: 4600, paidPesewas: 0, changePesewas: 0, status: "PENDING" };
    const t = registerTotals(order, cart);
    expect(t).toMatchObject({ subtotal: 4000, tax: 600, due: 4600, fromOrder: true });
    expect(t.subtotal - t.discount + t.tax).toBe(order.totalPesewas);
  });

  it("shows the balance due after a part payment", () => {
    const order = { id: "o", orderNumber: "ORD", subtotalPesewas: 4000, taxPesewas: 600, totalPesewas: 4600,
      paidPesewas: 1000, changePesewas: 0, status: "PARTIAL" };
    expect(registerTotals(order, cart)).toMatchObject({ paid: 1000, due: 3600 });
  });
});
