import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Modal,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  useWindowDimensions,
  View,
} from "react-native";
import AsyncStorage from "@react-native-async-storage/async-storage";
import {
  buildPosOrderPayload,
  cartLineKey,
  catalogItemLabel,
  CatalogRow,
  colors,
  computeCartTotals,
  DiningTable,
  ElepayClient,
  emptyPosOfflineState,
  filterUnfiredOrderItems,
  formatMoney,
  HeldCart,
  isFeatureNotEnabled,
  isOfflineNetworkError,
  isTenderAllowedOffline,
  MerchantView,
  newKey,
  ORDER_TYPES,
  OrderItemKdsMeta,
  OrderType,
  parseHeldCartPayload,
  parseMoney,
  posCacheBootstrap,
  PosCartLine,
  posEnqueueOfflineCashOrder,
  posOfflineBackoff,
  PosOfflineState,
  posOfflineStorageKey,
  PosOfflineSyncResult,
  PosOrder,
  PosOrderItem,
  PosReceipt,
  PosShift,
  PosTerminal,
  radius,
  replayOfflineQueue,
  spacing,
  TenderType,
} from "@elepay/mobile-core";
import { TerminalShiftModal } from "../components/TerminalShiftModal";
import { TenderModal } from "../components/TenderModal";

type ServerOrderItem = PosOrderItem & OrderItemKdsMeta;

const ALL_TENDERS: { type: TenderType; label: string }[] = [
  { type: "CASH", label: "Cash" },
  { type: "EXTERNAL_MOMO", label: "Own MoMo" },
  { type: "EXTERNAL_HUBTEL", label: "Hubtel" },
  { type: "EXTERNAL_CARD", label: "Ext Card" },
  { type: "BANK_TRANSFER", label: "Bank" },
  { type: "MOMO", label: "ELEPAY MoMo" },
  { type: "CARD", label: "ELEPAY Card" },
  { type: "GHQR", label: "ELEPAY GhQR" },
];

interface Props {
  client: ElepayClient;
  merchant: MerchantView;
  preselectedTableId: string | null;
  onClearPreselectedTable: () => void;
  preselectedOrderId?: string | null;
  onClearPreselectedOrder?: () => void;
}

