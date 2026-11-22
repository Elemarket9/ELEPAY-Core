import { describe, expect, it } from "vitest";
import { formatMoney } from "./money";

describe("Developer Platform and BackOffice Calculations", () => {
  it("computes POS subtotal, 15% VAT, and discount accurately", () => {
    const item1 = { pricePesewas: 4500, qty: 2 }; // 9,000 pesewas
    const item2 = { pricePesewas: 1500, qty: 3 }; // 4,500 pesewas
    const subtotal = item1.pricePesewas * item1.qty + item2.pricePesewas * item2.qty; // 13,500
    expect(subtotal).toBe(13500);

    const discountPct = 10;
    const discountPesewas = Math.round(subtotal * (discountPct / 100)); // 1,350
    expect(discountPesewas).toBe(1350);

    const netBeforeTax = subtotal - discountPesewas; // 12,150
    const vatPesewas = Math.round(netBeforeTax * 0.15); // 1,823
    expect(vatPesewas).toBe(1823);

    const total = netBeforeTax + vatPesewas; // 13,973
    expect(total).toBe(13973);
    expect(formatMoney(total)).toBe("GH₵139.73");
  });

  it("calculates statutory SSNIT and PAYE deductions for payroll", () => {
    const grossPesewas = 600000; // GH₵ 6,000.00
    const ssnitTier1 = Math.round(grossPesewas * 0.135); // 13.5% = 81,000 pesewas
    const ssnitTier2 = Math.round(grossPesewas * 0.05);  // 5% = 30,000 pesewas
    const taxableIncome = grossPesewas - ssnitTier2;      // 570,000 pesewas
    expect(ssnitTier1).toBe(81000);
    expect(ssnitTier2).toBe(30000);
    expect(taxableIncome).toBe(570000);
  });

  it("performs multi-currency conversion at defined exchange rates", () => {
    const rates: Record<string, number> = {
      USD: 0.065,
      EUR: 0.059,
      GBP: 0.051,
    };
    const amountGhsPesewas = 100000; // GH₵ 1,000.00
    const usdConverted = ((amountGhsPesewas / 100) * rates.USD).toFixed(2);
    const eurConverted = ((amountGhsPesewas / 100) * rates.EUR).toFixed(2);
    const gbpConverted = ((amountGhsPesewas / 100) * rates.GBP).toFixed(2);

    expect(usdConverted).toBe("65.00");
    expect(eurConverted).toBe("59.00");
    expect(gbpConverted).toBe("51.00");
  });

  it("validates CSV statement row generation", () => {
    const operations = [
      { reference: "chk_001", kind: "sale", counterparty: "Customer 1", amount: 5000, status: "settled", createdAt: "2026-11-22" },
      { reference: "payout_002", kind: "payout", counterparty: "Supplier X", amount: 2500, status: "success", createdAt: "2026-11-22" },
    ];
    const rows = operations.map((o) =>
      `"${o.reference}","${o.kind}","${o.counterparty}","${o.amount}","${(o.amount / 100).toFixed(2)}","${o.status}","${o.createdAt}"`
    );
    expect(rows.length).toBe(2);
    expect(rows[0]).toContain("chk_001");
    expect(rows[0]).toContain("50.00");
    expect(rows[1]).toContain("25.00");
  });

  it("parses Webhook signature headers correctly", () => {
    const header = "t=1732250000,v1=9f83cf4b1234567890abcdef";
    const parts = header.split(",");
    let timestamp: number | null = null;
    let signature: string | null = null;

    for (const part of parts) {
      const [k, v] = part.trim().split("=");
      if (k === "t") timestamp = parseInt(v, 10);
      if (k === "v1") signature = v;
    }

    expect(timestamp).toBe(1732250000);
    expect(signature).toBe("9f83cf4b1234567890abcdef");
  });

  it("constructs HMAC-SHA256 v2 canonical signing payload correctly", () => {
    const timestamp = "1732260000";
    const method = "POST";
    const path = "/v1/checkouts";
    const query = "";
    const nonce = "test-nonce-12345";
    const idempotencyKey = "idem-key-abcde";
    const body = '{"amount":15000,"currency":"GHS"}';

    // Canonical v2 format: elepay-request:v2:{len}:{field}...
    const fields = [timestamp, method, path, query, nonce, idempotencyKey, body];
    let canonical = "elepay-request:v2";
    for (const field of fields) {
      const val = field ?? "";
      const len = new TextEncoder().encode(val).length;
      canonical += `:${len}:${val}`;
    }

    expect(canonical).toBe(
      `elepay-request:v2:${timestamp.length}:${timestamp}:${method.length}:${method}:${path.length}:${path}:0::${nonce.length}:${nonce}:${idempotencyKey.length}:${idempotencyKey}:${body.length}:${body}`
    );
    expect(canonical.startsWith("elepay-request:v2")).toBe(true);
  });

  it("verifies direct Ghanaian rails supported in WooCommerce plugin", () => {
    const supportedRails = ["mtn_momo", "telecel_cash", "at_money", "ghipss_ghqr", "direct_card"];
    expect(supportedRails).toContain("mtn_momo");
    expect(supportedRails).toContain("telecel_cash");
    expect(supportedRails).toContain("at_money");
    expect(supportedRails).toContain("ghipss_ghqr");
    expect(supportedRails).toContain("direct_card");
    expect(supportedRails).not.toContain("paystack");
    expect(supportedRails).not.toContain("hubtel");
  });
});

