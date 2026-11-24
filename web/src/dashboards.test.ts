import { describe, expect, it } from "vitest";
import { checkoutMethodLabel, resolveInitialMethod } from "./Checkout";
import { validateWebhookInput } from "./Developer";
import { validateRefundInput } from "./Merchant";
import { upsertKnownCashier } from "./pos/PosTerminalBar";
import { filterOperationsByRange } from "./Wallet";

describe("Checkout helpers", () => {
  it("labels ghqr as GhQR and known methods accurately", () => {
    expect(checkoutMethodLabel("ghqr")).toBe("GhQR");
    expect(checkoutMethodLabel("momo")).toBe("Mobile money");
    expect(checkoutMethodLabel("card")).toBe("Card");
    expect(checkoutMethodLabel("wallet")).toBe("ELEPAY wallet");
    expect(checkoutMethodLabel("bank_transfer")).toBe("bank_transfer");
  });

  it("resolves initial method from available methods and falls back when missing", () => {
    expect(resolveInitialMethod([{ method: "ghqr" }, { method: "card" }], "")).toBe("ghqr");
    expect(resolveInitialMethod([{ method: "ghqr" }, { method: "card" }], "momo")).toBe("ghqr");
    expect(resolveInitialMethod([{ method: "ghqr" }, { method: "card" }], "card")).toBe("card");
    expect(resolveInitialMethod([], "")).toBe("momo");
  });
});

describe("Wallet statement range filtering", () => {
  const now = new Date("2027-04-15T12:00:00Z");
  const ops = [
    { id: "recent", createdAt: "2027-04-10T10:00:00Z" },
    { id: "forty_days", createdAt: "2027-03-05T10:00:00Z" },
    { id: "jan_two", createdAt: "2027-01-02T10:00:00Z" },
    { id: "prev_year", createdAt: "2026-12-20T10:00:00Z" },
    { id: "invalid", createdAt: "not-a-date" },
  ];

  it("returns all operations for 'all'", () => {
    expect(filterOperationsByRange(ops, "all", now)).toHaveLength(5);
  });

  it("filters 30d window", () => {
    expect(filterOperationsByRange(ops, "30d", now).map((o) => o.id)).toEqual(["recent"]);
  });

  it("filters 90d window", () => {
    expect(filterOperationsByRange(ops, "90d", now).map((o) => o.id)).toEqual(["recent", "forty_days"]);
  });

  it("filters ytd dynamically using now.getFullYear()", () => {
    expect(filterOperationsByRange(ops, "ytd", now).map((o) => o.id)).toEqual(["recent", "forty_days", "jan_two"]);
  });
});

describe("Developer webhook validation", () => {
  it("rejects missing, non-https, or invalid URLs and empty event selections", () => {
    expect(validateWebhookInput("", ["checkout.paid"])).toMatch(/required/i);
    expect(validateWebhookInput("http://example.com/hook", ["checkout.paid"])).toMatch(/https:\/\//i);
    expect(validateWebhookInput("not a url", ["checkout.paid"])).toMatch(/valid/i);
    expect(validateWebhookInput("https://api.myshop.com/webhooks", [])).toMatch(/event/i);
    expect(validateWebhookInput("https://api.myshop.com/webhooks", ["checkout.paid"])).toBeNull();
  });
});

describe("Merchant refund validation", () => {
  it("validates refund amount, max refundable cap, and reason", () => {
    expect(validateRefundInput("", "Damaged", 5000)).toEqual({ error: "Enter a valid refund amount, e.g. 10.00." });
    expect(validateRefundInput("0", "Damaged", 5000)).toEqual({ error: "Enter a valid refund amount, e.g. 10.00." });
    expect(validateRefundInput("60.00", "Damaged", 5000)).toHaveProperty("error");
    expect(validateRefundInput("25.00", "   ", 5000)).toEqual({ error: "Enter a refund reason." });
    expect(validateRefundInput("25.00", "Customer return", 5000)).toEqual({ pesewas: 2500 });
  });
});

describe("POS terminal cashier list helper", () => {
  it("adds new cashiers and replaces existing entries by id without duplicates", () => {
    const c1 = { id: "c1", merchantId: "m1", name: "Ama", badgeNumber: "B-001", isActive: true, createdAt: "2026-01-01T00:00:00Z" };
    const c2 = { id: "c2", merchantId: "m1", name: "Kojo", badgeNumber: "B-002", isActive: true, createdAt: "2026-01-02T00:00:00Z" };
    const c1Updated = { ...c1, name: "Ama Mensah" };

    const afterAdd = upsertKnownCashier([c2], c1);
    expect(afterAdd.map((c) => c.id)).toEqual(["c1", "c2"]);

    const afterUpdate = upsertKnownCashier(afterAdd, c1Updated);
    expect(afterUpdate).toHaveLength(2);
    expect(afterUpdate[0].name).toBe("Ama Mensah");
  });
});
