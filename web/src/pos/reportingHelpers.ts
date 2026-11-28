/**
 * Pure types and formatting helpers for Phase 6 (Live Order Management)
 * and Phase 7 (Owner Daily Report & CSV Export).
 */

export interface BranchView {
  id: string;
  name: string;
  active: boolean;
}

export interface LiveOrderSummary {
  id: string;
  orderNumber: string;
  orderType: string;
  branchId: string | null;
  tableId: string | null;
  tableNumber: string | null;
  serverName: string | null;
  itemCount: number;
  totalQuantity: number;
  subtotalPesewas: number;
  discountPesewas: number;
  taxPesewas: number;
  tipPesewas: number;
  totalPesewas: number;
  paidPesewas: number;
  balancePesewas: number;
  paymentStatus: string;
  kitchenStatus: string;
  kdsTicketCount: number;
  createdAt: string;
  ageSeconds: number;
}

export interface LiveOrderPage {
  nextCursor: string | null;
  hasMore: boolean;
  serverTime: string;
  orders: LiveOrderSummary[];
}

export interface SalesSummary {
  grossSalesPesewas: number;
  discountsPesewas: number;
  voidsPesewas: number;
  netSalesPesewas: number;
  taxPesewas: number;
  tipsPesewas: number;
  totalCollectedPesewas: number;
  orderCount: number;
  paidOrderCount: number;
  openOrderCount: number;
  voidedOrderCount: number;
}

export interface TenderBreakdown {
  tenderType: string;
  count: number;
  amountPesewas: number;
}

export interface OrderTypeBreakdown {
  orderType: string;
  orderCount: number;
  totalPesewas: number;
  averageOrderValuePesewas: number;
}

export interface TopItem {
  itemName: string;
  quantity: number;
  revenuePesewas: number;
}

export interface KitchenTiming {
  ticketCount: number;
  avgFiredToReadySeconds: number | null;
  avgReadyToServedSeconds: number | null;
}

export interface VoidEntry {
  orderId: string;
  orderNumber: string;
  orderItemId: string | null;
  itemName: string | null;
  quantity: number | null;
  amountPesewas: number;
  reason: string;
  voidedBy: string | null;
  approvedBy: string | null;
  voidedAt: string;
}

export interface ShiftCashRow {
  shiftId: string;
  terminalId: string;
  branchId: string | null;
  cashierName: string | null;
  status: string;
  openingBalancePesewas: number;
  expectedCashPesewas: number;
  countedCashPesewas: number | null;
  variancePesewas: number | null;
  shiftStart: string;
  shiftEnd: string | null;
}

export interface ShiftCashSummary {
  totalExpectedCashPesewas: number;
  totalCountedCashPesewas: number;
  totalVariancePesewas: number;
  openShiftCount: number;
  closedShiftCount: number;
  shifts: ShiftCashRow[];
}

export interface DailyReport {
  merchantId: string;
  branchId: string | null;
  businessDate: string;
  timezone: string;
  generatedAt: string;
  sales: SalesSummary;
  tenderMix: TenderBreakdown[];
  orderTypes: OrderTypeBreakdown[];
  topItemsByQuantity: TopItem[];
  topItemsByRevenue: TopItem[];
  kitchenTiming: KitchenTiming;
  voids: VoidEntry[];
  shiftCash: ShiftCashSummary;
}

/** Returns today's date (YYYY-MM-DD) in the Africa/Accra business timezone. */
export function todayInAccra(now: Date = new Date()): string {
  try {
    const parts = new Intl.DateTimeFormat("en-CA", {
      timeZone: "Africa/Accra",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).formatToParts(now);
    const y = parts.find((p) => p.type === "year")?.value;
    const m = parts.find((p) => p.type === "month")?.value;
    const d = parts.find((p) => p.type === "day")?.value;
    if (y && m && d) return `${y}-${m}-${d}`;
  } catch {
    // Fallback to UTC (Africa/Accra is UTC+0 year-round)
  }
  return now.toISOString().slice(0, 10);
}

/** Formats an order age in seconds into a compact human-readable label (e.g. "45s", "12m", "1h 15m"). */
export function formatOrderAge(ageSeconds: number): string {
  const sec = Math.max(0, Math.floor(Number.isFinite(ageSeconds) ? ageSeconds : 0));
  if (sec < 60) return `${sec}s`;
  const mins = Math.floor(sec / 60);
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  const remMins = mins % 60;
  if (hours < 24) return remMins > 0 ? `${hours}h ${remMins}m` : `${hours}h`;
  const days = Math.floor(hours / 24);
  const remHours = hours % 24;
  return remHours > 0 ? `${days}d ${remHours}h` : `${days}d`;
}

/** Formats an average kitchen duration in seconds (nullable) into "4m 12s" or "—". */
export function formatDurationSeconds(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) return "—";
  const sec = Math.max(0, Math.round(seconds));
  if (sec < 60) return `${sec}s`;
  const mins = Math.floor(sec / 60);
  const remSec = sec % 60;
  if (mins < 60) return remSec > 0 ? `${mins}m ${remSec}s` : `${mins}m`;
  const hours = Math.floor(mins / 60);
  const remMins = mins % 60;
  return remMins > 0 ? `${hours}h ${remMins}m` : `${hours}h`;
}

