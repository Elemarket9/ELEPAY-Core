import { ApiError } from "./api";
import type { CatalogRow } from "./types";

export type OrderType = "RETAIL" | "DINE_IN" | "TAKEAWAY" | "DELIVERY";
export type MomoNetwork = "mtn" | "telecel" | "airteltigo";
export type TenderMode = "CASH" | "MOMO" | "GHQR" | "CARD";

export const ORDER_TYPES: { value: OrderType; label: string }[] = [
  { value: "DINE_IN", label: "Dine-In" },
  { value: "TAKEAWAY", label: "Takeaway" },
  { value: "DELIVERY", label: "Delivery" },
  { value: "RETAIL", label: "Retail" },
];

export const MOMO_NETWORKS: { value: MomoNetwork; label: string }[] = [
  { value: "mtn", label: "MTN MoMo" },
  { value: "telecel", label: "Telecel Cash" },
  { value: "airteltigo", label: "AT Money" },
];

export interface PosTerminal {
  id: string;
  merchantId: string;
  branchId: string | null;
  serialNumber: string;
  model: string;
  status: string;
}

export interface PosCashier {
  id: string;
  merchantId: string;
  name: string;
  badgeNumber: string;
  isActive: boolean;
}

export interface PosShift {
  id: string;
  terminalId: string;
  cashierId: string;
  merchantId: string;
  openingBalancePesewas: number;
  closingBalancePesewas: number | null;
  cashDiscrepancyPesewas: number | null;
  status: string;
}

export interface HeldCart {
  id: string;
  terminalId: string;
  cashierId: string;
  merchantId: string;
  customerName: string | null;
  cartPayloadJson: string;
  totalPesewas: number;
  heldAt: string;
}

export interface PosOrder {
  id: string;
  orderNumber: string;
  totalPesewas: number;
  paidPesewas: number;
  changePesewas: number;
  status: string;
  orderType?: string;
}

export interface PosOrderItem {
  id: string;
  orderId: string;
  variantId: string;
  itemName: string;
  quantity: number;
  unitPricePesewas: number;
  discountPesewas: number;
  taxPesewas: number;
  totalPesewas: number;
  modifiersJson: string | null;
  seatNumber: number | null;
  course: string | null;
  notes: string | null;
}

export interface PosReceipt {
  receiptNumber: string;
  escposFormattedText: string;
}

export interface PosCartLine {
  variantId: string;
  label: string;
  unitPricePesewas: number;
  qty: number;
  seatNumber?: number | null;
  course?: string | null;
}

export interface KdsTicketPayloadItem {
  orderItemId: string;
  itemName: string;
  quantity: number;
  modifiers: string | null;
  course: string;
}

export interface KdsTicketPayload {
  merchantId: string;
  orderId: string;
  tableId: string | null;
  diningMode: string;
  serverName: string | null;
  course: string;
  items: KdsTicketPayloadItem[];
  notes: string | null;
  clientRef: string;
}

export interface EvenSplitResult {
  orderId: string;
  totalPesewas: number;
  splitCount: number;
  portions: { portionIndex: number; amountPesewas: number }[];
}

export interface SeatSplitResult {
  orderId: string;
  totalPesewas: number;
  seatGroups: {
    seatNumber: number;
    subtotalPesewas: number;
    items: { id: string; itemName: string; quantity: number; totalPesewas: number }[];
  }[];
}

export interface TenderInitiation {
  orderId?: string;
  orderStatus?: string | null;
  tenderType: string;
  amountPesewas: number;
  checkoutRef?: string | null;
  checkoutReference?: string | null;
  reference?: string | null;
  paymentStatus?: string | null;
  status?: string | null;
  payUrl?: string | null;
  qrPayload?: string | null;
  qrReference?: string | null;
  prompt?: string | null;
  message?: string | null;
  order?: PosOrder | null;
}

export interface NormalizedTenderResult {
  checkoutRef: string | null;
  paymentStatus: string | null;
  orderStatus: string | null;
  qrPayload: string | null;
  qrReference: string | null;
  payUrl: string | null;
  prompt: string | null;
  order: PosOrder | null;
}

export function catalogItemLabel(r: CatalogRow): string {
  return r.variantName && r.variantName !== "Standard" ? `${r.productName} (${r.variantName})` : r.productName;
}

export function isFeatureNotEnabled(err: unknown): boolean {
  return err instanceof ApiError && err.status === 404 && err.code === "FEATURE_NOT_ENABLED";
}

export function isTerminalStatusPaid(status?: string | null): boolean {
  if (!status) return false;
  const s = status.toUpperCase();
  return s === "PAID" || s === "SUCCEEDED" || s === "COMPLETED" || s === "SETTLED";
}

export function isTerminalStatusFailed(status?: string | null): boolean {
  if (!status) return false;
  const s = status.toUpperCase();
  return s === "FAILED" || s === "EXPIRED" || s === "CANCELLED" || s === "DECLINED";
}