export function PosRegisterScreen({
  client,
  merchant,
  preselectedTableId,
  onClearPreselectedTable,
  preselectedOrderId,
  onClearPreselectedOrder,
}: Props) {
  const { width } = useWindowDimensions();
  const isTablet = width >= 820;
  const storageKey = posOfflineStorageKey(merchant.id);

  const [offlineState, setOfflineState] = useState<PosOfflineState>(emptyPosOfflineState);
  const offlineStateRef = useRef<PosOfflineState>(offlineState);
  const [hydratedKey, setHydratedKey] = useState<string | null>(null);
  const [online, setOnline] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const kickSyncRef = useRef<(() => void) | null>(null);

  const [catalog, setCatalog] = useState<CatalogRow[]>([]);
  const [tables, setTables] = useState<DiningTable[]>([]);
  const [terminals, setTerminals] = useState<PosTerminal[]>([]);
  const [selectedTerminal, setSelectedTerminal] = useState<PosTerminal | null>(null);
  const [cashier, setCashier] = useState<{ id: string; badgeNumber: string; name?: string } | null>(null);
  const [shift, setShift] = useState<PosShift | null>(null);
  const [heldCarts, setHeldCarts] = useState<HeldCart[]>([]);

  const [search, setSearch] = useState("");
  const [selectedCategory, setSelectedCategory] = useState("ALL");
  const [cart, setCart] = useState<PosCartLine[]>([]);
  const [serverItems, setServerItems] = useState<ServerOrderItem[]>([]);
  const [firedItemIds, setFiredItemIds] = useState<Set<string>>(() => new Set());
  const [lastFiredRound, setLastFiredRound] = useState(0);
  const [orderType, setOrderType] = useState<OrderType>("DINE_IN");
  const [tableId, setTableId] = useState<string>("");
  const [activeSeat, setActiveSeat] = useState<number | null>(null);
  const [discount, setDiscount] = useState("");
  const [taxPercent, setTaxPercent] = useState("0");

  const [order, setOrder] = useState<PosOrder | null>(null);
  const [orderKey, setOrderKey] = useState<string>(() => newKey());
  const [addOnKey, setAddOnKey] = useState<string>(() => newKey());
  const [receipt, setReceipt] = useState<{ receipt: PosReceipt; order: PosOrder } | null>(null);

  const [showTerminalModal, setShowTerminalModal] = useState(false);
  const [showTenderModal, setShowTenderModal] = useState(false);
  const [showVoidModal, setShowVoidModal] = useState(false);
  const [voidReason, setVoidReason] = useState("");
  const [voidManagerBadge, setVoidManagerBadge] = useState("");
  const [voidManagerPin, setVoidManagerPin] = useState("");
  const [voidRefundPayments, setVoidRefundPayments] = useState(false);
  const [voidError, setVoidError] = useState<string | null>(null);
  const [voidSubmitting, setVoidSubmitting] = useState(false);
  const [showOfflineCashModal, setShowOfflineCashModal] = useState(false);
  const [offlineCashInput, setOfflineCashInput] = useState("");
  const [offlineCashError, setOfflineCashError] = useState<string | null>(null);
  const [offlineReceipt, setOfflineReceipt] = useState<{
    offlineTxId: string;
    totalPesewas: number;
    cashReceivedPesewas: number;
    changePesewas: number;
    itemCount: number;
  } | null>(null);

  const [refreshing, setRefreshing] = useState(false);
  const [moduleDisabled, setModuleDisabled] = useState(false);
  const [loadingExisting, setLoadingExisting] = useState(false);
  const [existingSelection, setExistingSelection] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const updateOffline = useCallback(
    (fn: (s: PosOfflineState) => PosOfflineState) => {
      const next = fn(offlineStateRef.current);
      offlineStateRef.current = next;
      setOfflineState(next);
      void AsyncStorage.setItem(storageKey, JSON.stringify(next)).catch(() => {});
    },
    [storageKey],
  );

  // Hydrate offline catalog cache & queued cash orders before live sync
  useEffect(() => {
    let cancelled = false;
    const empty = emptyPosOfflineState();
    offlineStateRef.current = empty;
    setOfflineState(empty);
    setHydratedKey(null);

    AsyncStorage.getItem(storageKey)
      .then((raw) => {
        if (cancelled) return;
        if (raw) {
          const parsed = { ...emptyPosOfflineState(), ...(JSON.parse(raw) as PosOfflineState) };
          offlineStateRef.current = parsed;
          setOfflineState(parsed);
          if (parsed.catalog.length > 0) setCatalog(parsed.catalog);
          if (parsed.tables.length > 0) setTables(parsed.tables);
          if (parsed.terminals.length > 0) {
            setTerminals(parsed.terminals);
            setSelectedTerminal((prev) =>
              prev && parsed.terminals.some((t) => t.id === prev.id) ? prev : (parsed.terminals[0] ?? null),
            );
          }
        }
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setHydratedKey(storageKey);
      });

    return () => {
      cancelled = true;
    };
  }, [storageKey]);

  useEffect(() => {
    if (preselectedTableId) {
      setOrderType("DINE_IN");
      // Existing table orders are selected in the register to load their server balance.
      setExistingSelection(preselectedTableId);
      onClearPreselectedTable();
    }
  }, [preselectedTableId, onClearPreselectedTable]);

  const loadAll = useCallback(async () => {
    setRefreshing(true);
    try {
      const [cat, tbls, terms] = await Promise.all([
        client.call<CatalogRow[]>(`/v1/inventory/catalog/merchant/${merchant.id}`),
        client.call<DiningTable[]>(`/v1/restaurant/tables/merchant/${merchant.id}`).catch(() => []),
        client.call<PosTerminal[]>(`/v1/pos/terminals/merchant/${merchant.id}`).catch((e) => {
          if (isFeatureNotEnabled(e)) setModuleDisabled(true);
          return [] as PosTerminal[];
        }),
      ]);
      setOnline(true);
      setCatalog(cat);
      setTables(tbls);
      setTerminals(terms);
      setSelectedTerminal((prev) => (prev && terms.some((t) => t.id === prev.id) ? prev : (terms[0] ?? null)));
      updateOffline((s) => posCacheBootstrap(s, { catalog: cat, tables: tbls, terminals: terms }));
      setError(null);
    } catch (e) {
      if (isFeatureNotEnabled(e)) {
        setModuleDisabled(true);
      } else if (isOfflineNetworkError(e)) {
        setOnline(false);
        const cached = offlineStateRef.current;
        if (cached.catalog.length > 0) {
          setCatalog(cached.catalog);
          if (cached.tables.length > 0) setTables(cached.tables);
          if (cached.terminals.length > 0) {
            setTerminals(cached.terminals);
            setSelectedTerminal((prev) => prev ?? cached.terminals[0] ?? null);
          }
          setNotice("Offline mode — using cached catalog. Cash orders will sync on reconnect.");
          setError(null);
        } else {
          setError("Offline and no cached catalog available. Connect once to load menu.");
        }
      } else {
        setError(e instanceof Error ? e.message : String(e));
      }
    } finally {
      setRefreshing(false);
    }
  }, [client, merchant.id, updateOffline]);

  useEffect(() => {
    if (hydratedKey !== storageKey) return;
    void loadAll();
  }, [hydratedKey, storageKey, loadAll]);

  // Background auto-replay loop for queued offline cash orders + reconnect probe
  useEffect(() => {
    if (hydratedKey !== storageKey) return;
    let stopped = false;
    let inFlight = false;
    let failures = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const persist = (next: PosOfflineState) => {
      offlineStateRef.current = next;
      setOfflineState(next);
      void AsyncStorage.setItem(storageKey, JSON.stringify(next)).catch(() => {});
    };

    const runSync = async () => {
      if (stopped || inFlight) return;
      if (timer) {
        clearTimeout(timer);
        timer = undefined;
      }
      const hasQueued = offlineStateRef.current.queue.length > 0;
      if (!hasQueued && online) {
        return;
      }
      inFlight = true;
      setSyncing(true);
      try {
        if (hasQueued) {
          const res = await replayOfflineQueue(
            offlineStateRef.current,
            (payload) => client.call<PosOfflineSyncResult>("/v1/pos/sync", { body: payload }),
            persist,
          );
          if (stopped) return;
          if (res.stoppedByError) {
            if (isOfflineNetworkError(res.stoppedByError)) {
              setOnline(false);
            }
            failures += 1;
          } else {
            failures = 0;
            setOnline(true);
            if (res.synced > 0) {
              setNotice(
                res.failed > 0
                  ? `Synced ${res.synced} offline order(s); ${res.failed} rejected by server.`
                  : `Synced ${res.synced} offline order(s).`,
              );
              void loadAll();
            } else if (res.failed > 0) {
              setError(`${res.failed} offline order(s) rejected by server during sync.`);
            }
          }
        } else {
          // Offline with empty queue: probe connectivity
          const cat = await client.call<CatalogRow[]>(`/v1/inventory/catalog/merchant/${merchant.id}`);
          if (stopped) return;
          failures = 0;
          setOnline(true);
          setCatalog(cat);
          persist(posCacheBootstrap(offlineStateRef.current, { catalog: cat }));
          setNotice("Back online — catalog refreshed.");
        }
      } catch (e) {
        if (stopped) return;
        if (isOfflineNetworkError(e)) {
          setOnline(false);
        }
        failures += 1;
      } finally {
        inFlight = false;
        setSyncing(false);
      }

      if (!stopped && (offlineStateRef.current.queue.length > 0 || failures > 0)) {
        timer = setTimeout(() => void runSync(), posOfflineBackoff(failures));
      }
    };

    kickSyncRef.current = () => {
      if (stopped) return;
      failures = 0;
      if (timer) {
        clearTimeout(timer);
        timer = undefined;
      }
      void runSync();
    };

    if (offlineStateRef.current.queue.length > 0 || !online) {
      timer = setTimeout(() => void runSync(), 1500);
    }

    return () => {
      stopped = true;
      kickSyncRef.current = null;
      if (timer) clearTimeout(timer);
    };
  }, [hydratedKey, storageKey, client, merchant.id, online, loadAll]);

  useEffect(() => {
    if (!selectedTerminal || !online) {
      if (!selectedTerminal) {
        setShift(null);
        setHeldCarts([]);
      }
      return;
    }
    client
      .call<PosShift>(`/v1/pos/shifts/active/${selectedTerminal.id}`)
      .then(setShift)
      .catch(() => setShift(null));
    client
      .call<HeldCart[]>(`/v1/pos/carts/held/${selectedTerminal.id}`)
      .then(setHeldCarts)
      .catch(() => setHeldCarts([]));
  }, [client, selectedTerminal, online]);

  const categories = useMemo(
    () => Array.from(new Set(catalog.map((r) => r.categoryName).filter((c): c is string => Boolean(c)))).sort(),
    [catalog],
  );
  const cartQtyMap = useMemo(() => {
    const map = new Map<string, number>();
    for (const c of cart) {
      map.set(c.variantId, (map.get(c.variantId) ?? 0) + c.qty);
    }
    return map;
  }, [cart]);
  const shown = catalog.filter((r) => {
    const matchCat = selectedCategory === "ALL" || r.categoryName === selectedCategory;
    const matchSearch = `${catalogItemLabel(r)} ${r.sku} ${r.barcode ?? ""}`.toLowerCase().includes(search.toLowerCase());
    return matchCat && matchSearch;
  });

  const selectedTable = useMemo(() => tables.find((t) => t.id === tableId) ?? null, [tables, tableId]);
  const seatCount = useMemo(() => {
    const cap = selectedTable?.capacity ?? 6;
    return Math.min(12, Math.max(4, cap));
  }, [selectedTable]);

  const canAddItems = !order || order.status === "PENDING" || order.status === "PARTIAL";
  const unfiredServerItems = useMemo(
    () => filterUnfiredOrderItems(serverItems, firedItemIds),
    [serverItems, firedItemIds],
  );

  const discountP = discount.trim() ? parseMoney(discount) : 0;
  const taxRate = Number(taxPercent);
  const calculated = computeCartTotals(cart, order ? 0 : discountP, order ? 0 : taxRate);
  const { subtotal, effectiveDiscount, tax } = calculated;
  const serverRemaining = order ? Math.max(0, order.totalPesewas - order.paidPesewas) : 0;
  const total = order ? serverRemaining + calculated.subtotal : calculated.total;

  const resetRegister = useCallback(() => {
    setCart([]);
    setServerItems([]);
    setFiredItemIds(new Set());
    setLastFiredRound(0);
    setDiscount("");
    setOrder(null);
    setOrderKey(newKey());
    setAddOnKey(newKey());
    setTableId("");
    setActiveSeat(null);
  }, []);

  const addItem = (row: CatalogRow) => {
    if (!canAddItems) return;
    const seat = orderType === "DINE_IN" ? activeSeat : null;
    const targetKey = cartLineKey(row.variantId, seat);
    setCart((prev) =>
      prev.some((x) => cartLineKey(x.variantId, x.seatNumber) === targetKey)
        ? prev.map((x) => (cartLineKey(x.variantId, x.seatNumber) === targetKey ? { ...x, qty: x.qty + 1 } : x))
        : [
            ...prev,
            {
              variantId: row.variantId,
              label: catalogItemLabel(row),
              unitPricePesewas: row.retailPricePesewas,
              qty: 1,
              seatNumber: seat,
            },
          ],
    );
  };

  const setQty = (lineKey: string, qty: number) => {
    if (!canAddItems) return;
    setCart((prev) =>
      qty <= 0
        ? prev.filter((x) => cartLineKey(x.variantId, x.seatNumber) !== lineKey)
        : prev.map((x) => (cartLineKey(x.variantId, x.seatNumber) === lineKey ? { ...x, qty } : x)),
    );
  };

  const cycleLineSeat = (lineKey: string) => {
    if (!canAddItems || orderType !== "DINE_IN") return;
    setCart((prev) => {
      const idx = prev.findIndex((x) => cartLineKey(x.variantId, x.seatNumber) === lineKey);
      if (idx < 0) return prev;
      const line = prev[idx];
      const nextSeat = line.seatNumber == null ? 1 : line.seatNumber >= seatCount ? null : line.seatNumber + 1;
      const nextKey = cartLineKey(line.variantId, nextSeat);
      const mergeIdx = prev.findIndex((x, i) => i !== idx && cartLineKey(x.variantId, x.seatNumber) === nextKey);
      if (mergeIdx >= 0) {
        return prev
          .map((x, i) => (i === mergeIdx ? { ...x, qty: x.qty + line.qty } : x))
          .filter((_, i) => i !== idx);
      }
      return prev.map((x, i) => (i === idx ? { ...x, seatNumber: nextSeat } : x));
    });
  };

  const hydrateLoadedOrder = useCallback(
    (loaded: PosOrder, items: ServerOrderItem[], resolvedTableId: string) => {
      const normalizedType: OrderType =
        loaded.orderType === "TAKEAWAY" ||
        loaded.orderType === "DELIVERY" ||
        loaded.orderType === "RETAIL"
          ? loaded.orderType
          : "DINE_IN";
      setOrder(loaded);
      setOrderType(normalizedType);
      setTableId(resolvedTableId);
      setServerItems(items);
      setCart([]);
      setAddOnKey(newKey());
      setFiredItemIds(new Set(items.filter((i) => i.kdsRound != null || !!i.sentAt).map((i) => i.id)));
      setLastFiredRound(Math.max(0, ...items.map((i) => i.kdsRound ?? 0)));
    },
    [],
  );

  async function openTableOrder(t: DiningTable) {
    if (order || cart.length || loadingExisting) {
      setError("Finish or clear your current cart first.");
      return;
    }
    if (!t.currentOrderId) {
      setTableId(t.id);
      return;
    }
    setLoadingExisting(true);
    try {
      if (!selectedTerminal || !cashier || !shift || shift.cashierId !== cashier.id) {
        throw new Error("Authenticate a cashier and open a shift first.");
      }
      const current = await client.call<PosOrder & { terminalId?: string | null; cashierId?: string | null }>(
        `/v1/pos/orders/${t.currentOrderId}`,
      );
      if (current.status !== "PENDING" && current.status !== "PARTIAL") {
        throw new Error("Order is not an open pending or partial order.");
      }
      const existing =
        current.terminalId === selectedTerminal.id && current.cashierId === cashier.id
          ? current
          : await client.call<PosOrder>(
              `/v1/restaurant/waiter/${merchant.id}/orders/${t.currentOrderId}/cashier`,
              { body: { terminalId: selectedTerminal.id, cashierId: cashier.id } },
            );
      if (existing.status !== "PENDING" && existing.status !== "PARTIAL") {
        throw new Error("Order is not an open pending or partial order.");
      }
      const items = await client.call<ServerOrderItem[]>(`/v1/pos/orders/${existing.id}/items`);
      hydrateLoadedOrder(existing, items, t.id);
      setNotice("Table order loaded. Add more items, fire add-ons to kitchen, or collect payment.");
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoadingExisting(false);
    }
  }

  const openOrderById = useCallback(
    async (orderId: string) => {
      if (loadingExisting) return;
      setLoadingExisting(true);
      try {
        const [loaded, items] = await Promise.all([
          client.call<PosOrder>(`/v1/pos/orders/${orderId}`),
          client.call<ServerOrderItem[]>(`/v1/pos/orders/${orderId}/items`),
        ]);
        const matchedTable = tables.find((t) => t.currentOrderId === orderId);
        hydrateLoadedOrder(loaded, items, matchedTable?.id ?? "");
        setNotice(`Loaded order ${loaded.orderNumber}.`);
        setError(null);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setLoadingExisting(false);
      }
    },
    [client, hydrateLoadedOrder, loadingExisting, tables],
  );

  useEffect(() => {
    if (!preselectedOrderId) return;
    void openOrderById(preselectedOrderId);
    onClearPreselectedOrder?.();
  }, [preselectedOrderId, onClearPreselectedOrder, openOrderById]);

  useEffect(() => {
    if (!existingSelection || !tables.length) return;
    const selected = tables.find((t) => t.id === existingSelection);
    setExistingSelection(null);
    if (selected) void openTableOrder(selected);
    // The selection is consumed once after tables are loaded.
  }, [existingSelection, tables]);

  async function ensureOrder(): Promise<PosOrder> {
    if (loadingExisting) throw new Error("Wait for table order to load.");
    if (order) {
      if (cart.length === 0) return order;
      const addPayload = buildPosOrderPayload({
        merchantId: merchant.id,
        branchId: selectedTerminal?.branchId ?? null,
        terminalId: selectedTerminal?.id ?? null,
        cashierId: cashier?.id ?? null,
        orderType,
        lines: cart,
        discountPesewas: 0,
        taxPesewas: 0,
      });
      const updated = await client.call<PosOrder>(`/v1/pos/orders/${order.id}/items`, {
        idempotencyKey: addOnKey,
        body: { items: addPayload.items },
      });
      const latestItems = await client.call<ServerOrderItem[]>(`/v1/pos/orders/${updated.id}/items`);
      setOrder(updated);
      setServerItems(latestItems);
      setCart([]);
      setAddOnKey(newKey());
      return updated;
    }
    if (tableId && orderType === "DINE_IN") {
      const current = await client.call<DiningTable[]>(`/v1/restaurant/tables/merchant/${merchant.id}`);
      if (current.find((t) => t.id === tableId)?.currentOrderId) {
        throw new Error("Table has an existing order. Open it instead of creating another.");
      }
    }
    if (discountP === null) throw new Error("Enter discount like 5.00");
    const payload = buildPosOrderPayload({
      merchantId: merchant.id,
      branchId: selectedTerminal?.branchId ?? null,
      terminalId: selectedTerminal?.id ?? null,
      cashierId: cashier?.id ?? null,
      orderType,
      lines: cart,
      discountPesewas: effectiveDiscount,
      taxPesewas: tax,
    });
    const created = await client.call<PosOrder>("/v1/pos/orders", {
      idempotencyKey: orderKey,
      body: payload,
    });
    setOrder(created);
    const createdItems = await client.call<ServerOrderItem[]>(`/v1/pos/orders/${created.id}/items`).catch(() => []);
    setServerItems(createdItems);
    setCart([]);
    setAddOnKey(newKey());
    if (orderType === "DINE_IN" && tableId) {
      await client
        .call(`/v1/restaurant/tables/${tableId}/seat?orderId=${encodeURIComponent(created.id)}`, { method: "POST" })
        .catch((e) => setError(`Order created, but could not seat table: ${e instanceof Error ? e.message : String(e)}`));
    }
    return created;
  }

  async function sendToKitchen(targetOrder: PosOrder) {
    if (orderType === "RETAIL") return;
    const fired = await client.call<{ id?: string; round?: number; items?: unknown[] } | null>(
      `/v1/pos/orders/${targetOrder.id}/fire-kds`,
      {
        method: "POST",
        body: {
          tableId: tableId || null,
          serverName: cashier?.name ?? cashier?.badgeNumber ?? null,
          notes: null,
        },
      },
    );
    const refreshedItems = await client.call<ServerOrderItem[]>(`/v1/pos/orders/${targetOrder.id}/items`);
    setServerItems(refreshedItems);
    const nextFired = new Set<string>();
    let maxRound = lastFiredRound;
    for (const it of refreshedItems) {
      if (it.kdsRound != null || it.sentAt) {
        nextFired.add(it.id);
        if (it.kdsRound != null && it.kdsRound > maxRound) {
          maxRound = it.kdsRound;
        }
      }
    }
    setFiredItemIds(nextFired);
    setLastFiredRound(maxRound);
    setError(null);
    if (!fired) {
      setNotice("All items have already been sent to the kitchen.");
      return;
    }
    const round = fired.round ?? Math.max(1, maxRound);
    const itemCount = Array.isArray(fired.items) ? fired.items.length : 0;
    setNotice(
      round > 1
        ? `Add-on Round ${round}${itemCount > 0 ? ` (${itemCount} item${itemCount === 1 ? "" : "s"})` : ""} sent to Kitchen Display (KDS).`
        : "Order sent to Kitchen Display (KDS).",
    );
  }

  function openVoidModal() {
    if (!order) return;
    setVoidReason("");
    setVoidManagerBadge("");
    setVoidManagerPin("");
    setVoidRefundPayments(order.status === "PARTIAL");
    setVoidError(null);
    setShowVoidModal(true);
  }

  async function submitVoidOrder() {
    if (!order) return;
    const trimmedReason = voidReason.trim();
    const trimmedPin = voidManagerPin.trim();
    if (!trimmedReason) {
      setVoidError("Void reason is required.");
      return;
    }
    if (!trimmedPin) {
      setVoidError("Manager PIN is required.");
      return;
    }
    setVoidSubmitting(true);
    setVoidError(null);
    setError(null);
    try {
      await client.call<PosOrder>(`/v1/pos/orders/${order.id}/void`, {
        method: "POST",
        body: {
          reason: trimmedReason,
          managerBadgeNumber: voidManagerBadge.trim() || null,
          managerPin: trimmedPin,
          refundPayments: order.status === "PARTIAL" ? voidRefundPayments : false,
        },
      });
      setShowVoidModal(false);
      resetRegister();
      setNotice("Order voided. Ready for a new order.");
      void loadAll();
    } catch (e) {
      setVoidError(e instanceof Error ? e.message : String(e));
    } finally {
      setVoidSubmitting(false);
    }
  }

  async function holdCart() {
    if (!selectedTerminal || !cashier) {
      setError("Select a terminal and verify cashier PIN before holding a cart.");
      setShowTerminalModal(true);
      return;
    }
    if (cart.length === 0) return;
    try {
      const selectedTbl = tables.find((t) => t.id === tableId);
      await client.call("/v1/pos/carts/hold", {
        body: {
          terminalId: selectedTerminal.id,
          cashierId: cashier.id,
          merchantId: merchant.id,
          customerName: selectedTbl ? `Table ${selectedTbl.tableNumber}` : "Walk-in",
          cartPayloadJson: JSON.stringify({ items: cart, discount, taxPercent, orderType, tableId: tableId || null }),
          totalPesewas: total,
        },
      });
      resetRegister();
      setNotice("Cart held.");
      const list = await client.call<HeldCart[]>(`/v1/pos/carts/held/${selectedTerminal.id}`).catch(() => []);
      setHeldCarts(list);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  async function recallCart(hc: HeldCart) {
    try {
      const recalled = await client.call<HeldCart>(`/v1/pos/carts/held/${hc.id}/recall`, { method: "POST" });
      const parsed = parseHeldCartPayload(recalled.cartPayloadJson);
      if (parsed) {
        resetRegister();
        setCart(parsed.items);
        setDiscount(parsed.discount);
        setTaxPercent(parsed.taxPercent);
        setOrderType(parsed.orderType);
        setTableId(parsed.tableId ?? "");
      }
      if (selectedTerminal) {
        const list = await client.call<HeldCart[]>(`/v1/pos/carts/held/${selectedTerminal.id}`).catch(() => []);
        setHeldCarts(list);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  async function handleOrderPaid(paidOrder: PosOrder) {
    try {
      const r = await client.call<PosReceipt>(`/v1/pos/orders/${paidOrder.id}/receipt`, { method: "POST" });
      const latest = await client.call<PosOrder>(`/v1/pos/orders/${paidOrder.id}`);
      setShowTenderModal(false);
      setReceipt({ receipt: r, order: latest });
      resetRegister();
      void loadAll();
    } catch (e) {
      setShowTenderModal(false);
      setError(`Payment recorded, but receipt failed: ${e instanceof Error ? e.message : String(e)}`);
      if (paidOrder.status === "PAID") {
        resetRegister();
        void loadAll();
      }
    }
  }

  function handleChargePress() {
    setError(null);
    if (!online) {
      if (order) {
        setError("Cannot settle an online server order while offline. Reconnect to collect payment.");
        return;
      }
      if (!selectedTerminal) {
        setError("No cached POS terminal available. Connect once to select a terminal before offline cash sales.");
        return;
      }
      if (cart.length === 0) return;
      setOfflineCashInput((total / 100).toFixed(2));
      setOfflineCashError(null);
      setShowOfflineCashModal(true);
      return;
    }
    setShowTenderModal(true);
  }

  function confirmOfflineCashCheckout() {
    if (!selectedTerminal) {
      setOfflineCashError("No cached terminal selected.");
      return;
    }
    const received = parseMoney(offlineCashInput);
    if (received === null || received < total) {
      setOfflineCashError(`Cash tendered must be at least ${formatMoney(total)}.`);
      return;
    }
    try {
      const txId = newKey();
      const idemKey = newKey();
      const result = posEnqueueOfflineCashOrder(offlineStateRef.current, {
        offlineTxId: txId,
        idempotencyKey: idemKey,
        merchantId: merchant.id,
        branchId: selectedTerminal.branchId ?? null,
        terminalId: selectedTerminal.id,
        cashierId: cashier?.id ?? null,
        lines: cart,
        discountPesewas: effectiveDiscount,
        taxPesewas: tax,
        cashReceivedPesewas: received,
      });
      updateOffline(() => result.state);
      const itemCount = cart.reduce((sum, l) => sum + l.qty, 0);
      setShowOfflineCashModal(false);
      setOfflineReceipt({
        offlineTxId: txId,
        totalPesewas: result.queued.payload.totalPesewas,
        cashReceivedPesewas: received,
        changePesewas: result.changePesewas,
        itemCount,
      });
      resetRegister();
      setNotice("Offline cash order queued. It will sync automatically when back online.");
      kickSyncRef.current?.();
    } catch (e) {
      setOfflineCashError(e instanceof Error ? e.message : String(e));
    }
  }

  if (moduleDisabled) {
    return (
      <View style={[styles.root, { padding: spacing.lg }]}>
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>POS Register Disabled</Text>
          <Text style={styles.mutedText}>The POS module is currently disabled in this environment.</Text>
        </View>
      </View>
    );
  }

  const hasOrderItems = serverItems.length > 0 || cart.length > 0;
  const canFireToKitchen = orderType !== "RETAIL" && (cart.length > 0 || unfiredServerItems.length > 0);
  const queuedCount = offlineState.queue.length;

  const catalogPane = (
    <View style={isTablet ? { flex: 1.35 } : undefined}>
      <TextInput
        style={styles.searchInput}
        value={search}
        onChangeText={setSearch}
        placeholder="Search menu item or SKU…"
        placeholderTextColor={colors.subtle}
      />

      {categories.length > 0 && (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.catScroll} contentContainerStyle={{ gap: 6 }}>
          <Pressable
            style={[styles.catPill, selectedCategory === "ALL" && styles.catPillActive]}
            onPress={() => setSelectedCategory("ALL")}
          >
            <Text style={[styles.catPillText, selectedCategory === "ALL" && styles.catPillTextActive]}>All</Text>
          </Pressable>
          {categories.map((cat) => (
            <Pressable
              key={cat}
              style={[styles.catPill, selectedCategory === cat && styles.catPillActive]}
              onPress={() => setSelectedCategory(cat)}
            >
              <Text style={[styles.catPillText, selectedCategory === cat && styles.catPillTextActive]}>{cat}</Text>
            </Pressable>
          ))}
        </ScrollView>
      )}

      {catalog.length === 0 ? (
        <View style={styles.emptyCard}>
          <Text style={styles.mutedText}>No menu products yet. Add products in Inventory &amp; Stock.</Text>
        </View>
      ) : (
        <View style={styles.productGrid}>
          {shown.map((r) => {
            const inCart = cartQtyMap.get(r.variantId) ?? 0;
            const lowStock = r.trackInventory && Number(r.onHand) <= Number(r.reorderPoint);
            return (
              <Pressable
                key={r.variantId}
                style={[styles.productTile, inCart > 0 && styles.productTileActive]}
                disabled={!canAddItems}
                onPress={() => addItem(r)}
              >
                {inCart > 0 && (
                  <View style={styles.qtyBadge}>
                    <Text style={styles.qtyBadgeText}>{inCart}</Text>
                  </View>
                )}
                <Text style={styles.productName} numberOfLines={2}>
                  {catalogItemLabel(r)}
                </Text>
                <View>
                  <Text style={styles.productPrice}>{formatMoney(r.retailPricePesewas)}</Text>
                  {r.trackInventory && (
                    <Text style={[styles.productStock, lowStock && { color: colors.warn, fontWeight: "700" }]}>
                      {Number(r.onHand)} in stock
                    </Text>
                  )}
                </View>
              </Pressable>
            );
          })}
        </View>
      )}

      {heldCarts.length > 0 && (
        <View style={[styles.card, { marginTop: spacing.md }]}>
          <Text style={styles.sectionTitle}>Held Carts ({heldCarts.length})</Text>
          {heldCarts.map((hc) => (
            <View key={hc.id} style={styles.heldRow}>
              <Text style={{ fontWeight: "700", color: colors.ink }}>{hc.customerName ?? "Walk-in"}</Text>
              <Text style={{ color: colors.greenDeep, fontWeight: "700" }}>{formatMoney(hc.totalPesewas)}</Text>
              <Pressable style={styles.smallBtn} onPress={() => void recallCart(hc)}>
                <Text style={styles.smallBtnText}>Recall</Text>
              </Pressable>
            </View>
          ))}
        </View>
      )}
    </View>
  );

  const cartPane = (
    <View style={[styles.card, isTablet ? { flex: 1 } : { marginTop: spacing.md }]}>
      <View style={styles.rowBetween}>
        <Text style={styles.sectionTitle}>
          {order ? `Order ${order.orderNumber}` : "Current Order"}
        </Text>
        {order && (
          <View style={styles.kdsTag}>
            <Text style={styles.kdsTagText}>
              {order.status}
              {lastFiredRound > 0 ? ` · R${lastFiredRound}` : ""}
            </Text>
          </View>
        )}
      </View>

      {/* Order Type Pills */}
      <View style={styles.typeRow}>
        {ORDER_TYPES.map((ot) => (
          <Pressable
            key={ot.value}
            disabled={!!order}
            style={[styles.typeBtn, orderType === ot.value && styles.typeBtnActive]}
            onPress={() => setOrderType(ot.value)}
          >
            <Text style={[styles.typeBtnText, orderType === ot.value && styles.typeBtnTextActive]}>{ot.label}</Text>
          </Pressable>
        ))}
      </View>

      {orderType === "DINE_IN" && tables.length > 0 && (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginBottom: 10 }} contentContainerStyle={{ gap: 6 }}>
          {tables.map((t) => (
            <Pressable
              key={t.id}
              disabled={!!order || loadingExisting}
              style={[styles.tableChip, tableId === t.id && styles.tableChipActive]}
              onPress={() => void openTableOrder(t)}
            >
              <Text style={[styles.tableChipText, tableId === t.id && styles.tableChipTextActive]}>
                T{t.tableNumber} ({t.capacity}){t.currentOrderId ? " · OPEN ORDER" : ""}
              </Text>
            </Pressable>
          ))}
        </ScrollView>
      )}

      {/* Phase 5: Per-Item Seat Selector Bar (Active Seat for newly tapped items) */}
      {orderType === "DINE_IN" && canAddItems && (
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          style={{ marginBottom: 10 }}
          contentContainerStyle={{ gap: 6, alignItems: "center" }}
        >
          <Text style={styles.seatBarLabel}>Seat:</Text>
          <Pressable
            style={[styles.seatChip, activeSeat === null && styles.seatChipActive]}
            onPress={() => setActiveSeat(null)}
          >
            <Text style={[styles.seatChipText, activeSeat === null && styles.seatChipTextActive]}>None</Text>
          </Pressable>
          {Array.from({ length: seatCount }, (_, idx) => idx + 1).map((seat) => (
            <Pressable
              key={seat}
              style={[styles.seatChip, activeSeat === seat && styles.seatChipActive]}
              onPress={() => setActiveSeat(seat)}
            >
              <Text style={[styles.seatChipText, activeSeat === seat && styles.seatChipTextActive]}>S{seat}</Text>
            </Pressable>
          ))}
        </ScrollView>
      )}

      {!hasOrderItems ? (
        <View style={styles.emptyCart}>
          <Text style={styles.mutedText}>Tap menu items to add them to this order.</Text>
        </View>
      ) : (
        <View style={styles.cartList}>
          {serverItems.length > 0 && (
            <>
              <Text style={styles.groupLabel}>On Order</Text>
              {serverItems.map((it) => {
                const isFired = it.kdsRound != null || !!it.sentAt || firedItemIds.has(it.id);
                return (
                  <View key={it.id} style={[styles.cartRow, it.voided && { opacity: 0.45 }]}>
                    <View style={{ flex: 1 }}>
                      <View style={{ flexDirection: "row", alignItems: "center", gap: 6, flexWrap: "wrap" }}>
                        <Text style={styles.cartItemName}>
                          {it.quantity}× {it.itemName}
                        </Text>
                        {it.seatNumber != null && (
                          <View style={styles.seatBadge}>
                            <Text style={styles.seatBadgeText}>S{it.seatNumber}</Text>
                          </View>
                        )}
                        <View style={[styles.roundBadge, isFired ? styles.roundBadgeSent : styles.roundBadgePending]}>
                          <Text style={[styles.roundBadgeText, isFired ? styles.roundBadgeSentText : styles.roundBadgePendingText]}>
                            {it.voided ? "VOID" : isFired ? (it.kdsRound ? `R${it.kdsRound}` : "SENT") : "UNFIRED"}
                          </Text>
                        </View>
                      </View>
                      <Text style={styles.cartItemUnit}>{formatMoney(it.unitPricePesewas)} each</Text>
                    </View>
                    <Text style={styles.cartLineTotal}>{formatMoney(it.totalPesewas)}</Text>
                  </View>
                );
              })}
            </>
          )}

          {cart.length > 0 && (
            <>
              {order && <Text style={styles.groupLabel}>New Add-ons</Text>}
              {cart.map((c) => {
                const lineKey = cartLineKey(c.variantId, c.seatNumber);
                return (
                  <View key={lineKey} style={styles.cartRow}>
                    <View style={{ flex: 1 }}>
                      <View style={{ flexDirection: "row", alignItems: "center", gap: 6, flexWrap: "wrap" }}>
                        <Text style={styles.cartItemName}>{c.label}</Text>
                        {orderType === "DINE_IN" && (
                          <Pressable style={styles.seatBadge} onPress={() => cycleLineSeat(lineKey)}>
                            <Text style={styles.seatBadgeText}>
                              {c.seatNumber != null ? `S${c.seatNumber}` : "+Seat"}
                            </Text>
                          </Pressable>
                        )}
                      </View>
                      <Text style={styles.cartItemUnit}>{formatMoney(c.unitPricePesewas)} each</Text>
                    </View>
                    <View style={styles.stepper}>
                      <Pressable style={styles.stepBtn} disabled={!canAddItems} onPress={() => setQty(lineKey, c.qty - 1)}>
                        <Text style={styles.stepBtnText}>−</Text>
                      </Pressable>
                      <Text style={styles.stepQty}>{c.qty}</Text>
                      <Pressable style={styles.stepBtn} disabled={!canAddItems} onPress={() => setQty(lineKey, c.qty + 1)}>
                        <Text style={styles.stepBtnText}>+</Text>
                      </Pressable>
                    </View>
                    <Text style={styles.cartLineTotal}>{formatMoney(c.unitPricePesewas * c.qty)}</Text>
                  </View>
                );
              })}
            </>
          )}
        </View>
      )}

      <View style={styles.totalsBox}>
        {order ? (
          <>
            <View style={styles.rowBetween}>
              <Text style={styles.mutedText}>Order Total</Text>
              <Text style={styles.totalVal}>{formatMoney(order.totalPesewas)}</Text>
            </View>
            {order.paidPesewas > 0 && (
              <View style={styles.rowBetween}>
                <Text style={styles.mutedText}>Paid</Text>
                <Text style={styles.totalVal}>−{formatMoney(order.paidPesewas)}</Text>
              </View>
            )}
            {cart.length > 0 && (
              <View style={styles.rowBetween}>
                <Text style={styles.mutedText}>New Add-ons</Text>
                <Text style={styles.totalVal}>+{formatMoney(subtotal)}</Text>
              </View>
            )}
          </>
        ) : (
          <>
            <View style={styles.rowBetween}>
              <Text style={styles.mutedText}>Subtotal</Text>
              <Text style={styles.totalVal}>{formatMoney(subtotal)}</Text>
            </View>
            {tax > 0 && (
              <View style={styles.rowBetween}>
                <Text style={styles.mutedText}>Tax ({taxPercent}%)</Text>
                <Text style={styles.totalVal}>{formatMoney(tax)}</Text>
              </View>
            )}
          </>
        )}
        <View style={[styles.rowBetween, { marginTop: 6, paddingTop: 6, borderTopWidth: 1, borderTopColor: colors.line }]}>
          <Text style={styles.grandLabel}>{order ? "Balance Due" : "Total"}</Text>
          <Text style={styles.grandValue}>{formatMoney(total)}</Text>
        </View>
      </View>

      {hasOrderItems && (
        <View style={{ gap: 8 }}>
          <View style={styles.actionRow}>
            {canFireToKitchen && online && (
              <Pressable
                style={[styles.secondaryBtn, { flex: 1 }]}
                onPress={() =>
                  void ensureOrder()
                    .then(sendToKitchen)
                    .catch((e) => setError(e instanceof Error ? e.message : String(e)))
                }
              >
                <Text style={styles.secondaryBtnText}>
                  {order ? "Fire Add-ons to Kitchen" : "Send to Kitchen"}
                </Text>
              </Pressable>
            )}
            {!order ? (
              online && (
                <Pressable style={[styles.secondaryBtn, { flex: 1 }]} onPress={() => void holdCart()}>
                  <Text style={styles.secondaryBtnText}>Hold Cart</Text>
                </Pressable>
              )
            ) : (
              <Pressable style={[styles.voidBtn, { flex: 1 }]} onPress={openVoidModal}>
                <Text style={styles.voidBtnText}>Void / New Order</Text>
              </Pressable>
            )}
          </View>
          <Pressable style={styles.chargeBtn} onPress={handleChargePress}>
            <Text style={styles.chargeBtnText}>
              {online ? `Charge ${formatMoney(total)}` : `Charge Cash Offline · ${formatMoney(total)}`}
            </Text>
          </Pressable>
        </View>
      )}
    </View>
  );

  return (
    <View style={styles.root}>
      {/* Compact Hardware / Shift / Online-Offline Bar */}
      <View style={styles.terminalBar}>
        <Pressable style={styles.terminalBarLeft} onPress={() => setShowTerminalModal(true)}>
          <Text style={styles.terminalBarTitle} numberOfLines={1}>
            {selectedTerminal ? selectedTerminal.serialNumber : "No Terminal"} ·{" "}
            {cashier ? cashier.name ?? cashier.badgeNumber : "Cashier PIN"}
          </Text>
          <View style={[styles.shiftBadge, shift ? styles.shiftOpen : styles.shiftClosed]}>
            <Text style={[styles.shiftBadgeText, shift ? styles.shiftOpenText : styles.shiftClosedText]}>
              {shift ? "SHIFT OPEN" : "NO SHIFT"}
            </Text>
          </View>
        </Pressable>

        <View style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
          <Pressable
            style={[
              styles.connPill,
              !online ? styles.connPillOffline : queuedCount > 0 || syncing ? styles.connPillSyncing : styles.connPillOnline,
            ]}
            onPress={() => {
              if (!online || queuedCount > 0) {
                kickSyncRef.current?.();
              } else {
                void loadAll();
              }
            }}
          >
            <Text
              style={[
                styles.connPillText,
                !online
                  ? styles.connPillOfflineText
                  : queuedCount > 0 || syncing
                    ? styles.connPillSyncingText
                    : styles.connPillOnlineText,
              ]}
            >
              {!online
                ? queuedCount > 0
                  ? `OFFLINE · ${queuedCount} QUEUED`
                  : "OFFLINE"
                : queuedCount > 0 || syncing
                  ? `SYNCING (${queuedCount})`
                  : "ONLINE"}
            </Text>
          </Pressable>
          <Pressable onPress={() => setShowTerminalModal(true)}>
            <Text style={styles.manageLink}>Manage</Text>
          </Pressable>
        </View>
      </View>

      {error && (
        <View style={styles.errorBanner}>
          <Text style={styles.errorText}>{error}</Text>
        </View>
      )}
      {notice && (
        <View style={styles.okBanner}>
          <Text style={styles.okText}>{notice}</Text>
        </View>
      )}

      <ScrollView
        contentContainerStyle={[styles.scrollContent, isTablet && styles.tabletSplit]}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void loadAll()} tintColor={colors.green} />}
      >
        {catalogPane}
        {cartPane}
      </ScrollView>

      <TerminalShiftModal
        visible={showTerminalModal}
        onClose={() => setShowTerminalModal(false)}
        client={client}
        merchantId={merchant.id}
        terminals={terminals}
        selectedTerminal={selectedTerminal}
        cashier={cashier}
        shift={shift}
        onSelectTerminal={setSelectedTerminal}
        onSelectCashier={setCashier}
        onShiftChanged={setShift}
      />

      <TenderModal
        visible={showTenderModal}
        onClose={() => setShowTenderModal(false)}
        client={client}
        merchantId={merchant.id}
        totalPesewas={total}
        ensureOrder={ensureOrder}
        initialOrder={order}
        onOrderUpdated={setOrder}
        onOrderPaid={handleOrderPaid}
      />

      {/* Phase 3: Manager-Controlled Order Void Modal */}
      <Modal
        visible={showVoidModal}
        transparent
        animationType="fade"
        onRequestClose={() => !voidSubmitting && setShowVoidModal(false)}
      >
        <View style={styles.modalBackdrop}>
          <View style={styles.receiptModal}>
            <Text style={styles.sectionTitle}>Void Order {order?.orderNumber ?? ""}</Text>
            <Text style={[styles.receiptSub, { marginBottom: 10 }]}>
              Enter a reason and manager PIN to authorize voiding this order.
            </Text>

            <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 6, marginBottom: 10 }}>
              {["Customer cancelled", "Wrong order", "Duplicate order"].map((preset) => (
                <Pressable
                  key={preset}
                  style={styles.smallBtn}
                  onPress={() => setVoidReason(preset)}
                >
                  <Text style={styles.smallBtnText}>{preset}</Text>
                </Pressable>
              ))}
            </View>

            <TextInput
              style={[styles.searchInput, { marginBottom: 8 }]}
              value={voidReason}
              onChangeText={setVoidReason}
              placeholder="Void reason (required)"
              placeholderTextColor={colors.subtle}
            />
            <TextInput
              style={[styles.searchInput, { marginBottom: 8 }]}
              value={voidManagerBadge}
              onChangeText={setVoidManagerBadge}
              placeholder="Manager badge number (optional)"
              placeholderTextColor={colors.subtle}
              autoCapitalize="characters"
            />
            <TextInput
              style={[styles.searchInput, { marginBottom: 10 }]}
              value={voidManagerPin}
              onChangeText={setVoidManagerPin}
              placeholder="Manager PIN (required)"
              placeholderTextColor={colors.subtle}
              secureTextEntry
              keyboardType="number-pad"
            />

            {order?.status === "PARTIAL" && (
              <Pressable
                style={[styles.offlineNoticeBox, { marginBottom: 10, flexDirection: "row", alignItems: "center", justifyContent: "space-between" }]}
                onPress={() => setVoidRefundPayments((v) => !v)}
              >
                <Text style={[styles.offlineNoticeText, { flex: 1 }]}>
                  Refund existing payments ({formatMoney(order.paidPesewas)})
                </Text>
                <Text style={{ fontWeight: "700", color: voidRefundPayments ? colors.green : colors.subtle }}>
                  {voidRefundPayments ? "YES" : "NO"}
                </Text>
              </Pressable>
            )}

            {voidError && (
              <Text style={[styles.errorText, { marginBottom: 8 }]}>{voidError}</Text>
            )}

            <View style={styles.actionRow}>
              <Pressable
                style={[styles.secondaryBtn, { flex: 1 }]}
                disabled={voidSubmitting}
                onPress={() => setShowVoidModal(false)}
              >
                <Text style={styles.secondaryBtnText}>Cancel</Text>
              </Pressable>
              <Pressable
                style={[styles.voidBtn, { flex: 1.4 }]}
                disabled={voidSubmitting}
                onPress={() => void submitVoidOrder()}
              >
                <Text style={styles.voidBtnText}>{voidSubmitting ? "Voiding…" : "Confirm Void"}</Text>
              </Pressable>
            </View>
          </View>
        </View>
      </Modal>

      {/* Phase 8: Offline Cash-Only Checkout Modal (MoMo / Card / GhQR / Bank blocked offline) */}
      <Modal
        visible={showOfflineCashModal}
        transparent
        animationType="fade"
        onRequestClose={() => setShowOfflineCashModal(false)}
      >
        <View style={styles.modalBackdrop}>
          <View style={styles.receiptModal}>
            <Text style={styles.sectionTitle}>Offline Cash Checkout</Text>
            <View style={styles.offlineNoticeBox}>
              <Text style={styles.offlineNoticeText}>
                Offline Mode — Cash Only. MoMo, Card, GhQR, and Bank Transfer are disabled until connectivity returns.
              </Text>
            </View>

            <View style={styles.offlineTenderRow}>
              {ALL_TENDERS.map((t) => {
                const allowed = isTenderAllowedOffline(t.type);
                return (
                  <View
                    key={t.type}
                    style={[styles.offlineTenderChip, allowed ? styles.offlineTenderAllowed : styles.offlineTenderBlocked]}
                  >
                    <Text
                      style={[
                        styles.offlineTenderText,
                        allowed ? styles.offlineTenderAllowedText : styles.offlineTenderBlockedText,
                      ]}
                    >
                      {t.label}
                      {allowed ? " ✓" : " ✕"}
                    </Text>
                  </View>
                );
              })}
            </View>

            <Text style={styles.mutedText}>Amount Due: {formatMoney(total)}</Text>
            <TextInput
              style={[styles.searchInput, { marginTop: 8, marginBottom: 8 }]}
              value={offlineCashInput}
              onChangeText={(v) => {
                setOfflineCashInput(v);
                setOfflineCashError(null);
              }}
              keyboardType="decimal-pad"
              placeholder="Cash received (GHS)"
              placeholderTextColor={colors.subtle}
            />

            <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 6, marginBottom: 12 }}>
              <Pressable style={styles.smallBtn} onPress={() => setOfflineCashInput((total / 100).toFixed(2))}>
                <Text style={styles.smallBtnText}>Exact</Text>
              </Pressable>
              {[1000, 2000, 5000, 10000].map((addP) => (
                <Pressable
                  key={addP}
                  style={styles.smallBtn}
                  onPress={() => {
                    const cur = parseMoney(offlineCashInput) ?? total;
                    setOfflineCashInput(((cur + addP) / 100).toFixed(2));
                  }}
                >
                  <Text style={styles.smallBtnText}>+{addP / 100}</Text>
                </Pressable>
              ))}
            </View>

            {offlineCashError && (
              <Text style={[styles.errorText, { marginBottom: 8 }]}>{offlineCashError}</Text>
            )}

            <View style={styles.actionRow}>
              <Pressable
                style={[styles.secondaryBtn, { flex: 1 }]}
                onPress={() => setShowOfflineCashModal(false)}
              >
                <Text style={styles.secondaryBtnText}>Cancel</Text>
              </Pressable>
              <Pressable style={[styles.chargeBtn, { flex: 1.4 }]} onPress={confirmOfflineCashCheckout}>
                <Text style={styles.chargeBtnText}>Queue Cash Sale</Text>
              </Pressable>
            </View>
          </View>
        </View>
      </Modal>

      {/* Local Offline Receipt Confirmation */}
      <Modal
        visible={!!offlineReceipt}
        transparent
        animationType="fade"
        onRequestClose={() => setOfflineReceipt(null)}
      >
        <View style={styles.modalBackdrop}>
          <View style={styles.receiptModal}>
            {offlineReceipt && (
              <>
                <Text style={styles.receiptTitle}>Offline Cash Sale Queued</Text>
                <Text style={styles.receiptSub}>
                  Ref {offlineReceipt.offlineTxId.slice(0, 8).toUpperCase()} · {offlineReceipt.itemCount} item(s) ·{" "}
                  {formatMoney(offlineReceipt.totalPesewas)}
                </Text>
                <View style={styles.receiptPaper}>
                  <Text style={styles.receiptMono}>
                    {`OFFLINE RECEIPT (PENDING SYNC)\nTotal:    ${formatMoney(offlineReceipt.totalPesewas)}\nCash:     ${formatMoney(offlineReceipt.cashReceivedPesewas)}\nChange:   ${formatMoney(offlineReceipt.changePesewas)}\nQueued:   Will sync via /v1/pos/sync`}
                  </Text>
                </View>
                <Pressable style={styles.chargeBtn} onPress={() => setOfflineReceipt(null)}>
                  <Text style={styles.chargeBtnText}>New Sale</Text>
                </Pressable>
              </>
            )}
          </View>
        </View>
      </Modal>

      <Modal visible={!!receipt} transparent animationType="fade" onRequestClose={() => setReceipt(null)}>
        <View style={styles.modalBackdrop}>
          <View style={styles.receiptModal}>
            {receipt && (
              <>
                <Text style={styles.receiptTitle}>Sale Complete</Text>
                <Text style={styles.receiptSub}>
                  Order {receipt.order.orderNumber} · {formatMoney(receipt.order.totalPesewas)}
                  {receipt.order.changePesewas > 0 ? ` · Change ${formatMoney(receipt.order.changePesewas)}` : ""}
                </Text>
                <ScrollView style={styles.receiptPaper}>
                  <Text style={styles.receiptMono}>{receipt.receipt.escposFormattedText}</Text>
                </ScrollView>
                <Pressable style={styles.chargeBtn} onPress={() => setReceipt(null)}>
                  <Text style={styles.chargeBtnText}>New Sale</Text>
                </Pressable>
              </>
            )}
          </View>
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  terminalBar: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    backgroundColor: colors.surface,
    paddingHorizontal: spacing.md,
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: colors.line,
  },
  terminalBarLeft: { flexDirection: "row", alignItems: "center", gap: 8, flex: 1 },
  terminalBarTitle: { fontSize: 13, fontWeight: "700", color: colors.ink },
  shiftBadge: { paddingHorizontal: 8, paddingVertical: 2, borderRadius: radius.pill },
  shiftOpen: { backgroundColor: colors.okBg },
  shiftClosed: { backgroundColor: colors.warnBg },
  shiftBadgeText: { fontSize: 10, fontWeight: "800" },
  shiftOpenText: { color: colors.ok },
  shiftClosedText: { color: colors.warn },
  manageLink: { fontSize: 13, fontWeight: "700", color: colors.green },
  errorBanner: { backgroundColor: colors.dangerBg, padding: 10 },
  errorText: { color: colors.danger, fontSize: 13, fontWeight: "600" },
  okBanner: { backgroundColor: colors.okBg, padding: 10 },
  okText: { color: colors.ok, fontSize: 13, fontWeight: "700" },
  scrollContent: { padding: spacing.md, paddingBottom: 32 },
  tabletSplit: { flexDirection: "row", gap: spacing.md, alignItems: "flex-start" },
  searchInput: {
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.lineStrong,
    borderRadius: radius.sm,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 14,
    color: colors.ink,
    marginBottom: 10,
  },
  catScroll: { marginBottom: 10 },
  catPill: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surface,
  },
  catPillActive: { backgroundColor: colors.greenDeep, borderColor: colors.greenDeep },
  catPillText: { fontSize: 12, fontWeight: "700", color: colors.inkSecondary },
  catPillTextActive: { color: "#fff" },
  productGrid: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  productTile: {
    width: "48%",
    minWidth: 145,
    minHeight: 92,
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    borderWidth: 1.5,
    borderColor: colors.line,
    padding: 12,
    justifyContent: "space-between",
  },
  productTileActive: { borderColor: colors.green, backgroundColor: colors.greenSubtle },
  qtyBadge: {
    position: "absolute",
    top: 8,
    right: 8,
    minWidth: 22,
    height: 22,
    borderRadius: 11,
    backgroundColor: colors.green,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 5,
  },
  qtyBadgeText: { color: "#fff", fontSize: 11, fontWeight: "800" },
  productName: { fontSize: 14, fontWeight: "700", color: colors.ink, paddingRight: 22 },
  productPrice: { fontSize: 15, fontWeight: "800", color: colors.greenDeep, marginTop: 6 },
  productStock: { fontSize: 11, color: colors.muted, marginTop: 2 },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.line,
    padding: spacing.md,
  },
  emptyCard: { backgroundColor: colors.surface, borderRadius: radius.md, padding: spacing.lg, alignItems: "center" },
  sectionTitle: { fontSize: 16, fontWeight: "800", color: colors.ink, marginBottom: 8 },
  rowBetween: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  kdsTag: { backgroundColor: colors.okBg, paddingHorizontal: 8, paddingVertical: 3, borderRadius: radius.pill },
  kdsTagText: { color: colors.ok, fontSize: 10, fontWeight: "800" },
  typeRow: { flexDirection: "row", gap: 6, marginBottom: 10 },
  typeBtn: {
    flex: 1,
    paddingVertical: 8,
    borderRadius: radius.xs,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceAlt,
    alignItems: "center",
  },
  typeBtnActive: { backgroundColor: colors.greenSoft, borderColor: colors.green },
  typeBtnText: { fontSize: 11, fontWeight: "700", color: colors.inkSecondary },
  typeBtnTextActive: { color: colors.greenDeep },
  tableChip: {
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: radius.xs,
    borderWidth: 1,
    borderColor: colors.lineStrong,
    backgroundColor: colors.surfaceAlt,
  },
  tableChipActive: { backgroundColor: colors.green, borderColor: colors.green },
  tableChipText: { fontSize: 12, fontWeight: "700", color: colors.ink },
  tableChipTextActive: { color: "#fff" },
  emptyCart: {
    padding: spacing.lg,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderStyle: "dashed",
    borderColor: colors.lineStrong,
    alignItems: "center",
    marginBottom: 10,
  },
  cartList: { gap: 8, marginBottom: 10 },
  cartRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    paddingBottom: 8,
    borderBottomWidth: 1,
    borderBottomColor: "#EEF2F0",
  },
  cartItemName: { fontSize: 13, fontWeight: "700", color: colors.ink },
  cartItemUnit: { fontSize: 11, color: colors.muted },
  stepper: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.surfaceAlt,
    borderWidth: 1,
    borderColor: colors.line,
    borderRadius: radius.xs,
  },
  stepBtn: { width: 28, height: 28, alignItems: "center", justifyContent: "center" },
  stepBtnText: { fontSize: 16, fontWeight: "800", color: colors.ink },
  stepQty: { minWidth: 22, textAlign: "center", fontSize: 13, fontWeight: "800", color: colors.ink },
  cartLineTotal: { minWidth: 72, textAlign: "right", fontSize: 13, fontWeight: "800", color: colors.ink },
  totalsBox: {
    backgroundColor: colors.surfaceAlt,
    borderRadius: radius.sm,
    padding: 12,
    borderWidth: 1,
    borderColor: colors.line,
    marginBottom: 12,
    gap: 4,
  },
  totalVal: { fontSize: 13, fontWeight: "700", color: colors.ink },
  grandLabel: { fontSize: 15, fontWeight: "800", color: colors.ink },
  grandValue: { fontSize: 18, fontWeight: "800", color: colors.greenDeep },
  actionRow: { flexDirection: "row", gap: 8 },
  secondaryBtn: {
    borderWidth: 1,
    borderColor: colors.lineStrong,
    backgroundColor: colors.surface,
    paddingVertical: 11,
    borderRadius: radius.sm,
    alignItems: "center",
  },
  secondaryBtnText: { fontSize: 13, fontWeight: "700", color: colors.ink },
  voidBtn: {
    borderWidth: 1,
    borderColor: colors.dangerBorder,
    backgroundColor: colors.dangerBg,
    paddingVertical: 11,
    borderRadius: radius.sm,
    alignItems: "center",
  },
  voidBtnText: { fontSize: 13, fontWeight: "700", color: colors.danger },
  chargeBtn: {
    backgroundColor: colors.green,
    paddingVertical: 14,
    borderRadius: radius.sm,
    alignItems: "center",
  },
  chargeBtnText: { color: "#fff", fontSize: 16, fontWeight: "800" },
  heldRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingVertical: 8,
    borderTopWidth: 1,
    borderTopColor: "#EEF2F0",
  },
  smallBtn: {
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: radius.xs,
    borderWidth: 1,
    borderColor: colors.green,
  },
  smallBtnText: { color: colors.greenDeep, fontSize: 12, fontWeight: "700" },
  mutedText: { fontSize: 13, color: colors.muted },
  modalBackdrop: { flex: 1, backgroundColor: "rgba(15,23,20,0.6)", justifyContent: "center", padding: spacing.lg },
  receiptModal: { backgroundColor: colors.surface, borderRadius: radius.lg, padding: spacing.lg, maxHeight: "85%" },
  receiptTitle: { fontSize: 20, fontWeight: "800", color: colors.ok },
  receiptSub: { fontSize: 13, color: colors.inkSecondary, marginTop: 4, marginBottom: spacing.md },
  receiptPaper: {
    backgroundColor: "#FCFDFB",
    borderWidth: 1,
    borderStyle: "dashed",
    borderColor: colors.lineStrong,
    borderRadius: radius.sm,
    padding: 12,
    maxHeight: 280,
    marginBottom: spacing.md,
  },
  receiptMono: { fontSize: 12, color: colors.ink, lineHeight: 18 },
  connPill: { paddingHorizontal: 8, paddingVertical: 3, borderRadius: radius.pill, borderWidth: 1 },
  connPillOnline: { backgroundColor: colors.okBg, borderColor: colors.ok },
  connPillSyncing: { backgroundColor: colors.warnBg, borderColor: colors.warn },
  connPillOffline: { backgroundColor: colors.dangerBg, borderColor: colors.dangerBorder },
  connPillText: { fontSize: 10, fontWeight: "800" },
  connPillOnlineText: { color: colors.ok },
  connPillSyncingText: { color: colors.warn },
  connPillOfflineText: { color: colors.danger },
  seatBarLabel: { fontSize: 12, fontWeight: "700", color: colors.muted },
  seatChip: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.lineStrong,
    backgroundColor: colors.surfaceAlt,
  },
  seatChipActive: { backgroundColor: colors.greenDeep, borderColor: colors.greenDeep },
  seatChipText: { fontSize: 11, fontWeight: "700", color: colors.ink },
  seatChipTextActive: { color: "#fff" },
  seatBadge: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: radius.xs,
    backgroundColor: colors.greenSoft,
    borderWidth: 1,
    borderColor: colors.green,
  },
  seatBadgeText: { fontSize: 10, fontWeight: "800", color: colors.greenDeep },
  groupLabel: {
    fontSize: 11,
    fontWeight: "800",
    color: colors.muted,
    textTransform: "uppercase",
    letterSpacing: 0.5,
    marginTop: 4,
  },
  roundBadge: { paddingHorizontal: 6, paddingVertical: 2, borderRadius: radius.xs },
  roundBadgeSent: { backgroundColor: colors.okBg },
  roundBadgePending: { backgroundColor: colors.warnBg },
  roundBadgeText: { fontSize: 10, fontWeight: "800" },
  roundBadgeSentText: { color: colors.ok },
  roundBadgePendingText: { color: colors.warn },
  offlineNoticeBox: {
    backgroundColor: colors.warnBg,
    borderRadius: radius.sm,
    padding: 10,
    marginBottom: 10,
  },
  offlineNoticeText: { fontSize: 12, fontWeight: "700", color: colors.warn },
  offlineTenderRow: { flexDirection: "row", flexWrap: "wrap", gap: 6, marginBottom: 12 },
  offlineTenderChip: { paddingHorizontal: 10, paddingVertical: 5, borderRadius: radius.pill, borderWidth: 1 },
  offlineTenderAllowed: { backgroundColor: colors.okBg, borderColor: colors.ok },
  offlineTenderBlocked: { backgroundColor: colors.surfaceAlt, borderColor: colors.line, opacity: 0.55 },
  offlineTenderText: { fontSize: 11, fontWeight: "800" },
  offlineTenderAllowedText: { color: colors.ok },
  offlineTenderBlockedText: { color: colors.muted },
});
