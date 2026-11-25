import React, { useCallback, useEffect, useMemo, useState } from "react";
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
import {
  buildPosOrderPayload,
  catalogItemLabel,
  CatalogRow,
  colors,
  computeCartTotals,
  DiningTable,
  ElepayClient,
  formatMoney,
  HeldCart,
  isFeatureNotEnabled,
  MerchantView,
  newKey,
  ORDER_TYPES,
  OrderType,
  parseHeldCartPayload,
  parseMoney,
  PosCartLine,
  PosOrder,
  PosOrderItem,
  PosReceipt,
  PosShift,
  PosTerminal,
  radius,
  spacing,
} from "@elepay/mobile-core";
import { TerminalShiftModal } from "../components/TerminalShiftModal";
import { TenderModal } from "../components/TenderModal";

interface Props {
  client: ElepayClient;
  merchant: MerchantView;
  preselectedTableId: string | null;
  onClearPreselectedTable: () => void;
}

export function PosRegisterScreen({ client, merchant, preselectedTableId, onClearPreselectedTable }: Props) {
  const { width } = useWindowDimensions();
  const isTablet = width >= 820;

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
  const [orderType, setOrderType] = useState<OrderType>("DINE_IN");
  const [tableId, setTableId] = useState<string>("");
  const [discount, setDiscount] = useState("");
  const [taxPercent, setTaxPercent] = useState("0");

  const [order, setOrder] = useState<PosOrder | null>(null);
  const [orderKey, setOrderKey] = useState<string>(() => newKey());
  const [kdsSent, setKdsSent] = useState(false);
  const [receipt, setReceipt] = useState<{ receipt: PosReceipt; order: PosOrder } | null>(null);

  const [showTerminalModal, setShowTerminalModal] = useState(false);
  const [showTenderModal, setShowTenderModal] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [moduleDisabled, setModuleDisabled] = useState(false);
  const [loadingExisting, setLoadingExisting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (preselectedTableId) {
      setOrderType("DINE_IN");
      // Existing table orders are selected in the register to load their server balance.
      setTableId(preselectedTableId);
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
      setCatalog(cat);
      setTables(tbls);
      setTerminals(terms);
      setSelectedTerminal((prev) => (prev && terms.some((t) => t.id === prev.id) ? prev : (terms[0] ?? null)));
      setError(null);
    } catch (e) {
      if (isFeatureNotEnabled(e)) setModuleDisabled(true);
      else setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRefreshing(false);
    }
  }, [client, merchant.id]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  useEffect(() => {
    if (!selectedTerminal) {
      setShift(null);
      setHeldCarts([]);
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
  }, [client, selectedTerminal]);

  const categories = useMemo(
    () => Array.from(new Set(catalog.map((r) => r.categoryName).filter((c): c is string => Boolean(c)))).sort(),
    [catalog],
  );
  const cartQtyMap = useMemo(() => new Map(cart.map((c) => [c.variantId, c.qty])), [cart]);
  const shown = catalog.filter((r) => {
    const matchCat = selectedCategory === "ALL" || r.categoryName === selectedCategory;
    const matchSearch = `${catalogItemLabel(r)} ${r.sku} ${r.barcode ?? ""}`.toLowerCase().includes(search.toLowerCase());
    return matchCat && matchSearch;
  });

  const discountP = discount.trim() ? parseMoney(discount) : 0;
  const taxRate = Number(taxPercent);
  const calculated = computeCartTotals(cart, discountP, taxRate);
  const { subtotal, effectiveDiscount, tax } = calculated;
  const total = order ? Math.max(0, order.totalPesewas - order.paidPesewas) : calculated.total;

  const addItem = (row: CatalogRow) => {
    if (order) return;
    setCart((prev) =>
      prev.some((x) => x.variantId === row.variantId)
        ? prev.map((x) => (x.variantId === row.variantId ? { ...x, qty: x.qty + 1 } : x))
        : [
            ...prev,
            {
              variantId: row.variantId,
              label: catalogItemLabel(row),
              unitPricePesewas: row.retailPricePesewas,
              qty: 1,
              seatNumber: null,
            },
          ],
    );
  };

  const setQty = (variantId: string, qty: number) => {
    if (order) return;
    setCart((prev) => (qty <= 0 ? prev.filter((x) => x.variantId !== variantId) : prev.map((x) => (x.variantId === variantId ? { ...x, qty } : x))));
  };

  async function openTableOrder(t: DiningTable) {
    if (order || cart.length || loadingExisting) { setError("Finish or clear your current cart first."); return; }
    if (!t.currentOrderId) { setTableId(t.id); return; }
    setLoadingExisting(true);
    try {
      if (!selectedTerminal || !cashier || !shift || shift.cashierId !== cashier.id) throw new Error("Authenticate a cashier and open a shift first.");
      const existing = await client.call<PosOrder>(`/v1/restaurant/waiter/${merchant.id}/orders/${t.currentOrderId}/cashier`, {body:{terminalId:selectedTerminal.id,cashierId:cashier.id}});
      if (existing.status !== "PENDING" || existing.paidPesewas !== 0) throw new Error("Order is not an unpaid pending order.");
      const items = await client.call<PosOrderItem[]>(`/v1/pos/orders/${existing.id}/items`);
      setOrder(existing); setTableId(t.id); setOrderType("DINE_IN"); setKdsSent(true);
      setCart(items.map(it => ({variantId: it.variantId, label: it.itemName, qty: it.quantity, unitPricePesewas: it.unitPricePesewas, seatNumber: it.seatNumber})));
      setNotice("Existing table order loaded; collect payment without recreating it."); setError(null);
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setLoadingExisting(false); }
  }

  async function ensureOrder(): Promise<PosOrder> {
    if (loadingExisting) throw new Error("Wait for table order to load.");
    if (order) return order;
    if (tableId && orderType === "DINE_IN") {
      const current = await client.call<DiningTable[]>(`/v1/restaurant/tables/merchant/${merchant.id}`);
      if (current.find(t=>t.id===tableId)?.currentOrderId) throw new Error("Table has an existing order. Open it instead of creating another.");
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
    if (orderType === "DINE_IN" && tableId) {
      await client
        .call(`/v1/restaurant/tables/${tableId}/seat?orderId=${encodeURIComponent(created.id)}`, { method: "POST" })
        .catch((e) => setError(`Order created, but could not seat table: ${e instanceof Error ? e.message : String(e)}`));
    }
    return created;
  }

  async function sendToKitchen(targetOrder: PosOrder) {
    if (kdsSent || orderType === "RETAIL") return;
    const orderItems = await client.call<PosOrderItem[]>(`/v1/pos/orders/${targetOrder.id}/items`);
    await client.call("/v1/restaurant/kds/tickets", {
      body: {
        merchantId: merchant.id,
        orderId: targetOrder.id,
        tableId: orderType === "DINE_IN" && tableId ? tableId : null,
        diningMode: orderType === "TAKEAWAY" ? "TAKEOUT" : orderType,
        serverName: cashier?.name ?? cashier?.badgeNumber ?? null,
        course: "MAIN",
        items: orderItems.map((it) => ({
          orderItemId: it.id,
          itemName: it.itemName,
          quantity: it.quantity,
          modifiers: it.modifiersJson && it.modifiersJson !== "[]" ? it.modifiersJson : null,
          course: it.course ?? "MAIN",
        })),
        notes: null,
        clientRef: `pos:${targetOrder.id}`,
      },
    });
    setKdsSent(true);
    setError(null);
    setNotice("Order sent to Kitchen Display (KDS).");
  }

  async function voidOrder() {
    if (!order) return;
    setError(null);
    try {
      await client.call<PosOrder>(`/v1/pos/orders/${order.id}/void`, { method: "POST" });
      setCart([]);
      setDiscount("");
      setOrder(null);
      setOrderKey(newKey());
      setKdsSent(false);
      setTableId("");
      setNotice("Order voided. Ready for a new order.");
      void loadAll();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
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
      const selectedTable = tables.find((t) => t.id === tableId);
      await client.call("/v1/pos/carts/hold", {
        body: {
          terminalId: selectedTerminal.id,
          cashierId: cashier.id,
          merchantId: merchant.id,
          customerName: selectedTable ? `Table ${selectedTable.tableNumber}` : "Walk-in",
          cartPayloadJson: JSON.stringify({ items: cart, discount, taxPercent, orderType, tableId: tableId || null }),
          totalPesewas: total,
        },
      });
      setCart([]);
      setDiscount("");
      setOrder(null);
      setOrderKey(newKey());
      setKdsSent(false);
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
        setCart(parsed.items);
        setDiscount(parsed.discount);
        setTaxPercent(parsed.taxPercent);
        setOrderType(parsed.orderType);
        setTableId(parsed.tableId ?? "");
        setOrder(null);
        setOrderKey(newKey());
        setKdsSent(false);
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
      setCart([]);
      setDiscount("");
      setOrder(null);
      setOrderKey(newKey());
      setKdsSent(false);
      setTableId("");
      void loadAll();
    } catch (e) {
      setShowTenderModal(false);
      setError(`Payment recorded, but receipt failed: ${e instanceof Error ? e.message : String(e)}`);
      if (paidOrder.status === "PAID") {
        setCart([]);
        setDiscount("");
        setOrder(null);
        setOrderKey(newKey());
        setKdsSent(false);
        setTableId("");
        void loadAll();
      }
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
                disabled={!!order}
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
        <Text style={styles.sectionTitle}>Current Order</Text>
        {kdsSent && (
          <View style={styles.kdsTag}>
            <Text style={styles.kdsTagText}>SENT TO KDS</Text>
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

      {cart.length === 0 ? (
        <View style={styles.emptyCart}>
          <Text style={styles.mutedText}>Tap menu items to add them to this order.</Text>
        </View>
      ) : (
        <View style={styles.cartList}>
          {cart.map((c) => (
            <View key={c.variantId} style={styles.cartRow}>
              <View style={{ flex: 1 }}>
                <Text style={styles.cartItemName}>{c.label}</Text>
                <Text style={styles.cartItemUnit}>{formatMoney(c.unitPricePesewas)} each</Text>
              </View>
              <View style={styles.stepper}>
                <Pressable style={styles.stepBtn} disabled={!!order} onPress={() => setQty(c.variantId, c.qty - 1)}>
                  <Text style={styles.stepBtnText}>−</Text>
                </Pressable>
                <Text style={styles.stepQty}>{c.qty}</Text>
                <Pressable style={styles.stepBtn} disabled={!!order} onPress={() => setQty(c.variantId, c.qty + 1)}>
                  <Text style={styles.stepBtnText}>+</Text>
                </Pressable>
              </View>
              <Text style={styles.cartLineTotal}>{formatMoney(c.unitPricePesewas * c.qty)}</Text>
            </View>
          ))}
        </View>
      )}

      <View style={styles.totalsBox}>
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
        <View style={[styles.rowBetween, { marginTop: 6, paddingTop: 6, borderTopWidth: 1, borderTopColor: colors.line }]}>
          <Text style={styles.grandLabel}>Total</Text>
          <Text style={styles.grandValue}>{formatMoney(total)}</Text>
        </View>
      </View>

      {cart.length > 0 && (
        <View style={{ gap: 8 }}>
          <View style={styles.actionRow}>
            {orderType !== "RETAIL" && !kdsSent && (
              <Pressable
                style={[styles.secondaryBtn, { flex: 1 }]}
                onPress={() =>
                  void ensureOrder()
                    .then(sendToKitchen)
                    .catch((e) => setError(e instanceof Error ? e.message : String(e)))
                }
              >
                <Text style={styles.secondaryBtnText}>Send to Kitchen</Text>
              </Pressable>
            )}
            {!order ? (
              <Pressable style={[styles.secondaryBtn, { flex: 1 }]} onPress={() => void holdCart()}>
                <Text style={styles.secondaryBtnText}>Hold Cart</Text>
              </Pressable>
            ) : (
              <Pressable style={[styles.voidBtn, { flex: 1 }]} onPress={() => void voidOrder()}>
                <Text style={styles.voidBtnText}>Void / New Order</Text>
              </Pressable>
            )}
          </View>
          <Pressable style={styles.chargeBtn} onPress={() => setShowTenderModal(true)}>
            <Text style={styles.chargeBtnText}>Charge {formatMoney(total)}</Text>
          </Pressable>
        </View>
      )}
    </View>
  );

  return (
    <View style={styles.root}>
      {/* Compact Hardware / Shift Bar */}
      <Pressable style={styles.terminalBar} onPress={() => setShowTerminalModal(true)}>
        <View style={styles.terminalBarLeft}>
          <Text style={styles.terminalBarTitle}>
            {selectedTerminal ? selectedTerminal.serialNumber : "No Terminal"} ·{" "}
            {cashier ? cashier.name ?? cashier.badgeNumber : "Cashier PIN"}
          </Text>
          <View style={[styles.shiftBadge, shift ? styles.shiftOpen : styles.shiftClosed]}>
            <Text style={[styles.shiftBadgeText, shift ? styles.shiftOpenText : styles.shiftClosedText]}>
              {shift ? "SHIFT OPEN" : "NO SHIFT"}
            </Text>
          </View>
        </View>
        <Text style={styles.manageLink}>Manage</Text>
      </Pressable>

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
        onOrderPaid={handleOrderPaid}
      />

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
});
