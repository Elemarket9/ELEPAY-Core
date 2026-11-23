/**
 * Offline-first POS order journal.
 *
 * Only cash collection is permitted offline. Every order and replay key is written
 * transactionally to IndexedDB before the cashier receives a local acknowledgement.
 * A local acknowledgment is NEVER a cloud receipt or rail authorization.
 */
export interface OfflineItem {
  variantId: string | null;
  itemName: string;
  quantity: number;
  unitPricePesewas: number;
  discountPesewas: number;
  taxPesewas: number;
  modifiersJson: string;
  seatNumber: number | null;
  course: string | null;
  notes: string | null;
}
export interface CashTender { tenderType: "CASH"; amountPesewas: number; reference: string | null }
export type OfflineStatus = "QUEUED" | "CONFLICT" | "SYNCED";
export interface OfflineOrder {
  offlineTxId: string;
  idempotencyKey: string;
  merchantId: string;
  branchId: string | null;
  terminalId: string | null;
  cashierId: string | null;
  customerId: null;
  items: OfflineItem[];
  discountPesewas: number;
  taxPesewas: number;
  tipPesewas: number;
  totalPesewas: number;
  payments: CashTender[];
  conflictStrategy: "REJECT_FLAGGED";
  orderType: "RETAIL" | "DINE_IN" | "TAKEOUT" | "DELIVERY";
  tableId: string | null;
  createdAt: number;
  status: OfflineStatus;
  serverOrderId: string | null;
  problem: string | null;
}
export type OfflinePayload = Omit<OfflineOrder, "createdAt" | "status" | "serverOrderId" | "problem">;
export interface ReconcileResult {
  status: "SYNCED" | "REJECTED" | "CONFLICT_RESOLVED";
  serverOrderId: string | null;
  message: string;
}

export interface OfflineStore {
  put(order: OfflineOrder): Promise<void>;
  list(merchantId: string): Promise<OfflineOrder[]>;
}

export function newOfflineOrder(input: Omit<OfflinePayload, "offlineTxId" | "idempotencyKey" | "conflictStrategy">): OfflineOrder {
  if (!input.merchantId || !input.items.length || !Number.isSafeInteger(input.totalPesewas) || input.totalPesewas <= 0)
    throw new Error("Offline order requires a merchant, items and a positive total");
  if (input.payments.some((p) => p.tenderType !== "CASH" || !Number.isSafeInteger(p.amountPesewas) || p.amountPesewas <= 0))
    throw new Error("Only physical cash can be recorded offline");
  if (input.orderType === "DINE_IN" && !input.tableId) throw new Error("Dine-in requires a table");
  const id = crypto.randomUUID();
  return {
    ...input, offlineTxId: id, idempotencyKey: crypto.randomUUID(), conflictStrategy: "REJECT_FLAGGED",
    createdAt: Date.now(), status: "QUEUED", serverOrderId: null, problem: null,
  };
}

export function offlinePayload(order: OfflineOrder): OfflinePayload {
  const { createdAt: _a, status: _b, serverOrderId: _c, problem: _d, ...payload } = order;
  void _a; void _b; void _c; void _d;
  return payload;
}

export function addCashLocally(order: OfflineOrder, amountPesewas: number): OfflineOrder {
  if (order.status !== "QUEUED") throw new Error("Only unsynchronized local orders can accept offline cash");
  if (!Number.isSafeInteger(amountPesewas) || amountPesewas <= 0) throw new Error("Enter a positive cash amount");
  const received = order.payments.reduce((total, payment) => total + payment.amountPesewas, 0);
  if (received >= order.totalPesewas) throw new Error("Offline order is already fully collected");
  return { ...order, payments: [...order.payments, { tenderType: "CASH", amountPesewas, reference: null }] };
}

