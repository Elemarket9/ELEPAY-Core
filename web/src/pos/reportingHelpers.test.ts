import { describe, expect, it } from "vitest";
import {
  buildDailyReportCsvFilename,
  csvEscape,
  filterLiveOrdersLocally,
  formatDailyReportCsv,
  formatDurationSeconds,
  formatOrderAge,
  formatOrderTypeLabel,
  formatPesewasDecimal,
  formatTenderLabel,
  kitchenStatusTone,
  paymentStatusTone,
  todayInAccra,
  type DailyReport,
  type LiveOrderSummary,
} from "./reportingHelpers";

function sampleReport(): DailyReport {
  return {
    merchantId: "m-1",
    branchId: "b-1",
    businessDate: "2026-11-27",
    timezone: "Africa/Accra",
    generatedAt: "2026-11-27T18:30:00Z",
    sales: {
      grossSalesPesewas: 250_000,
      discountsPesewas: 15_000,
      voidsPesewas: 12_500,
      netSalesPesewas: 235_000,
      taxPesewas: 29_375,
      tipsPesewas: 10_000,
      totalCollectedPesewas: 274_375,
      orderCount: 18,
      paidOrderCount: 15,
      openOrderCount: 2,
      voidedOrderCount: 1,
    },
    tenderMix: [
      { tenderType: "CASH", count: 8, amountPesewas: 120_000 },
      { tenderType: "MOMO", count: 5, amountPesewas: 94_375 },
      { tenderType: "BANK_TRANSFER", count: 2, amountPesewas: 60_000 },
    ],
    orderTypes: [
      { orderType: "DINE_IN", orderCount: 12, totalPesewas: 200_000, averageOrderValuePesewas: 16_666 },
      { orderType: "TAKEAWAY", orderCount: 5, totalPesewas: 74_375, averageOrderValuePesewas: 14_875 },
    ],
    topItemsByRevenue: [
      { itemName: 'Jollof Rice, "Special" Platter', quantity: 14, revenuePesewas: 140_000 },
      { itemName: "Grilled Tilapia", quantity: 5, revenuePesewas: 75_000 },
    ],
    topItemsByQuantity: [
      { itemName: "Bottled Water", quantity: 22, revenuePesewas: 22_000 },
      { itemName: 'Jollof Rice, "Special" Platter', quantity: 14, revenuePesewas: 140_000 },
    ],
    kitchenTiming: {
      ticketCount: 16,
      avgFiredToReadySeconds: 495,
      avgReadyToServedSeconds: 75,
    },
    voids: [
      {
        orderId: "o-9",
        orderNumber: "ORD-1009",
        orderItemId: "oi-2",
        itemName: "Banku & Okro",
        quantity: 1,
        amountPesewas: 12_500,
        reason: 'Customer changed mind, wanted "Kelewele"\ninstead',
        voidedBy: "cashier-ama",
        approvedBy: "mgr-kwame",
        voidedAt: "2026-11-27T14:15:00Z",
      },
    ],
    shiftCash: {
      totalExpectedCashPesewas: 120_000,
      totalCountedCashPesewas: 119_500,
      totalVariancePesewas: -500,
      openShiftCount: 1,
      closedShiftCount: 1,
      shifts: [],
    },
  };
}

describe("CSV & money formatting helpers", () => {
  it("escapes commas, quotes, and newlines per RFC 4180", () => {
    expect(csvEscape("Plain")).toBe("Plain");
    expect(csvEscape("Hello, World")).toBe('"Hello, World"');
    expect(csvEscape('He said "hi"')).toBe('"He said ""hi"""');
    expect(csvEscape("Line 1\nLine 2")).toBe('"Line 1\nLine 2"');
    expect(csvEscape(null)).toBe("");
    expect(csvEscape(undefined)).toBe("");
    expect(csvEscape(42)).toBe("42");
  });

  it("neutralizes CSV formula injection while preserving signed numeric values", () => {
    expect(csvEscape("=CMD|' /C calc'!A0")).toBe("'=CMD|' /C calc'!A0");
    expect(csvEscape("+1+1")).toBe("'+1+1");
    expect(csvEscape("@SUM(1+1)")).toBe("'@SUM(1+1)");
    expect(csvEscape("-1+1")).toBe("'-1+1");
    expect(csvEscape("\t=1")).toBe("'\t=1");
    expect(csvEscape("\r=1")).toBe('"\'\r=1"');
    expect(csvEscape("-12.50")).toBe("-12.50");
    expect(csvEscape("-100")).toBe("-100");
    expect(csvEscape(-12.5)).toBe("-12.5");
  });

  it("formats pesewas as exact decimal strings without float drift", () => {
    expect(formatPesewasDecimal(0)).toBe("0.00");
    expect(formatPesewasDecimal(5)).toBe("0.05");
    expect(formatPesewasDecimal(1250)).toBe("12.50");
    expect(formatPesewasDecimal(-500)).toBe("-5.00");
    expect(formatPesewasDecimal(274_375)).toBe("2743.75");
  });

  it("serializes a DailyReport to CSV with all sections and escaped special characters", () => {
    const csv = formatDailyReportCsv(sampleReport(), "Osu Main Branch");
    const lines = csv.split("\r\n");

    expect(lines[0]).toBe("Section,Metric,Value");
    expect(csv).toContain("Report,Business Date,2026-11-27");
    expect(csv).toContain("Report,Branch,Osu Main Branch");
    expect(csv).toContain("Sales,Gross Sales (GHS),2500.00");
    expect(csv).toContain("Sales,Discounts (GHS),150.00");
    expect(csv).toContain("Sales,Voids (GHS),125.00");
    expect(csv).toContain("Sales,Net Sales (GHS),2350.00");
    expect(csv).toContain("Kitchen,Avg Fired to Ready,8m 15s");
    expect(csv).toContain("Kitchen,Avg Ready to Served,1m 15s");
    expect(csv).toContain("Shift Cash,Variance (GHS),-5.00");
    expect(csv).toContain("Bank Transfer,2,600.00");
    expect(csv).toContain("Dine-in,12,2000.00,166.66");
    // Escaped quotes & commas in item name
    expect(csv).toContain('"Jollof Rice, ""Special"" Platter",14,1400.00');
    // Escaped newline, comma, and quotes in void reason
    expect(csv).toContain('"Customer changed mind, wanted ""Kelewele""\ninstead"');
  });

  it("builds sanitized CSV download filenames", () => {
    expect(buildDailyReportCsvFilename("2026-11-27", null)).toBe("elepay-daily-report-2026-11-27-all-branches.csv");
    expect(buildDailyReportCsvFilename("2026-11-27", "East Legon / VIP")).toBe("elepay-daily-report-2026-11-27-east-legon-vip.csv");
  });
});

