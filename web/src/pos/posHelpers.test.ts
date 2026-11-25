import { describe, expect, it } from "vitest";
import { ApiError } from "../api";
import {
  buildPosOrderPayload,
  computeCartTotals,
  formatKdsStatusLabel,
  isFeatureNotEnabled,
  isPollStatusFailed,
  isTerminalStatusFailed,
  isTerminalStatusPaid,
  normalizeNfcTapResult,
  normalizeTenderResult,
  parseHeldCartPayload,
  type SeatSplitGroup,
} from "./posHelpers";

describe("POS & Terminal Helpers", () => {
  it("detects Stage 1 FEATURE_NOT_ENABLED ApiError", () => {
    expect(isFeatureNotEnabled(new ApiError(404, "FEATURE_NOT_ENABLED", "This module is disabled in Stage 1"))).toBe(true);
    expect(isFeatureNotEnabled(new ApiError(404, "not_found", "Not found"))).toBe(false);
    expect(isFeatureNotEnabled(new Error("FEATURE_NOT_ENABLED"))).toBe(false);
  });

  it("computes integer minor-unit cart totals with discount clamping and tax", () => {
    const totals = computeCartTotals(
      [
        { unitPricePesewas: 1500, qty: 2 },
        { unitPricePesewas: 500, qty: 1 },
      ],
      1000,
      15,
    );
    expect(totals.subtotal).toBe(3500);
    expect(totals.effectiveDiscount).toBe(1000);
    expect(totals.tax).toBe(375); // 15% of 2500
    expect(totals.total).toBe(2875);

    const clamped = computeCartTotals([{ unitPricePesewas: 500, qty: 1 }], 9000, 0);
    expect(clamped.effectiveDiscount).toBe(500);
    expect(clamped.total).toBe(0);
  });

  it("builds POS order payload with terminalId, cashierId, orderType, and seatNumber without dead tableId", () => {
    const payload = buildPosOrderPayload({
      merchantId: "m-1",
      branchId: "b-1",
      terminalId: "term-123",
      cashierId: "cash-456",
      orderType: "DINE_IN",
      lines: [
        { variantId: "v-1", label: "Jollof Rice", unitPricePesewas: 4500, qty: 2, seatNumber: 3 },
      ],
      discountPesewas: 0,
      taxPesewas: 0,
    });

    expect(payload.terminalId).toBe("term-123");
    expect(payload.cashierId).toBe("cash-456");
    expect(payload.orderType).toBe("DINE_IN");
    expect("tableId" in payload).toBe(false);
    expect(payload.items[0].seatNumber).toBe(3);

    const retailPayload = buildPosOrderPayload({
      merchantId: "m-1",
      terminalId: "term-123",
      cashierId: "cash-456",
      orderType: "RETAIL",
      lines: [
        { variantId: "v-1", label: "Water", unitPricePesewas: 500, qty: 1, seatNumber: 2 },
      ],
      discountPesewas: 0,
      taxPesewas: 0,
    });
    expect("tableId" in retailPayload).toBe(false);
    expect(retailPayload.items[0].seatNumber).toBeNull();
  });

  it("classifies terminal payment statuses (including SETTLED), rail failure polling, and KDS labels", () => {
    expect(isTerminalStatusPaid("paid")).toBe(true);
    expect(isTerminalStatusPaid("SUCCEEDED")).toBe(true);
    expect(isTerminalStatusPaid("SETTLED")).toBe(true);
    expect(isTerminalStatusPaid("settled")).toBe(true);
    expect(isTerminalStatusPaid("pending")).toBe(false);

    expect(isTerminalStatusFailed("DECLINED")).toBe(true);
    expect(isTerminalStatusFailed("failed")).toBe(true);
    expect(isTerminalStatusFailed("processing")).toBe(false);

    // Rail failure detection when checkout resets to "open" with railStatus: "failed"
    expect(isPollStatusFailed({ status: "open", railStatus: "failed" })).toBe(true);
    expect(isPollStatusFailed({ status: "open", railStatus: "DECLINED" })).toBe(true);
    expect(isPollStatusFailed({ status: "expired", railStatus: null })).toBe(true);
    expect(isPollStatusFailed({ status: "processing", railStatus: "pending" })).toBe(false);
    expect(isPollStatusFailed({ status: "open", railStatus: null })).toBe(false);

    expect(formatKdsStatusLabel("NEW")).toBe("Kitchen: Queued");
    expect(formatKdsStatusLabel("IN_PROGRESS")).toBe("Kitchen: Preparing");
    expect(formatKdsStatusLabel("READY")).toBe("Kitchen: Ready");
    expect(formatKdsStatusLabel(null)).toBe("Not sent");
  });

  it("types SeatSplitGroup items with totalPesewas matching backend PosOrderItem", () => {
    const group: SeatSplitGroup = {
      seatNumber: 1,
      subtotalPesewas: 9000,
      items: [{ id: "oi-1", itemName: "Jollof Rice", quantity: 2, totalPesewas: 9000 }],
    };
    expect(group.items[0].totalPesewas).toBe(9000);
  });

  it("parses held cart payloads safely and rejects malformed JSON", () => {
    const valid = parseHeldCartPayload(
      JSON.stringify({
        items: [{ variantId: "v-1", label: "Tea", unitPricePesewas: 1000, qty: 1, seatNumber: null }],
        discount: "2.00",
        taxPercent: "12.5",
        orderType: "TAKEAWAY",
        tableId: null,
      }),
    );
    expect(valid?.orderType).toBe("TAKEAWAY");
    expect(valid?.items).toHaveLength(1);

    expect(parseHeldCartPayload("not json")).toBeNull();
    expect(parseHeldCartPayload("{}")).toBeNull();
  });

  it("normalizes backend TenderResult fields (checkoutRef, paymentStatus, orderStatus, qrPayload, qrReference) with legacy fallbacks", () => {
    const momo = normalizeTenderResult({
      orderId: "ord-1",
      orderStatus: "OPEN",
      tenderType: "MOMO",
      amountPesewas: 5000,
      linkId: "link-1",
      checkoutRef: "chk_live_123",
      attemptId: "att-1",
      paymentStatus: "pending",
      payUrl: "https://pay.elepay.gh/chk_live_123",
      qrPayload: null,
      qrReference: null,
      prompt: "Approve the payment on your phone",
      order: null,
    });
    expect(momo.checkoutRef).toBe("chk_live_123");
    expect(momo.paymentStatus).toBe("pending");
    expect(momo.orderStatus).toBe("OPEN");
    expect(momo.prompt).toBe("Approve the payment on your phone");

    const ghqr = normalizeTenderResult({
      orderId: "ord-2",
      orderStatus: "OPEN",
      tenderType: "GHQR",
      amountPesewas: 12000,
      checkoutRef: "chk_qr_456",
      paymentStatus: "open",
      payUrl: "https://pay.elepay.gh/chk_qr_456",
      qrPayload: "000201010212...",
      qrReference: "GHQR-REF-789",
      prompt: "Scan GhQR code to pay",
    });
    expect(ghqr.checkoutRef).toBe("chk_qr_456");
    expect(ghqr.paymentStatus).toBe("open");
    expect(ghqr.orderStatus).toBe("OPEN");
    expect(ghqr.qrPayload).toBe("000201010212...");
    expect(ghqr.qrReference).toBe("GHQR-REF-789");

    const legacyFallback = normalizeTenderResult({
      tenderType: "MOMO",
      amountPesewas: 1000,
      checkoutReference: "chk_legacy_1",
      reference: "ref_legacy_1",
      status: "PAID",
      message: "Legacy paid",
    });
    expect(legacyFallback.checkoutRef).toBe("chk_legacy_1");
    expect(legacyFallback.paymentStatus).toBe("PAID");
    expect(legacyFallback.orderStatus).toBe("PAID");
    expect(legacyFallback.prompt).toBe("Legacy paid");
  });

  it("normalizes backend NfcTapResult fields (checkoutRef, authCode) with legacy fallbacks", () => {
    const nfc = normalizeNfcTapResult({
      authorizationId: "auth-1",
      orderId: "ord-3",
      terminalId: "term-1",
      ksn: "FFFF9876543210E00001",
      ksnCounter: 1,
      cardScheme: "VISA",
      cvmMethod: "NO_CVM",
      authCode: "NFC1A2B3C",
      checkoutRef: "chk_nfc_999",
      payUrl: "https://pay.elepay.gh/chk_nfc_999",
      amountPesewas: 7500,
      status: "PENDING_SETTLEMENT",
    });
    expect(nfc.checkoutRef).toBe("chk_nfc_999");
    expect(nfc.authCode).toBe("NFC1A2B3C");
    expect(nfc.cardScheme).toBe("VISA");
    expect(nfc.cvmMethod).toBe("NO_CVM");
    expect(nfc.status).toBe("PENDING_SETTLEMENT");

    const legacyNfc = normalizeNfcTapResult({
      orderId: "ord-4",
      reference: "chk_nfc_legacy",
      authorizationCode: "AUTH999",
      cardBrand: "MASTERCARD",
      status: "PAID",
    });
    expect(legacyNfc.checkoutRef).toBe("chk_nfc_legacy");
    expect(legacyNfc.authCode).toBe("AUTH999");
    expect(legacyNfc.cardScheme).toBe("MASTERCARD");
  });
});