export async function reconcileQueued(
  store: OfflineStore, merchantId: string, send: (payload: OfflinePayload) => Promise<ReconcileResult>,
): Promise<{ synced: number; conflicts: number; remaining: number }> {
  let synced = 0, conflicts = 0;
  const orders = (await store.list(merchantId)).filter((o) => o.status === "QUEUED")
    .sort((a, b) => a.createdAt - b.createdAt);
  for (const order of orders) {
    try {
      const result = await send(offlinePayload(order));
      if ((result.status === "SYNCED" || result.status === "CONFLICT_RESOLVED") && result.serverOrderId) {
        await store.put({ ...order, status: "SYNCED", serverOrderId: result.serverOrderId, problem: null }); synced++;
      } else {
        await store.put({ ...order, status: "CONFLICT", problem: result.message || "Server did not accept order" }); conflicts++;
      }
    } catch (error) {
      // 400/409/422 are explicit reconciliation conflicts requiring staff review.
      // 401/403, 5xx and network failures keep the ORIGINAL replay key and all data queued.
      const status = typeof error === "object" && error !== null && "status" in error
        ? Number(error.status) : 0;
      if ([400, 409, 422].includes(status)) {
        await store.put({ ...order, status: "CONFLICT",
          problem: error instanceof Error ? error.message : "Order rejected" });
        conflicts++;
      } else break;
    }
  }
  return { synced, conflicts, remaining: (await store.list(merchantId)).filter((o) => o.status === "QUEUED").length };
}

const NAME = "elepay-pos-durable-v1";
const ORDER_STORE = "orders";
const CATALOG_STORE = "catalog";

function openDatabase(): Promise<IDBDatabase> {
  if (typeof indexedDB === "undefined") return Promise.reject(new Error("Offline storage is not supported on this device"));
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(NAME, 1);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(ORDER_STORE)) {
        const orders = db.createObjectStore(ORDER_STORE, { keyPath: "offlineTxId" });
        orders.createIndex("merchantId", "merchantId", { unique: false });
      }
      if (!db.objectStoreNames.contains(CATALOG_STORE))
        db.createObjectStore(CATALOG_STORE, { keyPath: "merchantId" });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("Could not open offline storage"));
    request.onblocked = () => reject(new Error("Close other old ELEPAY tabs to upgrade offline storage"));
  });
}

async function withStore<T>(
  table: string, mode: IDBTransactionMode,
  operation: (s: IDBObjectStore, done: (value: T) => void) => void,
): Promise<T> {
  const db = await openDatabase();
  return new Promise<T>((resolve, reject) => {
    let value: T;
    const tx = db.transaction(table, mode);
    tx.oncomplete = () => { db.close(); resolve(value); };
    tx.onerror = () => { db.close(); reject(tx.error || new Error("Offline write failed")); };
    tx.onabort = () => { db.close(); reject(tx.error || new Error("Offline transaction aborted")); };
    try { operation(tx.objectStore(table), (v) => { value = v; }); }
    catch (e) { tx.abort(); reject(e); }
  });
}

/** Do not acknowledge the order until the IndexedDB transaction completes. */
export const offlineDb: OfflineStore = {
  put: (order) => withStore<void>(ORDER_STORE, "readwrite", (s, done) => {
    s.put(order); done(undefined);
  }),
  list: (merchantId) => withStore<OfflineOrder[]>(ORDER_STORE, "readonly", (s, done) => {
    const r = s.index("merchantId").getAll(merchantId);
    r.onsuccess = () => done(r.result as OfflineOrder[]);
    r.onerror = () => { throw r.error; };
  }),
};

export function saveOfflineCatalog<T>(merchantId: string, rows: T[]): Promise<void> {
  return withStore<void>(CATALOG_STORE, "readwrite", (s, done) => {
    s.put({ merchantId, rows, fetchedAt: Date.now() }); done(undefined);
  });
}
export function loadOfflineCatalog<T>(merchantId: string): Promise<{ rows: T[]; fetchedAt: number } | null> {
  return withStore(CATALOG_STORE, "readonly", (s, done) => {
    const request = s.get(merchantId);
    request.onsuccess = () => done(request.result ?? null);
  });
}
