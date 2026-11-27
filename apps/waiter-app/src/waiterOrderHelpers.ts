export const WAITER_COURSES = ["APPETIZER", "MAIN", "DESSERT", "BEVERAGE", "SIDE"] as const;
export type WaiterCourse = (typeof WAITER_COURSES)[number];

export type WaiterTable = {
  id: string;
  tableNumber: string;
  capacity: number;
  status: string;
  currentOrderId: string | null;
  ownedByMe?: boolean;
};

export type WaiterLine = {
  variantId: string;
  quantity: number;
  notes: string;
  course: WaiterCourse;
};

export type WaiterOrder = {
  orderId: string;
  ticketId: string;
  tableNumber: string;
  orderNumber: string;
  orderStatus: string;
  kitchenStatus: string;
  totalPesewas: number;
  billRequested: boolean;
};

export type WaiterDraft = {
  merchant: string;
  tableId: string;
  orderId?: string | null;
  items: WaiterLine[];
  notes: string;
  key: string;
};

export type WaiterSubmission = {
  path: string;
  idempotencyKey: string;
  isAddOn: boolean;
  body:
    | {
        items: WaiterLine[];
        notes: string;
      }
    | {
        tableId: string;
        items: WaiterLine[];
        notes: string;
      };
};

export const TERMINAL_WAITER_SUBMIT_ERROR_CODES = new Set([
  "invalid_order",
  "invalid_item",
  "invalid_course",
  "table_in_use",
  "order_not_modifiable",
]);

export function normalizeWaiterCourse(raw?: string | null): WaiterCourse {
  if (!raw) return "MAIN";
  const upper = raw.trim().toUpperCase();
  if ((WAITER_COURSES as readonly string[]).includes(upper)) {
    return upper as WaiterCourse;
  }
  return "MAIN";
}

export function isOrderModifiableStatus(orderStatus: string): boolean {
  return orderStatus === "PENDING" || orderStatus === "PARTIAL";
}

export function isTableOwnedByWaiter(
  table: WaiterTable,
  orders: readonly WaiterOrder[]
): boolean {
  if (table.ownedByMe === true) return true;
  if (table.ownedByMe === false) return false;
  return Boolean(
    table.currentOrderId && orders.some((order) => order.orderId === table.currentOrderId)
  );
}

export function canAddToTable(
  table: WaiterTable,
  orders: readonly WaiterOrder[]
): boolean {
  if (!table.currentOrderId) return false;
  if (!isTableOwnedByWaiter(table, orders)) return false;
  const matchedOrder = orders.find((order) => order.orderId === table.currentOrderId);
  if (matchedOrder && !isOrderModifiableStatus(matchedOrder.orderStatus)) {
    return false;
  }
  return true;
}

export function canSelectTable(
  table: WaiterTable,
  orders: readonly WaiterOrder[]
): boolean {
  if (table.status === "AVAILABLE" && !table.currentOrderId) return true;
  return canAddToTable(table, orders);
}

export function formatTableButtonLabel(
  table: WaiterTable,
  selectedTableId: string,
  orders: readonly WaiterOrder[]
): string {
  const prefix = selectedTableId === table.id ? "✓ " : "";
  const title = canAddToTable(table, orders)
    ? `Add to Table ${table.tableNumber}`
    : `Table ${table.tableNumber}`;
  return `${prefix}${title} · ${table.status} · ${table.capacity} seats`;
}

export function normalizeStoredDraft(raw: {
  merchant: string;
  tableId: string;
  orderId?: string | null;
  items: Array<{ variantId: string; quantity: number; notes: string; course?: string | null }>;
  notes: string;
  key: string;
}): WaiterDraft {
  return {
    merchant: raw.merchant,
    tableId: raw.tableId,
    orderId: raw.orderId ?? null,
    items: raw.items.map((item) => ({
      variantId: item.variantId,
      quantity: item.quantity,
      notes: item.notes,
      course: normalizeWaiterCourse(item.course),
    })),
    notes: raw.notes,
    key: raw.key,
  };
}

export function buildWaiterSubmission(
  prefix: string,
  draft: WaiterDraft
): WaiterSubmission {
  const items: WaiterLine[] = draft.items.map((item) => ({
    variantId: item.variantId,
    quantity: item.quantity,
    notes: item.notes,
    course: normalizeWaiterCourse(item.course),
  }));

  if (draft.orderId) {
    return {
      path: `${prefix}/orders/${draft.orderId}/items`,
      idempotencyKey: draft.key,
      isAddOn: true,
      body: {
        items,
        notes: draft.notes,
      },
    };
  }

  return {
    path: `${prefix}/orders`,
    idempotencyKey: draft.key,
    isAddOn: false,
    body: {
      tableId: draft.tableId,
      items,
      notes: draft.notes,
    },
  };
}