export function normalizeTenderResult(res: TenderInitiation): NormalizedTenderResult {
  return {
    checkoutRef: res.checkoutRef ?? res.checkoutReference ?? res.reference ?? null,
    paymentStatus: res.paymentStatus ?? res.status ?? null,
    orderStatus: res.orderStatus ?? res.order?.status ?? null,
    qrPayload: res.qrPayload ?? null,
    qrReference: res.qrReference ?? null,
    payUrl: res.payUrl ?? null,
    prompt: res.prompt ?? res.message ?? null,
    order: res.order ?? null,
  };
}

export function computeCartTotals(
  cart: PosCartLine[],
  discountPesewas: number | null,
  taxRatePercent: number,
): { subtotal: number; effectiveDiscount: number; tax: number; total: number } {
  const subtotal = cart.reduce((sum, line) => sum + line.unitPricePesewas * line.qty, 0);
  const rawDiscount = discountPesewas ?? 0;
  const effectiveDiscount = Math.min(Math.max(0, rawDiscount), subtotal);
  const taxableBase = Math.max(0, subtotal - effectiveDiscount);
  const safeTaxRate = Number.isFinite(taxRatePercent) && taxRatePercent >= 0 && taxRatePercent <= 100 ? taxRatePercent : 0;
  const tax = Math.round((taxableBase * safeTaxRate) / 100);
  return { subtotal, effectiveDiscount, tax, total: taxableBase + tax };
}

export function normalizeCourse(course?: string | null): string {
  const trimmed = course?.trim();
  return trimmed ? trimmed.toUpperCase() : "MAIN";
}

export function resolveTicketCourse(items: { course?: string | null }[]): string {
  if (items.length === 0) return "MAIN";
  const unique = new Set(items.map((it) => normalizeCourse(it.course)));
  return unique.size === 1 ? Array.from(unique)[0] : "ALL";
}

export function buildKdsTicketPayload(args: {
  merchantId: string;
  orderId: string;
  tableId: string | null;
  orderType: OrderType;
  serverName: string | null;
  orderItems: PosOrderItem[];
  notes?: string | null;
  clientRef?: string | null;
}): KdsTicketPayload {
  const items: KdsTicketPayloadItem[] = args.orderItems.map((it) => ({
    orderItemId: it.id,
    itemName: it.itemName,
    quantity: it.quantity,
    modifiers: it.modifiersJson && it.modifiersJson !== "[]" ? it.modifiersJson : null,
    course: normalizeCourse(it.course),
  }));
  return {
    merchantId: args.merchantId,
    orderId: args.orderId,
    tableId: args.orderType === "DINE_IN" && args.tableId ? args.tableId : null,
    diningMode: args.orderType === "TAKEAWAY" ? "TAKEOUT" : args.orderType,
    serverName: args.serverName,
    course: resolveTicketCourse(items),
    items,
    notes: args.notes ?? null,
    clientRef: args.clientRef ?? `pos:${args.orderId}`,
  };
}

export function buildPosOrderPayload(args: {
  merchantId: string;
  branchId: string | null;
  terminalId: string | null;
  cashierId: string | null;
  orderType: OrderType;
  lines: PosCartLine[];
  discountPesewas: number;
  taxPesewas: number;
}) {
  return {
    merchantId: args.merchantId,
    branchId: args.branchId,
    terminalId: args.terminalId,
    cashierId: args.cashierId,
    orderType: args.orderType,
    items: args.lines.map((c) => ({
      variantId: c.variantId,
      itemName: c.label,
      quantity: c.qty,
      unitPricePesewas: c.unitPricePesewas,
      discountPesewas: 0,
      taxPesewas: 0,
      seatNumber: args.orderType === "DINE_IN" ? (c.seatNumber ?? null) : null,
      course: c.course?.trim() ? c.course.trim().toUpperCase() : null,
    })),
    discountPesewas: args.discountPesewas,
    taxPesewas: args.taxPesewas,
  };
}

export function parseHeldCartPayload(payloadJson: string): {
  items: PosCartLine[];
  discount: string;
  taxPercent: string;
  orderType: OrderType;
  tableId: string | null;
} | null {
  try {
    const parsed = JSON.parse(payloadJson) as Record<string, unknown>;
    if (!Array.isArray(parsed.items)) return null;
    return {
      items: parsed.items as PosCartLine[],
      discount: typeof parsed.discount === "string" ? parsed.discount : "",
      taxPercent: typeof parsed.taxPercent === "string" ? parsed.taxPercent : "0",
      orderType:
        parsed.orderType === "DINE_IN" ||
        parsed.orderType === "TAKEAWAY" ||
        parsed.orderType === "DELIVERY" ||
        parsed.orderType === "RETAIL"
          ? parsed.orderType
          : "DINE_IN",
      tableId: typeof parsed.tableId === "string" ? parsed.tableId : null,
    };
  } catch {
    return null;
  }
}
