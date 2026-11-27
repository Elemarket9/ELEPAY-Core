/**
 * Offline-first POS catalog cache, cash-only order queue, and idempotent replay engine.
 * Mirrors apps/shared/src/kdsEngine.ts and aligns 1:1 with PosOfflineSyncService (POST /v1/pos/sync).
 */

import { ApiError } from "./api";
import type { PosCartLine, PosOrderItem, PosTerminal } from "./posHelpers";
import type { CatalogRow, DiningTable, OrderItemKdsMeta, TenderType } from "./types";

export type OfflineConflictStrategy = "SERVER_ADJUSTED" | "ACCEPT_LOCAL_FORCE" | "REJECT_FLAGGED";

export interface OfflineOrderItemInput {
  variantId: string | null;
  itemName: string;
  quantity: number;
  unitPricePesewas: number;
  discountPesewas: number;
  taxPesewas: number;
  modifiersJson: string | null;
  seatNumber: number | null;
  course: string | null;
  notes: string | null;
}

export interface OfflinePayment {
  tenderType: TenderType;
  amountPesewas: number;
  reference: string | null;
}

export interface OfflineOrderPayload {
  offlineTxId: string;
  idempotencyKey: string;
  merchantId: string;
  branchId: string | null;
  terminalId: string;
  cashierId: string | null;
  customerId: null;
  items: OfflineOrderItemInput[];
  discountPesewas: number;
  taxPesewas: number;
  tipPesewas: number;
  totalPesewas: number;
  payments: OfflinePayment[];
  conflictStrategy: OfflineConflictStrategy;
}

export interface PosOfflineSyncResult {
  offlineTxId: string;
  idempotencyKey: string;
  status: "SYNCED" | "CONFLICT_RESOLVED" | "REJECTED" | string;
  serverOrderId: string | null;
  resolutionApplied: string | null;
  message: string;
}

export interface PosOfflineQueuedOrder {
  payload: OfflineOrderPayload;
  queuedAt: number;
  attempts: number;
  lastError: string | null;
}

export interface PosOfflineSyncedRecord {
  offlineTxId: string;
  idempotencyKey: string;
  serverOrderId: string | null;
  status: string;
  message: string;
  settledAt: number;
}

export interface PosOfflineState {
  catalog: CatalogRow[];
  tables: DiningTable[];
  terminals: PosTerminal[];
  catalogCachedAt: number | null;
  queue: PosOfflineQueuedOrder[];
  failed: PosOfflineQueuedOrder[];
  history: PosOfflineSyncedRecord[];
  lastSyncAt: number | null;
}

const MAX_HISTORY = 50;
const MAX_FAILED = 50;

export function emptyPosOfflineState(): PosOfflineState {
  return {
    catalog: [],
    tables: [],
    terminals: [],
    catalogCachedAt: null,
    queue: [],
    failed: [],
    history: [],
    lastSyncAt: null,
  };
}

export function posOfflineStorageKey(merchantId: string): string {
  return `elepay.pos.offline.${merchantId}`;
}

export function posCacheBootstrap(
  state: PosOfflineState,
  data: {
    catalog: CatalogRow[];
    tables?: DiningTable[];
    terminals?: PosTerminal[];
  },
  now = Date.now(),
): PosOfflineState {
  return {
    ...state,
    catalog: data.catalog,
    tables: data.tables ?? state.tables,
    terminals: data.terminals ?? state.terminals,
    catalogCachedAt: now,
  };
}

/**
 * Only CASH tenders are allowed while the POS terminal is offline.
 * MoMo, Card, GhQR, and Bank Transfer require live server verification and are blocked offline.
 */
export function isTenderAllowedOffline(mode: string): boolean {
  return mode.trim().toUpperCase() === "CASH";
}

/**
 * Classifies whether an error indicates an offline/unreachable server (network failure or 502/503/504).
 */
export function isOfflineNetworkError(err: unknown): boolean {
  if (!(err instanceof ApiError)) {
    return true;
  }
  return err.status === 0 || err.status === 502 || err.status === 503 || err.status === 504;
}

/**
 * Permanent 4xx rejections (except 401 auth expiry and 429 rate limit) should not block the queue head.
 */
export function isPermanentSyncRejection(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status >= 400 &&
    err.status < 500 &&
    err.status !== 401 &&
    err.status !== 429
  );
}

export interface EnqueueOfflineCashOrderArgs {
  offlineTxId: string;
  idempotencyKey: string;
  merchantId: string;
  branchId: string | null;
  terminalId: string;
  cashierId: string | null;
  lines: PosCartLine[];
  discountPesewas: number;
  taxPesewas: number;
  tipPesewas?: number;
  cashReceivedPesewas: number;
  conflictStrategy?: OfflineConflictStrategy;
}

