import { ApiError } from "../api";
import { normalizeGhanaPhone } from "../money";

export type OrderType = "RETAIL" | "DINE_IN" | "TAKEAWAY" | "DELIVERY";
export type MomoNetwork = "mtn" | "telecel" | "airteltigo";
export type TenderMode = "CASH" | "MOMO" | "GHQR" | "NFC_CARD";

export const ORDER_TYPES: { value: OrderType; label: string }[] = [
  { value: "RETAIL", label: "Retail" },
  { value: "DINE_IN", label: "Dine-In" },
  { value: "TAKEAWAY", label: "Takeaway" },
  { value: "DELIVERY", label: "Delivery" },
];

export const MOMO_NETWORKS: { value: MomoNetwork; label: string }[] = [
  { value: "mtn", label: "MTN MoMo" },
  { value: "telecel", label: "Telecel Cash" },
  { value: "airteltigo", label: "AirtelTigo Money" },
];

export interface PosTerminal {
  id: string;
  merchantId: string;
  branchId: string | null;
  serialNumber: string;
  model: string;
  hardwareFingerprint: string | null;
  status: string;
  pairedAt: string | null;
  createdAt: string;
}

export interface PosCashier {
  id: string;
  merchantId: string;
  name: string;
  badgeNumber: string;
  isActive: boolean;
  createdAt: string;
}