/** Formats integer pesewas into a decimal GHS string without floating-point drift (e.g. 1250 -> "12.50"). */
export function formatPesewasDecimal(pesewas: number): string {
  const safe = Number.isFinite(pesewas) ? Math.trunc(pesewas) : 0;
  const negative = safe < 0;
  const abs = Math.abs(safe);
  const major = Math.floor(abs / 100);
  const minor = String(abs % 100).padStart(2, "0");
  return `${negative ? "-" : ""}${major}.${minor}`;
}

/** Formats a quantity (integer or decimal) cleanly without trailing zeros. */
export function formatQuantity(qty: number | null | undefined): string {
  if (qty === null || qty === undefined || !Number.isFinite(qty)) return "—";
  if (Number.isInteger(qty)) return String(qty);
  return String(Number(qty.toFixed(4)));
}

/** RFC 4180 CSV field escaping with formula-injection neutralization. */
export function csvEscape(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "number") return String(value);
  const str = String(value);
  const safe = /^[=+\-@\t\r]/.test(str) && !/^-\d+(?:\.\d+)?$/.test(str) ? `'${str}` : str;
  if (/[",\r\n]/.test(safe)) {
    return `"${safe.replace(/"/g, '""')}"`;
  }
  return safe;
}

function csvRow(cells: (string | number | null | undefined)[]): string {
  return cells.map(csvEscape).join(",");
}

export function formatTenderLabel(tenderType: string): string {
  switch (tenderType.toUpperCase()) {
    case "CASH":
      return "Cash";
    case "MOMO":
      return "Mobile Money";
    case "CARD":
      return "Card";
    case "BANK_TRANSFER":
      return "Bank Transfer";
    case "EXTERNAL_MOMO":
      return "Own MoMo (External)";
    case "EXTERNAL_CARD":
      return "External Card POS";
    case "EXTERNAL_POS":
      return "External POS";
    case "GHQR":
      return "GhQR";
    case "WALLET":
      return "ELEPAY Wallet";
    default:
      return tenderType;
  }
}

export function formatOrderTypeLabel(orderType: string): string {
  switch (orderType.toUpperCase()) {
    case "DINE_IN":
      return "Dine-in";
    case "TAKEAWAY":
      return "Takeaway";
    case "DELIVERY":
      return "Delivery";
    case "STANDARD":
      return "Counter";
    default:
      return orderType;
  }
}

export function paymentStatusTone(status: string): "active" | "pending" | "failed" | "info" {
  switch (status.toUpperCase()) {
    case "PAID":
      return "active";
    case "PARTIAL":
      return "info";
    case "VOIDED":
      return "failed";
    default:
      return "pending";
  }
}

export function kitchenStatusTone(status: string): "active" | "pending" | "failed" | "info" {
  switch (status.toUpperCase()) {
    case "READY":
    case "SERVED":
      return "active";
    case "NEW":
      return "info";
    case "CANCELLED":
      return "failed";
    default:
      return "pending";
  }
}

/** Client-side quick search filter over loaded live orders. */
export function filterLiveOrdersLocally(orders: LiveOrderSummary[], query: string): LiveOrderSummary[] {
  const q = query.trim().toLowerCase();
  if (!q) return orders;
  return orders.filter((o) => {
    return (
      o.orderNumber.toLowerCase().includes(q) ||
      (o.tableNumber && o.tableNumber.toLowerCase().includes(q)) ||
      (o.serverName && o.serverName.toLowerCase().includes(q)) ||
      o.orderType.toLowerCase().includes(q) ||
      formatOrderTypeLabel(o.orderType).toLowerCase().includes(q)
    );
  });
}

/** Builds a clean download filename for a daily report CSV. */
export function buildDailyReportCsvFilename(businessDate: string, branchName?: string | null): string {
  const datePart = businessDate.replace(/[^0-9-]/g, "") || "report";
  const branchSlug = branchName
    ? branchName
        .trim()
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-+|-+$/g, "")
    : "all-branches";
  return `elepay-daily-report-${datePart}-${branchSlug || "all-branches"}.csv`;
}