export function posEnqueueOfflineCashOrder(
  state: PosOfflineState,
  args: EnqueueOfflineCashOrderArgs,
  now = Date.now(),
): { state: PosOfflineState; queued: PosOfflineQueuedOrder; changePesewas: number } {
  const terminalId = args.terminalId?.trim();
  if (!terminalId) {
    throw new Error("A cached POS terminal is required to queue an offline cash order.");
  }
  const offlineTxId = args.offlineTxId?.trim();
  if (!offlineTxId) {
    throw new Error("offlineTxId is required.");
  }
  const idempotencyKey = args.idempotencyKey?.trim();
  if (!idempotencyKey) {
    throw new Error("idempotencyKey is required.");
  }
  if (!args.lines || args.lines.length === 0) {
    throw new Error("Cannot queue an offline order with an empty cart.");
  }

  for (const line of args.lines) {
    if (!Number.isFinite(line.qty) || line.qty <= 0) {
      throw new Error(`Invalid quantity for item ${line.label}.`);
    }
    if (!Number.isFinite(line.unitPricePesewas) || line.unitPricePesewas < 0) {
      throw new Error(`Invalid unit price for item ${line.label}.`);
    }
  }

  const existing = state.queue.find(
    (entry) =>
      entry.payload.idempotencyKey === idempotencyKey ||
      entry.payload.offlineTxId === offlineTxId,
  );
  if (existing) {
    const paid = existing.payload.payments.reduce((sum, p) => sum + p.amountPesewas, 0);
    return {
      state,
      queued: existing,
      changePesewas: Math.max(0, paid - existing.payload.totalPesewas),
    };
  }

  const subtotal = args.lines.reduce((sum, line) => sum + Math.round(line.unitPricePesewas * line.qty), 0);
  const discountPesewas = Math.min(Math.max(0, Math.round(args.discountPesewas)), subtotal);
  const taxPesewas = Math.max(0, Math.round(args.taxPesewas));
  const tipPesewas = Math.max(0, Math.round(args.tipPesewas ?? 0));
  const totalPesewas = Math.max(0, subtotal - discountPesewas) + taxPesewas + tipPesewas;
  const cashReceivedPesewas = Math.round(args.cashReceivedPesewas);

  if (!Number.isFinite(cashReceivedPesewas) || cashReceivedPesewas < totalPesewas) {
    throw new Error("Cash received must cover the order total.");
  }

  const items: OfflineOrderItemInput[] = args.lines.map((line) => ({
    variantId: line.variantId,
    itemName: line.label,
    quantity: line.qty,
    unitPricePesewas: line.unitPricePesewas,
    discountPesewas: 0,
    taxPesewas: 0,
    modifiersJson: null,
    seatNumber: line.seatNumber ?? null,
    course: line.course?.trim() ? line.course.trim().toUpperCase() : null,
    notes: null,
  }));

  const payload: OfflineOrderPayload = {
    offlineTxId,
    idempotencyKey,
    merchantId: args.merchantId,
    branchId: args.branchId,
    terminalId,
    cashierId: args.cashierId,
    customerId: null,
    items,
    discountPesewas,
    taxPesewas,
    tipPesewas,
    totalPesewas,
    payments: [
      {
        tenderType: "CASH",
        amountPesewas: cashReceivedPesewas,
        reference: `OFFLINE:${offlineTxId}`,
      },
    ],
    conflictStrategy: args.conflictStrategy ?? "SERVER_ADJUSTED",
  };

  const queued: PosOfflineQueuedOrder = {
    payload,
    queuedAt: now,
    attempts: 0,
    lastError: null,
  };

  return {
    state: {
      ...state,
      queue: [...state.queue, queued],
    },
    queued,
    changePesewas: Math.max(0, cashReceivedPesewas - totalPesewas),
  };
}

export function posMarkQueueAttemptError(
  state: PosOfflineState,
  idempotencyKey: string,
  errorMessage: string,
): PosOfflineState {
  return {
    ...state,
    queue: state.queue.map((entry) =>
      entry.payload.idempotencyKey === idempotencyKey
        ? { ...entry, attempts: entry.attempts + 1, lastError: errorMessage }
        : entry,
    ),
  };
}