describe("Age, duration, and date helpers", () => {
  it("formats order ages across seconds, minutes, hours, and days", () => {
    expect(formatOrderAge(-5)).toBe("0s");
    expect(formatOrderAge(0)).toBe("0s");
    expect(formatOrderAge(45)).toBe("45s");
    expect(formatOrderAge(60)).toBe("1m");
    expect(formatOrderAge(14 * 60 + 20)).toBe("14m");
    expect(formatOrderAge(3600)).toBe("1h");
    expect(formatOrderAge(3600 + 15 * 60)).toBe("1h 15m");
    expect(formatOrderAge(26 * 3600)).toBe("1d 2h");
  });

  it("formats kitchen durations and handles nulls", () => {
    expect(formatDurationSeconds(null)).toBe("—");
    expect(formatDurationSeconds(undefined)).toBe("—");
    expect(formatDurationSeconds(38)).toBe("38s");
    expect(formatDurationSeconds(120)).toBe("2m");
    expect(formatDurationSeconds(495)).toBe("8m 15s");
    expect(formatDurationSeconds(3720)).toBe("1h 2m");
  });

  it("computes Africa/Accra business date accurately at midnight boundary", () => {
    expect(todayInAccra(new Date("2026-11-27T23:59:59Z"))).toBe("2026-11-27");
    expect(todayInAccra(new Date("2026-11-28T00:00:01Z"))).toBe("2026-11-28");
  });
});

describe("Label, tone, and live order filter helpers", () => {
  it("maps tender types, order types, and status tones", () => {
    expect(formatTenderLabel("BANK_TRANSFER")).toBe("Bank Transfer");
    expect(formatTenderLabel("GHQR")).toBe("GhQR");
    expect(formatTenderLabel("CUSTOM")).toBe("CUSTOM");
    expect(formatOrderTypeLabel("DINE_IN")).toBe("Dine-in");
    expect(formatOrderTypeLabel("TAKEAWAY")).toBe("Takeaway");
    expect(formatOrderTypeLabel("DELIVERY")).toBe("Delivery");
    expect(paymentStatusTone("PAID")).toBe("active");
    expect(paymentStatusTone("PARTIAL")).toBe("info");
    expect(paymentStatusTone("VOIDED")).toBe("failed");
    expect(kitchenStatusTone("READY")).toBe("active");
    expect(kitchenStatusTone("NEW")).toBe("info");
    expect(kitchenStatusTone("CANCELLED")).toBe("failed");
  });

  it("filters live orders locally by order number, table, server, or type", () => {
    const orders: LiveOrderSummary[] = [
      {
        id: "o1",
        orderNumber: "ORD-101",
        orderType: "DINE_IN",
        branchId: "b1",
        tableId: "t1",
        tableNumber: "12",
        serverName: "Ama Mensah",
        itemCount: 3,
        totalQuantity: 4,
        subtotalPesewas: 15000,
        discountPesewas: 0,
        taxPesewas: 0,
        tipPesewas: 0,
        totalPesewas: 15000,
        paidPesewas: 0,
        balancePesewas: 15000,
        paymentStatus: "PENDING",
        kitchenStatus: "PREPARING",
        kdsTicketCount: 1,
        createdAt: "2026-11-27T12:00:00Z",
        ageSeconds: 420,
      },
      {
        id: "o2",
        orderNumber: "ORD-102",
        orderType: "DELIVERY",
        branchId: "b1",
        tableId: null,
        tableNumber: null,
        serverName: "Kojo",
        itemCount: 1,
        totalQuantity: 1,
        subtotalPesewas: 5000,
        discountPesewas: 0,
        taxPesewas: 0,
        tipPesewas: 0,
        totalPesewas: 5000,
        paidPesewas: 5000,
        balancePesewas: 0,
        paymentStatus: "PAID",
        kitchenStatus: "READY",
        kdsTicketCount: 1,
        createdAt: "2026-11-27T12:05:00Z",
        ageSeconds: 120,
      },
    ];

    expect(filterLiveOrdersLocally(orders, "")).toHaveLength(2);
    expect(filterLiveOrdersLocally(orders, "101").map((o) => o.id)).toEqual(["o1"]);
    expect(filterLiveOrdersLocally(orders, "12").map((o) => o.id)).toEqual(["o1"]);
    expect(filterLiveOrdersLocally(orders, "kojo").map((o) => o.id)).toEqual(["o2"]);
    expect(filterLiveOrdersLocally(orders, "delivery").map((o) => o.id)).toEqual(["o2"]);
  });
});