/** Serializes a DailyReport into a multi-section RFC 4180 CSV document. */
export function formatDailyReportCsv(report: DailyReport, branchName?: string | null): string {
  const lines: string[] = [];
  const scopeLabel = branchName?.trim() || (report.branchId ? report.branchId : "All Branches");

  // Section 1: Summary
  lines.push(csvRow(["Section", "Metric", "Value"]));
  lines.push(csvRow(["Report", "Business Date", report.businessDate]));
  lines.push(csvRow(["Report", "Timezone", report.timezone]));
  lines.push(csvRow(["Report", "Branch", scopeLabel]));
  lines.push(csvRow(["Report", "Generated At", report.generatedAt]));
  lines.push(csvRow(["Sales", "Gross Sales (GHS)", formatPesewasDecimal(report.sales.grossSalesPesewas)]));
  lines.push(csvRow(["Sales", "Discounts (GHS)", formatPesewasDecimal(report.sales.discountsPesewas)]));
  lines.push(csvRow(["Sales", "Voids (GHS)", formatPesewasDecimal(report.sales.voidsPesewas)]));
  lines.push(csvRow(["Sales", "Net Sales (GHS)", formatPesewasDecimal(report.sales.netSalesPesewas)]));
  lines.push(csvRow(["Sales", "Tax (GHS)", formatPesewasDecimal(report.sales.taxPesewas)]));
  lines.push(csvRow(["Sales", "Tips (GHS)", formatPesewasDecimal(report.sales.tipsPesewas)]));
  lines.push(csvRow(["Sales", "Total Collected (GHS)", formatPesewasDecimal(report.sales.totalCollectedPesewas)]));
  lines.push(csvRow(["Sales", "Total Orders", report.sales.orderCount]));
  lines.push(csvRow(["Sales", "Paid Orders", report.sales.paidOrderCount]));
  lines.push(csvRow(["Sales", "Open Orders", report.sales.openOrderCount]));
  lines.push(csvRow(["Sales", "Voided Orders", report.sales.voidedOrderCount]));
  lines.push(csvRow(["Kitchen", "Tickets", report.kitchenTiming.ticketCount]));
  lines.push(csvRow(["Kitchen", "Avg Fired to Ready", formatDurationSeconds(report.kitchenTiming.avgFiredToReadySeconds)]));
  lines.push(csvRow(["Kitchen", "Avg Ready to Served", formatDurationSeconds(report.kitchenTiming.avgReadyToServedSeconds)]));
  lines.push(csvRow(["Shift Cash", "Expected Cash (GHS)", formatPesewasDecimal(report.shiftCash.totalExpectedCashPesewas)]));
  lines.push(csvRow(["Shift Cash", "Counted Cash (GHS)", formatPesewasDecimal(report.shiftCash.totalCountedCashPesewas)]));
  lines.push(csvRow(["Shift Cash", "Variance (GHS)", formatPesewasDecimal(report.shiftCash.totalVariancePesewas)]));
  lines.push(csvRow(["Shift Cash", "Open Shifts", report.shiftCash.openShiftCount]));
  lines.push(csvRow(["Shift Cash", "Closed Shifts", report.shiftCash.closedShiftCount]));

  // Section 2: Tender Mix
  lines.push("");
  lines.push(csvRow(["Tender Mix"]));
  lines.push(csvRow(["Tender Type", "Count", "Amount (GHS)"]));
  for (const t of report.tenderMix) {
    lines.push(csvRow([formatTenderLabel(t.tenderType), t.count, formatPesewasDecimal(t.amountPesewas)]));
  }

  // Section 3: Order Types
  lines.push("");
  lines.push(csvRow(["Order Types"]));
  lines.push(csvRow(["Order Type", "Orders", "Total (GHS)", "Average Order Value (GHS)"]));
  for (const ot of report.orderTypes) {
    lines.push(
      csvRow([
        formatOrderTypeLabel(ot.orderType),
        ot.orderCount,
        formatPesewasDecimal(ot.totalPesewas),
        formatPesewasDecimal(ot.averageOrderValuePesewas),
      ]),
    );
  }

  // Section 4: Top Items by Revenue
  lines.push("");
  lines.push(csvRow(["Top Items by Revenue"]));
  lines.push(csvRow(["Item", "Quantity", "Revenue (GHS)"]));
  for (const item of report.topItemsByRevenue) {
    lines.push(csvRow([item.itemName, formatQuantity(item.quantity), formatPesewasDecimal(item.revenuePesewas)]));
  }

  // Section 5: Top Items by Quantity
  lines.push("");
  lines.push(csvRow(["Top Items by Quantity"]));
  lines.push(csvRow(["Item", "Quantity", "Revenue (GHS)"]));
  for (const item of report.topItemsByQuantity) {
    lines.push(csvRow([item.itemName, formatQuantity(item.quantity), formatPesewasDecimal(item.revenuePesewas)]));
  }

  // Section 6: Voids
  lines.push("");
  lines.push(csvRow(["Voids"]));
  lines.push(csvRow(["Order #", "Item", "Quantity", "Amount (GHS)", "Reason", "Voided By", "Approved By", "Voided At"]));
  for (const v of report.voids) {
    lines.push(
      csvRow([
        v.orderNumber,
        v.itemName ?? "Entire Order",
        v.quantity !== null && v.quantity !== undefined ? formatQuantity(v.quantity) : "",
        formatPesewasDecimal(v.amountPesewas),
        v.reason,
        v.voidedBy ?? "",
        v.approvedBy ?? "",
        v.voidedAt,
      ]),
    );
  }

  return lines.join("\r\n");
}