export function posSettleOfflineOrder(
  state: PosOfflineState,
  idempotencyKey: string,
  result: PosOfflineSyncResult | null,
  now = Date.now(),
): PosOfflineState {
  const target = state.queue.find((entry) => entry.payload.idempotencyKey === idempotencyKey);
  const queue = state.queue.filter((entry) => entry.payload.idempotencyKey !== idempotencyKey);

  if (!target) {
    return state;
  }

  if (result === null) {
    const failed = [...state.failed, target].slice(-MAX_FAILED);
    return {
      ...state,
      queue,
      failed,
    };
  }

  const record: PosOfflineSyncedRecord = {
    offlineTxId: result.offlineTxId ?? target.payload.offlineTxId,
    idempotencyKey: result.idempotencyKey ?? target.payload.idempotencyKey,
    serverOrderId: result.serverOrderId ?? null,
    status: result.status,
    message: result.message,
    settledAt: now,
  };

  const history = [record, ...state.history].slice(0, MAX_HISTORY);
  return {
    ...state,
    queue,
    history,
    lastSyncAt: now,
  };
}

export function posRetryFailedOrder(state: PosOfflineState, idempotencyKey: string): PosOfflineState {
  const target = state.failed.find((entry) => entry.payload.idempotencyKey === idempotencyKey);
  if (!target) return state;
  return {
    ...state,
    failed: state.failed.filter((entry) => entry.payload.idempotencyKey !== idempotencyKey),
    queue: [...state.queue, { ...target, attempts: 0, lastError: null }],
  };
}

export function posOfflineBackoff(failures: number): number {
  return failures <= 0 ? 5000 : Math.min(30000, 5000 * 2 ** failures);
}

/**
 * Replays queued offline cash orders FIFO via `sendFn` (which posts to `POST /v1/pos/sync`).
 * - Successful responses settle into `history`.
 * - Permanent 4xx errors (other than 401/429) move the item to `failed` and continue draining.
 * - Transient/network/5xx/401/429 errors stop replay and leave the item at queue head for retry.
 */
export async function replayOfflineQueue(
  initialState: PosOfflineState,
  sendFn: (payload: OfflineOrderPayload) => Promise<PosOfflineSyncResult>,
  onStateChange?: (next: PosOfflineState) => void,
  nowFn: () => number = () => Date.now(),
): Promise<{
  state: PosOfflineState;
  synced: number;
  failed: number;
  stoppedByError: unknown | null;
}> {
  let current = initialState;
  let synced = 0;
  let failed = 0;

  while (current.queue.length > 0) {
    const entry = current.queue[0];
    const key = entry.payload.idempotencyKey;
    try {
      const result = await sendFn(entry.payload);
      current = posSettleOfflineOrder(current, key, result, nowFn());
      synced += 1;
      onStateChange?.(current);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      if (isPermanentSyncRejection(err)) {
        current = posMarkQueueAttemptError(current, key, message);
        current = posSettleOfflineOrder(current, key, null, nowFn());
        failed += 1;
        onStateChange?.(current);
      } else {
        current = posMarkQueueAttemptError(current, key, message);
        onStateChange?.(current);
        return { state: current, synced, failed, stoppedByError: err };
      }
    }
  }

  return { state: current, synced, failed, stoppedByError: null };
}

/**
 * Phase 2 helper: returns only non-voided items that have not yet been sent to KDS.
 */
export function filterUnfiredOrderItems<T extends PosOrderItem & OrderItemKdsMeta>(
  items: T[],
  locallyFiredIds?: ReadonlySet<string>,
): T[] {
  return items.filter(
    (it) =>
      !it.voided &&
      it.kdsRound == null &&
      !it.sentAt &&
      !(locallyFiredIds && locallyFiredIds.has(it.id)),
  );
}

/**
 * Phase 2 helper: computes the next KDS firing round number for an order.
 */
export function computeNextKdsRound(
  items: (PosOrderItem & OrderItemKdsMeta)[],
  localLastRound = 0,
): number {
  let maxRound = Math.max(0, localLastRound);
  for (const it of items) {
    if (typeof it.kdsRound === "number" && it.kdsRound > maxRound) {
      maxRound = it.kdsRound;
    }
  }
  return maxRound + 1;
}

/**
 * Phase 2 helper: formats the idempotent KDS clientRef for a given order and round.
 */
export function kdsRoundClientRef(orderId: string, round: number): string {
  return `pos:${orderId}:r${round}`;
}

/**
 * Phase 5 helper: keys cart lines by (variantId, seatNumber) so multiple seats
 * can order the same menu item independently.
 */
export function cartLineKey(variantId: string, seatNumber?: number | null): string {
  return `${variantId}::${seatNumber ?? 0}`;
}