export interface PosShift {
  id: string;
  terminalId: string;
  cashierId: string;
  merchantId: string;
  openingBalancePesewas: number;
  closingBalancePesewas: number | null;
  expectedCashPesewas: number;
  cashDiscrepancyPesewas: number | null;
  status: string;
  shiftStart: string;
  shiftEnd: string | null;
  notes: string | null;
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

export interface DiningTable {
  id: string;
  sectionId: string;
  tableNumber: string;
  capacity: number;
  status: "AVAILABLE" | "OCCUPIED" | "RESERVED" | "DIRTY";
}

export interface PosOrder {
  id: string;
  orderNumber: string;
  totalPesewas: number;
  paidPesewas: number;
  changePesewas: number;
  status: string;
  orderType?: string;
  merchantId?: string;
  subtotalPesewas?: number;
  discountPesewas?: number;
  taxPesewas?: number;
}

export interface PosReceipt {
  receiptNumber: string;
  escposFormattedText: string;
}

export interface EvenSplitPortion {
  portionIndex: number;
  amountPesewas: number;
}

export interface EvenSplitResult {
  orderId: string;
  totalPesewas: number;
  splitCount: number;
  portions: EvenSplitPortion[];
}

export interface SeatSplitGroup {
  seatNumber: number;
  subtotalPesewas: number;
  items: { id: string; itemName: string; quantity: number; lineTotalPesewas: number }[];
}

export interface SeatSplitResult {
  orderId: string;
  totalPesewas: number;
  seatGroups: SeatSplitGroup[];
}

export type BackendTenderType = TenderMode | "CARD" | "WALLET";

export interface TenderInitiation {
  orderId?: string;
  orderStatus?: string | null;
  tenderType: BackendTenderType;
  amountPesewas: number;
  linkId?: string | null;
  checkoutRef?: string | null;
  checkoutReference?: string | null;
  reference?: string | null;
  attemptId?: string | null;
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

export interface NfcTapResult {
  authorizationId?: string;
  orderId: string;
  terminalId?: string;
  ksn?: string;
  ksnCounter?: number;
  cardScheme?: string | null;
  cvmMethod?: string | null;
  authCode?: string | null;
  checkoutRef?: string | null;
  payUrl?: string | null;
  amountPesewas?: number;
  status: string;
  reference?: string | null;
  authorizationCode?: string | null;
  maskedPan?: string | null;
  cardBrand?: string | null;
  message?: string | null;
  order?: PosOrder | null;
}

export interface NormalizedNfcTapResult {
  authorizationId: string | null;
  checkoutRef: string | null;
  authCode: string | null;
  cardScheme: string | null;
  cvmMethod: string | null;
  payUrl: string | null;
  status: string;
  message: string | null;
  order: PosOrder | null;
}

export function normalizeTenderResult(res: TenderInitiation): NormalizedTenderResult {
  return {
    checkoutRef: res.checkoutRef ?? res.checkoutReference ?? res.reference ?? null,
    paymentStatus: res.paymentStatus ?? res.status ?? null,
    orderStatus: res.orderStatus ?? res.status ?? null,
    qrPayload: res.qrPayload ?? null,
    qrReference: res.qrReference ?? null,
    payUrl: res.payUrl ?? null,
    prompt: res.prompt ?? res.message ?? null,
    order: res.order ?? null,
  };
}

export function normalizeNfcTapResult(res: NfcTapResult): NormalizedNfcTapResult {
  return {
    authorizationId: res.authorizationId ?? null,
    checkoutRef: res.checkoutRef ?? res.reference ?? null,
    authCode: res.authCode ?? res.authorizationCode ?? null,
    cardScheme: res.cardScheme ?? res.cardBrand ?? null,
    cvmMethod: res.cvmMethod ?? null,
    payUrl: res.payUrl ?? null,
    status: res.status,
    message: res.message ?? null,
    order: res.order ?? null,
  };
}

export interface KdsTicketSummary {
  id: string;
  ticketNumber?: string;
  status: string;
  stationName?: string | null;
}

export interface PosCartLine {
  variantId: string;
  label: string;
  unitPricePesewas: number;
  qty: number;
  seatNumber: number | null;
}

export interface HeldCartPayload {
  items: PosCartLine[];
  discount: string;
  taxPercent: string;
  orderType: OrderType;
  tableId: string | null;
}

export function isFeatureNotEnabled(err: unknown): boolean {
  return err instanceof ApiError && err.code === "FEATURE_NOT_ENABLED";
}

export function computeCartTotals(
  lines: Pick<PosCartLine, "unitPricePesewas" | "qty">[],
  discountPesewas: number | null,
  taxRatePercent: number,
): { subtotal: number; effectiveDiscount: number; tax: number; total: number } {
  const subtotal = lines.reduce((sum, l) => sum + l.unitPricePesewas * l.qty, 0);
  const clampedDiscount = discountPesewas !== null && discountPesewas > 0
    ? Math.min(discountPesewas, subtotal)
    : 0;
  const taxValid = Number.isFinite(taxRatePercent) && taxRatePercent >= 0 && taxRatePercent <= 100;
  const tax = taxValid ? Math.round(((subtotal - clampedDiscount) * taxRatePercent) / 100) : 0;
  return {
    subtotal,
    effectiveDiscount: clampedDiscount,
    tax,
    total: subtotal - clampedDiscount + tax,
  };
}

export function buildPosOrderPayload(params: {
  merchantId: string;
  branchId?: string | null;
  terminalId: string | null;
  cashierId: string | null;
  orderType: OrderType;
  tableId?: string | null;
  lines: PosCartLine[];
  discountPesewas: number;
  taxPesewas: number;
}) {
  return {
    merchantId: params.merchantId,
    branchId: params.branchId ?? null,
    terminalId: params.terminalId,
    cashierId: params.cashierId,
    customerId: null,
    tableId: params.orderType === "DINE_IN" ? (params.tableId ?? null) : null,
    items: params.lines.map((l) => ({
      variantId: l.variantId,
      itemName: l.label,
      quantity: l.qty,
      unitPricePesewas: l.unitPricePesewas,
      discountPesewas: 0,
      taxPesewas: 0,
      modifiersJson: "[]",
      seatNumber: params.orderType === "DINE_IN" ? l.seatNumber : null,
      course: null,
      notes: null,
    })),
    discountPesewas: params.discountPesewas,
    taxPesewas: params.taxPesewas,
    tipPesewas: 0,
    orderType: params.orderType,
  };
}

export function isTerminalStatusPaid(status: string | null | undefined): boolean {
  if (!status) return false;
  const norm = status.trim().toUpperCase();
  return norm === "PAID" || norm === "SUCCEEDED" || norm === "CAPTURED" || norm === "COMPLETED";
}

export function isTerminalStatusFailed(status: string | null | undefined): boolean {
  if (!status) return false;
  const norm = status.trim().toUpperCase();
  return norm === "FAILED" || norm === "DECLINED" || norm === "CANCELLED" || norm === "EXPIRED";
}

export function formatKdsStatusLabel(status: string | null | undefined): string {
  if (!status) return "Not sent";
  const norm = status.trim().toUpperCase();
  switch (norm) {
    case "NEW":
    case "QUEUED":
      return "Kitchen: Queued";
    case "IN_PROGRESS":
    case "PREPARING":
      return "Kitchen: Preparing";
    case "READY":
    case "BUMPED":
      return "Kitchen: Ready";
    case "COMPLETED":
    case "SERVED":
      return "Kitchen: Served";
    default:
      return `Kitchen: ${norm}`;
  }
}

export function parseHeldCartPayload(json: string): HeldCartPayload | null {
  try {
    const parsed = JSON.parse(json) as Partial<HeldCartPayload>;
    if (!Array.isArray(parsed.items)) return null;
    return {
      items: parsed.items,
      discount: typeof parsed.discount === "string" ? parsed.discount : "",
      taxPercent: typeof parsed.taxPercent === "string" ? parsed.taxPercent : "0",
      orderType: parsed.orderType ?? "RETAIL",
      tableId: parsed.tableId ?? null,
    };
  } catch {
    return null;
  }
}

export interface PosTenderRecovery {
  orderId:string; key:string; tenderType:"CASH"|"MOMO"|"GHQR"|"CARD"; amountPesewas:number;
  phone?:string; network?:MomoNetwork; checkoutRef:string|null; qrPayload?:string; payUrl?:string;
}
export function decodePosTender(raw:string|null):PosTenderRecovery|null {
  if(raw===null)return null;
  const a=JSON.parse(raw) as PosTenderRecovery;
  if(!a||typeof a.orderId!=="string"||!/^[0-9a-f-]{36}$/i.test(a.orderId)||typeof a.key!=="string"||!a.key||a.key.length>200||!["CASH","MOMO","GHQR","CARD"].includes(a.tenderType)||!Number.isSafeInteger(a.amountPesewas)||a.amountPesewas<=0||(a.checkoutRef!==null&&(typeof a.checkoutRef!=="string"||!/^[\w-]+$/.test(a.checkoutRef))))throw new Error("Invalid saved tender");
  if(a.tenderType==="MOMO"&&(typeof a.phone!=="string"||normalizeGhanaPhone(a.phone)!==a.phone||!MOMO_NETWORKS.some(n=>n.value===a.network)))throw new Error("Invalid saved MoMo request");
  if(a.qrPayload!==undefined&&typeof a.qrPayload!=="string")throw new Error("Invalid QR data");
  if(a.payUrl!==undefined&&typeof a.payUrl!=="string")throw new Error("Invalid payment URL");
  return a;
}
export function remainingPosBalance(order:Pick<PosOrder,"totalPesewas"|"paidPesewas">):number {
  if(!Number.isSafeInteger(order.totalPesewas)||!Number.isSafeInteger(order.paidPesewas)||order.totalPesewas<0||order.paidPesewas<0)throw new Error("Invalid order totals");
  return Math.max(0,order.totalPesewas-order.paidPesewas);
}
export function isPaidPosOrder(order:PosOrder):boolean {
  return order.status==="PAID"&&Number.isSafeInteger(order.totalPesewas)&&order.totalPesewas>0&&Number.isSafeInteger(order.paidPesewas)&&order.paidPesewas>=order.totalPesewas;
}
export function exactBarcodeMatch<T extends {barcode:string|null;sku:string}>(rows:T[],raw:string):T|null{
  const code=raw.trim();if(!code)return null;const matches=rows.filter(r=>r.barcode===code||r.sku===code);return matches.length===1?matches[0]:null;
}
export type SavedPosOrder={key:string;payload:ReturnType<typeof buildPosOrderPayload>;orderId:string|null};
export function decodeSavedPosOrder(raw:string|null,merchantId:string):SavedPosOrder|null {
  if(raw===null)return null;const v=JSON.parse(raw) as SavedPosOrder;
  if(!v||typeof v.key!=="string"||!v.key||v.key.length>200||!v.payload||v.payload.merchantId!==merchantId||!Array.isArray(v.payload.items)||!v.payload.items.length||v.payload.items.some(i=>!i||typeof i.variantId!=="string"||typeof i.itemName!=="string"||!Number.isSafeInteger(i.quantity)||i.quantity<=0||!Number.isSafeInteger(i.unitPricePesewas)||i.unitPricePesewas<0)||(v.orderId!==null&&(typeof v.orderId!=="string"||!/^[0-9a-f-]{36}$/i.test(v.orderId))))throw new Error("Invalid saved POS order");
  return v;
}
