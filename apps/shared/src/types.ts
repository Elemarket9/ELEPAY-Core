export interface MerchantView {
  id: string;
  tradingName: string;
  kind: string;
  status: string;
  merchantCode: string;
  role: string;
}

export interface Balances {
  pending: number;
  available: number;
  reserved: number;
}

export interface TransactionLine {
  reference: string;
  line: number;
  kind: string;
  description: string | null;
  amount: number;
  fee: number;
  net: number;
  refunded: number;
  disputed: number;
  pending: number;
  settled: number;
  status: string;
  paidAt: string | null;
  collector: string;
}

export interface Payout {
  reference: string;
  amount: number;
  status: string;
  clientReference: string;
  createdAt: string;
  failureReason: string | null;
}

export interface SettlementDestination {
  id: string;
  type: string;
  network: string;
  accountNumber: string;
  accountName: string;
  verification: string;
  status?: string;
  coolingOffUntil?: string | null;
  oobVerificationStatus?: string;
}

export interface CatalogRow {
  variantId: string;
  productId: string;
  productName: string;
  categoryId: string | null;
  categoryName: string | null;
  sku: string;
  barcode: string | null;
  variantName: string | null;
  costPricePesewas: number;
  retailPricePesewas: number;
  trackInventory: boolean;
  onHand: number;
  reorderPoint: number;
}

export interface FloorPlan {
  id: string;
  name: string;
}

export type TableStatus = "AVAILABLE" | "OCCUPIED" | "BILL_PRINTED" | "RESERVED" | "DIRTY";

export type TenderType =
  | "CASH"
  | "MOMO"
  | "GHQR"
  | "CARD"
  | "BANK_TRANSFER"
  | "EXTERNAL_MOMO"
  | "EXTERNAL_CARD"
  | "EXTERNAL_POS";

export interface OrderItemKdsMeta {
  kdsRound?: number | null;
  sentAt?: string | null;
  voided?: boolean;
  voidedAt?: string | null;
}

export interface DiningTable {
  id: string;
  sectionId: string;
  tableNumber: string;
  capacity: number;
  status: TableStatus;
  currentOrderId?: string | null;
  branchId?: string | null;
}

export interface DeviceCredential {
  apiKey: string;
  signingSecret: string;
  deviceId: string;
  merchantId: string;
  merchantName: string;
  stationId: string | null;
  stationName: string | null;
  hasPrinter?: boolean;
}

export interface KitchenStation {
  id: string;
  name: string;
  output: "SCREEN" | "PRINTER" | "BOTH";
  printerAddress: string | null;
}
