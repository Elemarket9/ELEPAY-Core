package com.elepay.merchanttools.pos;

import com.elepay.checkout.PosCheckoutGateway;
import com.elepay.common.ApiException;
import com.elepay.common.OpsQueue;
import com.elepay.common.References;
import com.elepay.merchant.MerchantAccess;
import com.elepay.merchanttools.inventory.MovementType;
import com.elepay.merchanttools.inventory.StockService;
import com.elepay.merchanttools.restaurant.DiningMode;
import com.elepay.merchanttools.restaurant.KdsService;
import com.elepay.merchanttools.restaurant.RecipeCostingService;
import com.elepay.merchanttools.restaurant.RestaurantTableService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class PosOrderService {
    private static final Set<String> MOMO_NETWORKS = Set.of("mtn", "telecel", "airteltigo");
    private static final Set<String> PAYABLE_STATUSES = Set.of("PENDING", "PARTIAL");

    private final JdbcClient db;
    private final StockService stockService;
    private final PosCashierService cashierService;
    private final PosCheckoutGateway checkout;
    private final OpsQueue opsQueue;
    private final RecipeCostingService recipeCostingService;
    private final KdsService kdsService;
    private final ObjectProvider<RestaurantTableService> tableServiceProvider;
    private final ObjectProvider<PosReceiptService> receiptServiceProvider;
    private final MerchantAccess merchants;
    private final ObjectMapper json;
    /** Savepoint-scoped template so a failed stock deduction never rolls back the payment. */
    private final TransactionTemplate savepointTx;

    public PosOrderService(JdbcClient db,
                           StockService stockService,
                           PosCashierService cashierService,
                           PosCheckoutGateway checkout,
                           OpsQueue opsQueue,
                           RecipeCostingService recipeCostingService,
                           KdsService kdsService,
                           ObjectProvider<RestaurantTableService> tableServiceProvider,
                           ObjectProvider<PosReceiptService> receiptServiceProvider,
                           MerchantAccess merchants,
                           ObjectMapper json,
                           PlatformTransactionManager tm) {
        this.db = db;
        this.stockService = stockService;
        this.cashierService = cashierService;
        this.checkout = checkout;
        this.opsQueue = opsQueue;
        this.recipeCostingService = recipeCostingService;
        this.kdsService = kdsService;
        this.tableServiceProvider = tableServiceProvider;
        this.receiptServiceProvider = receiptServiceProvider;
        this.merchants = merchants;
        this.json = json;
        this.savepointTx = new TransactionTemplate(tm);
        this.savepointTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    private static void requirePayable(PosOrder order) {
        if ("PAID".equals(order.status())) {
            throw ApiException.conflict("order_paid", "Order is already fully paid");
        }
        if (!PAYABLE_STATUSES.contains(order.status())) {
            throw ApiException.conflict("order_not_payable", "Order is " + order.status() + " and cannot take payments");
        }
    }

    private boolean hasOpenDigitalCheckoutForSplit(UUID orderId, String splitKey) {
        String normalized = splitKey == null ? "" : splitKey;
        return db.sql("""
                SELECT count(*) FROM pos_checkout_link
                WHERE order_id = :id AND COALESCE(split_key, '') = :splitKey AND status = 'open'
                """)
                .param("id", orderId)
                .param("splitKey", normalized)
                .query(Long.class).single() > 0;
    }

    private long openDigitalCheckoutAmount(UUID orderId) {
        return db.sql("""
                SELECT COALESCE(SUM(amount), 0) FROM pos_checkout_link
                WHERE order_id = :id AND status = 'open'
                """)
                .param("id", orderId)
                .query(Long.class).single();
    }

    public record PosOrder(
            UUID id,
            String orderNumber,
            UUID merchantId,
            UUID branchId,
            UUID terminalId,
            UUID cashierId,
            UUID customerId,
            long subtotalPesewas,
            long discountPesewas,
            long taxPesewas,
            long tipPesewas,
            long totalPesewas,
            long paidPesewas,
            long changePesewas,
            String status,
            String orderType,
            OffsetDateTime createdAt
    ) {}

    public record PosOrderItem(
            UUID id,
            UUID orderId,
            UUID variantId,
            String itemName,
            BigDecimal quantity,
            long unitPricePesewas,
            long discountPesewas,
            long taxPesewas,
            long totalPesewas,
            String modifiersJson,
            Integer seatNumber,
            String course,
            String notes,
            Integer kdsRound,
            OffsetDateTime sentAt,
            boolean voided
    ) {
        public PosOrderItem(
                UUID id,
                UUID orderId,
                UUID variantId,
                String itemName,
                BigDecimal quantity,
                long unitPricePesewas,
                long discountPesewas,
                long taxPesewas,
                long totalPesewas,
                String modifiersJson,
                Integer seatNumber,
                String course,
                String notes
        ) {
            this(id, orderId, variantId, itemName, quantity, unitPricePesewas, discountPesewas,
                    taxPesewas, totalPesewas, modifiersJson, seatNumber, course, notes, null, null, false);
        }
    }

    public record OrderItemInput(
            UUID variantId,
            String itemName,
            BigDecimal quantity,
            long unitPricePesewas,
            long discountPesewas,
            long taxPesewas,
            String modifiersJson,
            Integer seatNumber,
            String course,
            String notes
    ) {}

    public record TenderPayment(
            UUID id,
            UUID orderId,
            String tenderType,
            long amountPesewas,
            String reference,
            String status,
            OffsetDateTime createdAt,
            String splitKey
    ) {
        public TenderPayment(
                UUID id,
                UUID orderId,
                String tenderType,
                long amountPesewas,
                String reference,
                String status,
                OffsetDateTime createdAt
        ) {
            this(id, orderId, tenderType, amountPesewas, reference, status, createdAt, null);
        }
    }

    public record TenderRequest(
            TenderType tenderType,
            Long amountPesewas,
            String splitKey,
            String phone,
            String network,
            String email,
            String reference
    ) {}

    public record TenderResult(
            UUID orderId,
            String orderStatus,
            TenderType tenderType,
            long amountPesewas,
            UUID linkId,
            String checkoutRef,
            UUID attemptId,
            String paymentStatus,
            String payUrl,
            String qrPayload,
            String qrReference,
            String prompt,
            PosOrder order
    ) {}

    private long resolveAuthoritativeUnitPrice(UUID merchantId, OrderItemInput item) {
        if (item == null || item.quantity() == null || item.quantity().signum() <= 0
                || item.unitPricePesewas() < 0 || item.discountPesewas() < 0 || item.taxPesewas() < 0) {
            throw new IllegalArgumentException("Invalid POS item amounts");
        }
        long basePrice;
        if (item.variantId() == null) {
            basePrice = item.unitPricePesewas();
        } else {
            basePrice = db.sql("SELECT retail_price_pesewas FROM inventory_variant WHERE id = :id AND merchant_id = :m")
                    .param("id", item.variantId())
                    .param("m", merchantId)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> ApiException.notFound("Variant not found"));
        }
        long modifierPrice = resolveModifierAdditionalPrice(merchantId, item.modifiersJson());
        return Math.addExact(basePrice, modifierPrice);
    }

    private long resolveModifierAdditionalPrice(UUID merchantId, String modifiersJson) {
        long total = 0L;
        java.util.Set<UUID> seenOptionIds = new java.util.HashSet<>();
        java.util.Map<UUID, Integer> groupCounts = new java.util.HashMap<>();

        if (modifiersJson != null && !modifiersJson.isBlank() && !"[]".equals(modifiersJson.trim())) {
            JsonNode root;
            try {
                root = json.readTree(modifiersJson);
            } catch (Exception e) {
                throw ApiException.badRequest("invalid_modifiers", "Invalid modifiers JSON");
            }
            if (!root.isArray()) {
                throw ApiException.badRequest("invalid_modifiers", "Modifiers must be a JSON array");
            }
            for (JsonNode node : root) {
                String rawId = null;
                if (node.isString()) {
                    rawId = node.asString();
                } else if (node.isObject()) {
                    if (node.hasNonNull("optionId")) rawId = node.get("optionId").asString();
                    else if (node.hasNonNull("id")) rawId = node.get("id").asString();
                }
                if (rawId != null && !rawId.isBlank()) {
                    UUID optionId;
                    try {
                        optionId = UUID.fromString(rawId);
                    } catch (IllegalArgumentException e) {
                        throw ApiException.badRequest("invalid_modifiers", "Invalid modifier option ID");
                    }
                    if (!seenOptionIds.add(optionId)) {
                        throw ApiException.badRequest("duplicate_modifier", "Duplicate modifier option selected");
                    }
                    record ModRow(UUID groupId, long price, int maxSelection) {}
                    ModRow mod = db.sql("""
                            SELECT o.group_id, o.additional_price_pesewas, g.max_selection
                            FROM restaurant_modifier_option o
                            JOIN restaurant_modifier_group g ON g.id = o.group_id
                            WHERE o.id = :id AND g.merchant_id = :m
                            """)
                            .param("id", optionId)
                            .param("m", merchantId)
                            .query((rs, i) -> new ModRow(
                                    rs.getObject(1, UUID.class),
                                    rs.getLong(2),
                                    rs.getInt(3)))
                            .optional()
                            .orElseThrow(() -> ApiException.notFound("Modifier option not found"));
                    if (mod.price() < 0) {
                        throw ApiException.badRequest("invalid_modifier_price", "Modifier price cannot be negative");
                    }
                    int count = groupCounts.merge(mod.groupId(), 1, Integer::sum);
                    if (mod.maxSelection() > 0 && count > mod.maxSelection()) {
                        throw ApiException.badRequest("modifier_max_selection_exceeded",
                                "Selected modifiers exceed group maximum of " + mod.maxSelection());
                    }
                    total = Math.addExact(total, mod.price());
                }
            }
        }

        record RequiredGroup(UUID id, String name, int minSelection, boolean isRequired) {}
        List<RequiredGroup> requiredGroups = db.sql("""
                SELECT id, name, min_selection, is_required
                FROM restaurant_modifier_group
                WHERE merchant_id = :m AND (is_required = true OR min_selection > 0)
                """)
                .param("m", merchantId)
                .query((rs, i) -> new RequiredGroup(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getInt(3),
                        rs.getBoolean(4)))
                .list();
        for (RequiredGroup group : requiredGroups) {
            int requiredMin = Math.max(group.minSelection(), group.isRequired() ? 1 : 0);
            int selected = groupCounts.getOrDefault(group.id(), 0);
            if (selected < requiredMin) {
                throw ApiException.badRequest("required_modifier_missing",
                        "Modifier group '" + group.name() + "' requires at least " + requiredMin + " selection(s)");
            }
        }
        return total;
    }

    private static long itemTotal(OrderItemInput item, long effectiveUnitPrice) {
        long gross = item.quantity().multiply(BigDecimal.valueOf(effectiveUnitPrice))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (item.discountPesewas() > gross) throw new IllegalArgumentException("Item discount exceeds gross");
        return Math.addExact(Math.subtractExact(gross, item.discountPesewas()), item.taxPesewas());
    }

    public static String generateOrderNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        return "ORD-" + date + "-" + suffix;
    }

    @Transactional
    public PosOrder createOrder(
            UUID merchantId,
            UUID branchId,
            UUID terminalId,
            UUID cashierId,
            UUID customerId,
            List<OrderItemInput> items,
            long discountPesewas,
            long taxPesewas,
            long tipPesewas,
            String orderType
    ) {
        UUID orderId = UUID.randomUUID();
        String orderNumber = generateOrderNumber();

        if (items == null || items.isEmpty() || discountPesewas < 0 || taxPesewas < 0 || tipPesewas < 0)
            throw new IllegalArgumentException("Invalid order amounts or empty items");

        UUID effectiveCashierId = cashierId;
        if (terminalId != null) {
            var activeShift = cashierService.getActiveShift(terminalId);
            if (activeShift.isPresent()) {
                if (effectiveCashierId != null && !effectiveCashierId.equals(activeShift.get().cashierId())) {
                    throw ApiException.forbidden("Cashier does not match active terminal shift");
                }
                if (effectiveCashierId == null) {
                    effectiveCashierId = activeShift.get().cashierId();
                }
            }
        }
        if (effectiveCashierId != null) {
            var cashier = cashierService.getCashier(effectiveCashierId)
                    .filter(c -> c.merchantId().equals(merchantId) && c.isActive())
                    .orElseThrow(() -> ApiException.notFound("Active cashier not found"));
            effectiveCashierId = cashier.id();
        }

        List<Long> unitPrices = new ArrayList<>(items.size());
        List<Long> lineTotals = new ArrayList<>(items.size());
        long subtotal = 0L;
        for (OrderItemInput item : items) {
            long unitPrice = resolveAuthoritativeUnitPrice(merchantId, item);
            long lineTotal = itemTotal(item, unitPrice);
            unitPrices.add(unitPrice);
            lineTotals.add(lineTotal);
            subtotal = Math.addExact(subtotal, lineTotal);
        }
        if (discountPesewas > subtotal) throw new IllegalArgumentException("Order discount exceeds subtotal");
        long total = Math.addExact(Math.addExact(Math.subtractExact(subtotal, discountPesewas), taxPesewas), tipPesewas);

        db.sql("""
            INSERT INTO pos_order (
                id, order_number, merchant_id, branch_id, terminal_id, cashier_id,
                customer_id, subtotal_pesewas, discount_pesewas, tax_pesewas, tip_pesewas,
                total_pesewas, paid_pesewas, change_pesewas, status, order_type, created_at
            ) VALUES (
                :id, :orderNumber, :merchantId, :branchId, :terminalId, :cashierId,
                :customerId, :subtotal, :discount, :tax, :tip,
                :total, 0, 0, 'PENDING', :orderType, now()
            )
            """)
            .param("id", orderId)
            .param("orderNumber", orderNumber)
            .param("merchantId", merchantId)
            .param("branchId", branchId)
            .param("terminalId", terminalId)
            .param("cashierId", effectiveCashierId)
            .param("customerId", customerId)
            .param("subtotal", subtotal)
            .param("discount", discountPesewas)
            .param("tax", taxPesewas)
            .param("tip", tipPesewas)
            .param("total", total)
            .param("orderType", orderType == null ? "STANDARD" : orderType)
            .update();

        for (int i = 0; i < items.size(); i++) {
            OrderItemInput item = items.get(i);
            UUID itemId = UUID.randomUUID();

            db.sql("""
                INSERT INTO pos_order_item (
                    id, order_id, variant_id, item_name, quantity, unit_price_pesewas,
                    discount_pesewas, tax_pesewas, total_pesewas, modifiers_json,
                    seat_number, course, notes
                ) VALUES (
                    :id, :orderId, :variantId, :itemName, :quantity, :unitPrice,
                    :discount, :tax, :total, :modifiersJson,
                    :seatNumber, :course, :notes
                )
                """)
                .param("id", itemId)
                .param("orderId", orderId)
                .param("variantId", item.variantId())
                .param("itemName", item.itemName())
                .param("quantity", item.quantity())
                .param("unitPrice", unitPrices.get(i))
                .param("discount", item.discountPesewas())
                .param("tax", item.taxPesewas())
                .param("total", lineTotals.get(i))
                .param("modifiersJson", item.modifiersJson() == null ? "[]" : item.modifiersJson())
                .param("seatNumber", item.seatNumber())
                .param("course", item.course())
                .param("notes", item.notes())
                .update();
        }

        return getOrder(orderId).orElseThrow();
    }

    public Optional<PosOrder> getOrder(UUID id) {
        return db.sql("""
            SELECT id, order_number, merchant_id, branch_id, terminal_id, cashier_id,
                   customer_id, subtotal_pesewas, discount_pesewas, tax_pesewas, tip_pesewas,
                   total_pesewas, paid_pesewas, change_pesewas, status, order_type, created_at
            FROM pos_order WHERE id = :id
            """)
            .param("id", id)
            .query(PosOrder.class)
            .optional();
    }

    public List<PosOrderItem> listOrderItems(UUID orderId) {
        return db.sql("""
            SELECT id, order_id, variant_id, item_name, quantity, unit_price_pesewas,
                   discount_pesewas, tax_pesewas, total_pesewas, modifiers_json,
                   seat_number, course, notes, kds_round, sent_at, voided
            FROM pos_order_item
            WHERE order_id = :orderId AND NOT voided
            ORDER BY id
            """)
            .param("orderId", orderId)
            .query(PosOrderItem.class)
            .list();
    }

    public List<PosOrderItem> listAllOrderItems(UUID orderId) {
        return db.sql("""
            SELECT id, order_id, variant_id, item_name, quantity, unit_price_pesewas,
                   discount_pesewas, tax_pesewas, total_pesewas, modifiers_json,
                   seat_number, course, notes, kds_round, sent_at, voided
            FROM pos_order_item
            WHERE order_id = :orderId
            ORDER BY id
            """)
            .param("orderId", orderId)
            .query(PosOrderItem.class)
            .list();
    }

    public List<TenderPayment> listTenderPayments(UUID orderId) {
        return db.sql("""
            SELECT id, order_id, tender_type, amount_pesewas, reference, status, created_at, split_key
            FROM pos_tender_payment WHERE order_id = :orderId
            ORDER BY created_at ASC
            """)
            .param("orderId", orderId)
            .query(TenderPayment.class)
            .list();
    }

    @Transactional
    public PosOrder addItemsToOrder(UUID orderId, List<OrderItemInput> items) {
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("items_required", "At least one item is required");
        }
        PosOrder order = lockOrder(orderId);
        if (!PAYABLE_STATUSES.contains(order.status())) {
            throw ApiException.conflict("order_not_modifiable",
                    "Order is " + order.status() + " and cannot accept additional items");
        }
        long paidSplits = db.sql("""
                SELECT count(*) FROM pos_order_split
                WHERE order_id = :id AND paid_pesewas > 0
                """).param("id", orderId).query(Long.class).single();
        if (paidSplits > 0 || openDigitalCheckoutAmount(orderId) > 0) {
            throw ApiException.conflict("split_already_in_progress",
                    "Cannot add items while split bill payments or digital checkouts are in progress");
        }
        db.sql("DELETE FROM pos_order_split WHERE order_id = :id").param("id", orderId).update();

        List<Long> unitPrices = new ArrayList<>(items.size());
        List<Long> lineTotals = new ArrayList<>(items.size());
        for (OrderItemInput item : items) {
            long unitPrice = resolveAuthoritativeUnitPrice(order.merchantId(), item);
            long lineTotal = itemTotal(item, unitPrice);
            unitPrices.add(unitPrice);
            lineTotals.add(lineTotal);
        }

        for (int i = 0; i < items.size(); i++) {
            OrderItemInput item = items.get(i);
            UUID itemId = UUID.randomUUID();
            db.sql("""
                INSERT INTO pos_order_item (
                    id, order_id, variant_id, item_name, quantity, unit_price_pesewas,
                    discount_pesewas, tax_pesewas, total_pesewas, modifiers_json,
                    seat_number, course, notes, kds_round, sent_at, voided
                ) VALUES (
                    :id, :orderId, :variantId, :itemName, :quantity, :unitPrice,
                    :discount, :tax, :total, :modifiersJson,
                    :seatNumber, :course, :notes, NULL, NULL, false
                )
                """)
                .param("id", itemId)
                .param("orderId", orderId)
                .param("variantId", item.variantId())
                .param("itemName", item.itemName())
                .param("quantity", item.quantity())
                .param("unitPrice", unitPrices.get(i))
                .param("discount", item.discountPesewas())
                .param("tax", item.taxPesewas())
                .param("total", lineTotals.get(i))
                .param("modifiersJson", item.modifiersJson() == null ? "[]" : item.modifiersJson())
                .param("seatNumber", item.seatNumber())
                .param("course", item.course())
                .param("notes", item.notes())
                .update();
        }

        long subtotal = db.sql("""
                SELECT COALESCE(SUM(total_pesewas), 0)
                FROM pos_order_item
                WHERE order_id = :id AND NOT voided
                """).param("id", orderId).query(Long.class).single();
        long total = Math.addExact(
                Math.addExact(Math.subtractExact(subtotal, order.discountPesewas()), order.taxPesewas()),
                order.tipPesewas());

        db.sql("""
                UPDATE pos_order
                SET subtotal_pesewas = :subtotal, total_pesewas = :total
                WHERE id = :id
                """)
                .param("subtotal", subtotal)
                .param("total", total)
                .param("id", orderId)
                .update();

        tableServiceProvider.ifAvailable(ts -> ts.revertBillPrintedToOccupied(order.merchantId(), orderId));
        return getOrder(orderId).orElseThrow();
    }

    @Transactional
    public Optional<KdsService.KdsTicket> fireUnfiredItemsToKds(
            UUID orderId,
            UUID tableIdOverride,
            String serverNameOverride,
            String notes
    ) {
        PosOrder order = lockOrder(orderId);
        DiningMode mode = switch (order.orderType() == null ? "" : order.orderType().toUpperCase(Locale.ROOT)) {
            case "DINE_IN" -> DiningMode.DINE_IN;
            case "TAKEAWAY", "TAKEOUT" -> DiningMode.TAKEOUT;
            case "DELIVERY" -> DiningMode.DELIVERY;
            default -> null;
        };
        if (mode == null) {
            return Optional.empty();
        }

        List<PosOrderItem> unfired = db.sql("""
                SELECT i.id, i.order_id, i.variant_id, i.item_name, i.quantity, i.unit_price_pesewas,
                       i.discount_pesewas, i.tax_pesewas, i.total_pesewas, i.modifiers_json,
                       i.seat_number, i.course, i.notes, i.kds_round, i.sent_at, i.voided
                FROM pos_order_item i
                WHERE i.order_id = :orderId
                  AND i.sent_at IS NULL
                  AND NOT i.voided
                  AND NOT EXISTS (
                      SELECT 1 FROM restaurant_kds_item ki
                      WHERE ki.order_item_id = i.id AND ki.item_status <> 'CANCELLED'
                  )
                ORDER BY i.id
                FOR UPDATE
                """)
                .param("orderId", orderId)
                .query(PosOrderItem.class)
                .list();
        if (unfired.isEmpty()) {
            return Optional.empty();
        }

        int kdsNext = kdsService.nextRoundForOrder(orderId);
        int itemNext = db.sql("""
                SELECT COALESCE(MAX(kds_round), 0) + 1
                FROM pos_order_item
                WHERE order_id = :id
                """).param("id", orderId).query(Integer.class).single();
        int round = Math.max(kdsNext, itemNext);
        String clientRef = "pos:" + orderId + ":r" + round;

        UUID tableId = tableIdOverride != null ? tableIdOverride : resolveOrderTableId(order.merchantId(), orderId);
        String serverName = (serverNameOverride != null && !serverNameOverride.isBlank())
                ? serverNameOverride
                : (order.cashierId() == null ? null
                        : cashierService.getCashier(order.cashierId()).map(PosCashierService.Cashier::name).orElse(null));

        List<KdsService.KdsItemInput> kdsItems = unfired.stream()
                .map(i -> {
                    String modOrNotes = (i.modifiersJson() != null && !i.modifiersJson().isBlank()
                            && !"[]".equals(i.modifiersJson().trim()))
                            ? i.modifiersJson()
                            : (i.notes() != null && !i.notes().isBlank() ? i.notes() : i.modifiersJson());
                    return new KdsService.KdsItemInput(i.id(), i.itemName(), i.quantity(), modOrNotes, i.course());
                })
                .toList();

        List<String> distinctCourses = unfired.stream()
                .map(PosOrderItem::course)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .toList();
        String ticketCourse = distinctCourses.isEmpty() ? null
                : (distinctCourses.size() == 1 ? distinctCourses.get(0) : "ALL");

        KdsService.KdsTicket ticket = kdsService.createTicket(
                order.merchantId(),
                orderId,
                tableId,
                mode,
                serverName,
                ticketCourse,
                kdsItems,
                notes,
                clientRef,
                round
        );

        for (PosOrderItem item : unfired) {
            db.sql("""
                    UPDATE pos_order_item
                    SET kds_round = :round, sent_at = now()
                    WHERE id = :id
                    """)
                    .param("round", round)
                    .param("id", item.id())
                    .update();
        }
        return Optional.of(ticket);
    }

    public record DigitalCheckout(UUID id, UUID orderId, String reference, long amount, String status, String payUrl) {}
    private record Link(UUID id, UUID orderId, String checkoutRef, long amount, String status, String splitKey) {}
    private record OrderSplitRow(UUID id, UUID orderId, String splitKey, long amountPesewas, long paidPesewas, String status) {}

    private PosOrder lockOrder(UUID orderId) {
        db.sql("SELECT id FROM pos_order WHERE id = :id FOR UPDATE").param("id", orderId).query(UUID.class)
                .optional().orElseThrow(() -> ApiException.notFound("Order not found"));
        return getOrder(orderId).orElseThrow();
    }

    private Optional<Link> link(UUID orderId) {
        return db.sql("""
                SELECT id, order_id, checkout_ref, amount, status, split_key FROM pos_checkout_link
                WHERE order_id = :id AND status IN ('open','applied') ORDER BY created_at DESC LIMIT 1
                """).param("id", orderId).query(Link.class).optional();
    }

    private Optional<Link> linkForSplit(UUID orderId, String splitKey) {
        String normalized = splitKey == null ? "" : splitKey;
        return db.sql("""
                SELECT id, order_id, checkout_ref, amount, status, split_key FROM pos_checkout_link
                WHERE order_id = :id AND COALESCE(split_key, '') = :splitKey AND status IN ('open','applied')
                LIMIT 1
                """).param("id", orderId).param("splitKey", normalized).query(Link.class).optional();
    }

    private Optional<OrderSplitRow> lockOrderSplit(UUID orderId, String splitKey) {
        return db.sql("""
                SELECT id, order_id, split_key, amount_pesewas, paid_pesewas, status
                FROM pos_order_split
                WHERE order_id = :orderId AND split_key = :splitKey
                FOR UPDATE
                """)
                .param("orderId", orderId)
                .param("splitKey", splitKey)
                .query(OrderSplitRow.class)
                .optional();
    }

    @Transactional
    public DigitalCheckout startDigital(UUID orderId, String actor) {
        PosOrder order = lockOrder(orderId);
        Optional<Link> existing = linkForSplit(orderId, null);
        if (existing.isPresent()) {
            Link l = existing.get();
            return new DigitalCheckout(l.id(), orderId, l.checkoutRef(), l.amount(), l.status(), checkout.payUrl(l.checkoutRef()));
        }
        requirePayable(order);
        long amount = Math.subtractExact(order.totalPesewas(), order.paidPesewas());
        if (amount <= 0)
            throw ApiException.conflict("order_paid", "Order already paid");
        UUID linkId = UUID.randomUUID();
        String ref = checkout.createSplitAware(order.merchantId(), order.branchId(), linkId, amount, null, actor);
        db.sql("""
                INSERT INTO pos_checkout_link (id, order_id, checkout_ref, amount, status, split_key)
                VALUES (:id, :order, :ref, :amount, 'open', NULL)
                """).param("id", linkId).param("order", orderId).param("ref", ref).param("amount", amount).update();
        return new DigitalCheckout(linkId, orderId, ref, amount, "open", checkout.payUrl(ref));
    }

    @Transactional
    public PosOrder confirmDigital(UUID orderId) {
        return confirmDigital(orderId, null);
    }

    @Transactional
    public PosOrder confirmDigital(UUID orderId, String splitKey) {
        PosOrder order = lockOrder(orderId);
        String normalizedSplit = (splitKey != null && !splitKey.isBlank()) ? splitKey.trim() : null;
        Link l = (normalizedSplit != null ? linkForSplit(orderId, normalizedSplit) : link(orderId))
                .orElseThrow(() -> ApiException.notFound("Digital checkout not found"));
        if ("applied".equals(l.status())) return order;
        if (l.amount() > Math.subtractExact(order.totalPesewas(), order.paidPesewas()))
            throw ApiException.conflict("pos_payment_mismatch", "POS balance no longer matches checkout");
        var evidence = checkout.requirePaid(l.checkoutRef(), order.merchantId(), order.branchId(), l.amount());
        TenderType type = mapMethodToTenderType(evidence.method());
        db.sql("UPDATE pos_checkout_link SET status = 'applied', journal_tx = :journal WHERE id = :id")
                .param("journal", evidence.journalTx()).param("id", l.id()).update();
        return recordTender(order, type, l.amount(), l.checkoutRef(), l.splitKey());
    }

    @Transactional
    public void cancelDigital(UUID orderId, String actor) {
        cancelDigital(orderId, null, actor);
    }

    @Transactional
    public void cancelDigital(UUID orderId, String splitKey, String actor) {
        PosOrder order = lockOrder(orderId);
        String normalizedSplit = (splitKey != null && !splitKey.isBlank()) ? splitKey.trim() : null;
        Link l = (normalizedSplit != null ? linkForSplit(orderId, normalizedSplit) : link(orderId))
                .orElseThrow(() -> ApiException.notFound("Digital checkout not found"));
        if (!"open".equals(l.status()))
            throw ApiException.conflict("checkout_paid", "Paid checkout cannot be cancelled");
        checkout.cancel(order.merchantId(), order.branchId(), l.checkoutRef(), actor);
        db.sql("UPDATE pos_checkout_link SET status = 'cancelled' WHERE id = :id").param("id", l.id()).update();
    }

    @Transactional
    public PosOrder voidOrder(UUID orderId, String actor) {
        PosOrder order = lockOrder(orderId);
        if ("VOIDED".equals(order.status())) {
            return order;
        }
        if (!"PENDING".equals(order.status()) || order.paidPesewas() > 0) {
            throw ApiException.conflict("order_cannot_be_voided", "Only unpaid pending orders can be voided");
        }
        List<Link> links = db.sql("""
                SELECT id, order_id, checkout_ref, amount, status, split_key
                FROM pos_checkout_link
                WHERE order_id = :id AND status IN ('open', 'applied')
                FOR UPDATE
                """).param("id", orderId).query(Link.class).list();
        for (Link l : links) {
            if ("applied".equals(l.status())) {
                throw ApiException.conflict("order_cannot_be_voided", "Order has an applied digital payment and cannot be voided");
            }
        }
        for (Link l : links) {
            if ("open".equals(l.status())) {
                checkout.cancel(order.merchantId(), order.branchId(), l.checkoutRef(), actor);
                db.sql("UPDATE pos_checkout_link SET status = 'cancelled' WHERE id = :id")
                        .param("id", l.id()).update();
            }
        }
        db.sql("UPDATE pos_order SET status = 'VOIDED' WHERE id = :id")
                .param("id", orderId).update();
        List<UUID> openTicketIds = db.sql("""
                SELECT id FROM restaurant_kds_ticket
                WHERE merchant_id = :m AND order_id = :orderId
                  AND status IN ('NEW', 'PREPARING', 'READY')
                ORDER BY created_at ASC, id ASC
                FOR UPDATE
                """)
                .param("m", order.merchantId())
                .param("orderId", orderId)
                .query(UUID.class)
                .list();
        for (UUID ticketId : openTicketIds) {
            kdsService.cancel(ticketId, "void:" + ticketId, "Order voided");
        }
        return getOrder(orderId).orElseThrow();
    }

    @Transactional
    public TenderResult initiateTender(UUID orderId, TenderRequest req, String actor) {
        if (req == null || req.tenderType() == null) {
            throw ApiException.badRequest("tender_required", "Tender type is required");
        }
        PosOrder order = lockOrder(orderId);
        requirePayable(order);
        long remaining = Math.subtractExact(order.totalPesewas(), order.paidPesewas());
        String splitKey = (req.splitKey() != null && !req.splitKey().isBlank()) ? req.splitKey().trim() : null;

        long effectiveRemaining = remaining;
        long splitCount = db.sql("SELECT count(*) FROM pos_order_split WHERE order_id = :id")
                .param("id", orderId).query(Long.class).single();
        if (splitCount > 0) {
            if (splitKey == null) {
                throw ApiException.badRequest("split_key_required", "Order has active split bills; specify splitKey");
            }
            OrderSplitRow split = lockOrderSplit(orderId, splitKey)
                    .orElseThrow(() -> ApiException.notFound("Split bill not found"));
            long splitRemaining = Math.subtractExact(split.amountPesewas(), split.paidPesewas());
            if (splitRemaining <= 0 || "PAID".equals(split.status())) {
                throw ApiException.conflict("split_already_paid", "Sub-bill is already paid");
            }
            effectiveRemaining = Math.min(remaining, splitRemaining);
        }

        long amount = req.amountPesewas() != null ? req.amountPesewas() : effectiveRemaining;
        if (amount <= 0) {
            throw ApiException.badRequest("invalid_amount", "Tender amount must be positive");
        }

        if (req.tenderType().isDirectPosTender()) {
            String normalizedRef = req.reference() == null ? null : req.reference().trim();
            if (req.tenderType().isExternalRecorded()) {
                validateExternalReference(order.merchantId(), req.tenderType(), normalizedRef);
                if (amount > effectiveRemaining) {
                    throw ApiException.badRequest("amount_exceeds_balance",
                            req.tenderType().displayLabel() + " amount cannot exceed remaining balance");
                }
            }
            // Prevent double-capturing the same split or over-capturing the order across concurrent splits.
            if (hasOpenDigitalCheckoutForSplit(orderId, splitKey)) {
                throw ApiException.conflict("digital_payment_active",
                        "Resolve the linked checkout before recording payment");
            }
            long projectedApplied = req.tenderType() == TenderType.CASH ? Math.min(amount, effectiveRemaining) : amount;
            if (Math.addExact(openDigitalCheckoutAmount(orderId), projectedApplied) > remaining) {
                throw ApiException.conflict("digital_payment_active",
                        "Open digital checkouts exceed remaining balance");
            }
            PosOrder updated = recordTender(order, req.tenderType(), amount, normalizedRef, splitKey);
            String prompt = req.tenderType().isExternalRecorded()
                    ? req.tenderType().displayLabel() + " recorded (external processor — not settled by ELEPAY)"
                    : "Cash tender recorded";
            return new TenderResult(orderId, updated.status(), req.tenderType(), amount,
                    null, null, null, "paid", null, null, null, prompt, updated);
        }

        if (amount > effectiveRemaining) {
            throw ApiException.badRequest("amount_exceeds_balance", "Digital tender amount cannot exceed remaining balance");
        }

        Optional<Link> existing = linkForSplit(orderId, splitKey);
        Link linkRecord;
        if (existing.isPresent()) {
            linkRecord = existing.get();
            if ("applied".equals(linkRecord.status())) {
                return new TenderResult(orderId, order.status(), req.tenderType(), linkRecord.amount(),
                        linkRecord.id(), linkRecord.checkoutRef(), null, "paid",
                        checkout.payUrl(linkRecord.checkoutRef()), null, null, "Tender already applied", order);
            }
            if (linkRecord.amount() != amount) {
                throw ApiException.conflict("open_checkout_amount_mismatch",
                        "An open digital checkout exists for a different amount; cancel it before changing tender amount");
            }
        } else {
            long openLinkedAmount = openDigitalCheckoutAmount(orderId);
            if (Math.addExact(openLinkedAmount, amount) > remaining) {
                throw ApiException.conflict("open_checkouts_exceed_balance", "Open split checkouts exceed remaining balance");
            }
            UUID linkId = UUID.randomUUID();
            String ref = checkout.createSplitAware(order.merchantId(), order.branchId(), linkId, amount, splitKey, actor);
            db.sql("""
                    INSERT INTO pos_checkout_link (id, order_id, checkout_ref, amount, status, split_key)
                    VALUES (:id, :order, :ref, :amount, 'open', :splitKey)
                    """)
                    .param("id", linkId)
                    .param("order", orderId)
                    .param("ref", ref)
                    .param("amount", amount)
                    .param("splitKey", splitKey)
                    .update();
            linkRecord = new Link(linkId, orderId, ref, amount, "open", splitKey);
        }

        return switch (req.tenderType()) {
            case MOMO -> {
                String msisdn = References.msisdn(req.phone());
                String network = req.network() == null ? null : req.network().trim().toLowerCase();
                if (network == null || !MOMO_NETWORKS.contains(network)) {
                    throw ApiException.badRequest("invalid_network", "Choose mtn, telecel or airteltigo");
                }
                var railRes = checkout.startAndSubmitRail(linkRecord.checkoutRef(), "momo", msisdn, network, req.email());
                yield new TenderResult(orderId, order.status(), TenderType.MOMO, linkRecord.amount(),
                        linkRecord.id(), linkRecord.checkoutRef(), railRes.attemptId(), railRes.status(),
                        checkout.payUrl(linkRecord.checkoutRef()), null, null,
                        railRes.message() != null ? railRes.message() : "Approve the payment on your phone", order);
            }
            case CARD -> {
                var railRes = checkout.startAndSubmitRail(linkRecord.checkoutRef(), "card", null, null, req.email());
                yield new TenderResult(orderId, order.status(), TenderType.CARD, linkRecord.amount(),
                        linkRecord.id(), linkRecord.checkoutRef(), railRes.attemptId(), railRes.status(),
                        checkout.payUrl(linkRecord.checkoutRef()), null, null,
                        railRes.message() != null ? railRes.message() : "Complete card payment", order);
            }
            case GHQR -> {
                String merchantName = merchants.find(order.merchantId())
                        .map(MerchantAccess.Merchant::tradingName)
                        .orElse("ELEPAY Merchant");
                String terminalRef = order.terminalId() != null ? order.terminalId().toString().replace("-", "") : "POS";
                var qr = checkout.createGhQrIntent(order.merchantId(), terminalRef, linkRecord.amount(), merchantName);
                var railRes = checkout.startAndSubmitRail(linkRecord.checkoutRef(), "ghqr", null, null, req.email());
                PosOrder latestOrder = getOrder(orderId).orElse(order);
                yield new TenderResult(orderId, latestOrder.status(), TenderType.GHQR, linkRecord.amount(),
                        linkRecord.id(), linkRecord.checkoutRef(), railRes.attemptId(), railRes.status(),
                        checkout.payUrl(linkRecord.checkoutRef()), qr.payloadText(), qr.qrReference(),
                        railRes.message() != null ? railRes.message() : "Scan GhQR code to pay", latestOrder);
            }
            case WALLET -> new TenderResult(orderId, order.status(), TenderType.WALLET, linkRecord.amount(),
                    linkRecord.id(), linkRecord.checkoutRef(), null, "open",
                    checkout.payUrl(linkRecord.checkoutRef()), null, null,
                    "Confirm wallet payment", order);
            case CASH, BANK_TRANSFER, EXTERNAL_MOMO, EXTERNAL_CARD, EXTERNAL_POS ->
                    throw new IllegalStateException("Handled above");
        };
    }

    @EventListener
    @Transactional
    public void onCheckoutPaid(PosCheckoutGateway.CheckoutPaidEvent event) {
        Optional<UUID> orderIdOpt = db.sql("""
                SELECT order_id
                FROM pos_checkout_link
                WHERE checkout_ref = :ref
                """).param("ref", event.reference()).query(UUID.class).optional();
        if (orderIdOpt.isEmpty()) {
            return; // Not a POS checkout
        }
        // Acquire pos_order lock BEFORE pos_checkout_link lock to match voidOrder() and prevent AB-BA deadlocks.
        PosOrder order = lockOrder(orderIdOpt.get());
        Optional<Link> linkOpt = db.sql("""
                SELECT id, order_id, checkout_ref, amount, status, split_key
                FROM pos_checkout_link
                WHERE checkout_ref = :ref
                FOR UPDATE
                """).param("ref", event.reference()).query(Link.class).optional();
        if (linkOpt.isEmpty()) {
            return;
        }
        Link l = linkOpt.get();
        if (!"open".equals(l.status())) {
            return; // Already applied or cancelled
        }
        if (!PAYABLE_STATUSES.contains(order.status())) {
            opsQueue.raise("pos_overpayment", "high", "pos-checkout:" + l.checkoutRef(), Map.of(
                    "orderId", order.id().toString(),
                    "merchantId", order.merchantId().toString(),
                    "orderStatus", String.valueOf(order.status()),
                    "checkoutRef", l.checkoutRef(),
                    "amountPesewas", String.valueOf(l.amount()),
                    "journalTx", String.valueOf(event.journalTx())));
            return;
        }
        TenderType tenderType = mapMethodToTenderType(event.method());
        db.sql("UPDATE pos_checkout_link SET status = 'applied', journal_tx = :journal WHERE id = :id")
                .param("journal", event.journalTx())
                .param("id", l.id())
                .update();
        recordTender(order, tenderType, l.amount(), l.checkoutRef(), l.splitKey());
    }

    private static TenderType mapMethodToTenderType(String method) {
        if (method == null) {
            throw ApiException.unprocessable("unsupported_payment", "Unsupported checkout payment method");
        }
        return switch (method.toLowerCase()) {
            case "momo" -> TenderType.MOMO;
            case "card" -> TenderType.CARD;
            case "wallet" -> TenderType.WALLET;
            case "ghqr" -> TenderType.GHQR;
            default -> throw ApiException.unprocessable("unsupported_payment", "Unsupported checkout payment method");
        };
    }

    @Transactional
    public PosOrder addTenderPayment(UUID orderId, TenderType tenderType, long amountPesewas, String reference) {
        return addTenderPayment(orderId, tenderType, amountPesewas, reference, null);
    }

    @Transactional
    public PosOrder addTenderPayment(UUID orderId, TenderType tenderType, long amountPesewas, String reference, String splitKey) {
        if (tenderType == null) {
            throw ApiException.badRequest("tender_type_required", "Tender type is required");
        }
        if (amountPesewas <= 0) {
            throw ApiException.badRequest("invalid_amount", "Payment amount must be positive");
        }
        if (!tenderType.isDirectPosTender())
            throw ApiException.unprocessable("digital_payment_unverified", "Use the linked ELEPAY checkout");
        PosOrder order = lockOrder(orderId);
        requirePayable(order);
        String normalizedSplit = (splitKey != null && !splitKey.isBlank()) ? splitKey.trim() : null;
        long remaining = Math.subtractExact(order.totalPesewas(), order.paidPesewas());
        long effectiveRemaining = remaining;
        long splitCount = db.sql("SELECT count(*) FROM pos_order_split WHERE order_id = :id")
                .param("id", orderId).query(Long.class).single();
        if (splitCount > 0) {
            if (normalizedSplit == null) {
                throw ApiException.badRequest("split_key_required", "Order has active split bills; specify splitKey");
            }
            OrderSplitRow split = lockOrderSplit(orderId, normalizedSplit)
                    .orElseThrow(() -> ApiException.notFound("Split bill not found"));
            long splitRemaining = Math.subtractExact(split.amountPesewas(), split.paidPesewas());
            if (splitRemaining <= 0 || "PAID".equals(split.status())) {
                throw ApiException.conflict("split_already_paid", "Sub-bill is already paid");
            }
            effectiveRemaining = Math.min(remaining, splitRemaining);
        }
        String normalizedRef = reference == null ? null : reference.trim();
        if (tenderType.isExternalRecorded()) {
            validateExternalReference(order.merchantId(), tenderType, normalizedRef);
            if (amountPesewas > effectiveRemaining) {
                throw ApiException.badRequest("amount_exceeds_balance",
                        tenderType.displayLabel() + " amount cannot exceed remaining balance");
            }
        }
        if (hasOpenDigitalCheckoutForSplit(orderId, normalizedSplit)) {
            throw ApiException.conflict("digital_payment_active", "Resolve the linked checkout before recording payment");
        }
        long projectedApplied = tenderType == TenderType.CASH ? Math.min(amountPesewas, effectiveRemaining) : amountPesewas;
        if (Math.addExact(openDigitalCheckoutAmount(orderId), projectedApplied) > remaining) {
            throw ApiException.conflict("digital_payment_active", "Open digital checkouts exceed remaining balance");
        }
        return recordTender(order, tenderType, amountPesewas, normalizedRef, normalizedSplit);
    }

    private void validateExternalReference(UUID merchantId, TenderType tenderType, String normalizedRef) {
        String label = tenderType.displayLabel();
        if (normalizedRef == null || normalizedRef.isBlank()) {
            throw ApiException.badRequest("reference_required", label + " reference is required");
        }
        if (normalizedRef.length() < 4) {
            throw ApiException.badRequest("reference_too_short", label + " reference must be at least 4 characters");
        }
        if (normalizedRef.length() > 128) {
            throw ApiException.badRequest("reference_too_long", label + " reference must be 128 characters or fewer");
        }
        boolean duplicate = Boolean.TRUE.equals(db.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM pos_tender_payment
                    WHERE merchant_id = :merchantId
                      AND tender_type = :tenderType
                      AND status = 'SUCCESS'
                      AND lower(trim(reference)) = lower(:ref)
                )
                """)
                .param("merchantId", merchantId)
                .param("tenderType", tenderType.name())
                .param("ref", normalizedRef)
                .query(Boolean.class)
                .single());
        if (duplicate) {
            throw ApiException.conflict("duplicate_external_reference",
                    label + " reference '" + normalizedRef + "' has already been recorded for this merchant");
        }
    }

    private PosOrder recordTender(PosOrder order, TenderType tenderType, long amountPesewas, String reference, String splitKey) {
        if (amountPesewas <= 0) throw ApiException.badRequest("invalid_amount", "Payment amount must be positive");
        UUID orderId = order.id();
        requirePayable(order);

        long remaining = Math.max(0, Math.subtractExact(order.totalPesewas(), order.paidPesewas()));
        long effectiveRemaining = remaining;
        if (splitKey != null) {
            Optional<OrderSplitRow> splitOpt = lockOrderSplit(orderId, splitKey);
            if (splitOpt.isPresent()) {
                long splitRemaining = Math.max(0, Math.subtractExact(splitOpt.get().amountPesewas(), splitOpt.get().paidPesewas()));
                effectiveRemaining = Math.min(remaining, splitRemaining);
            }
        }

        // Cash handed over beyond the balance is returned as change: it must not be booked
        // as paid nor added to the drawer's expected cash.
        long applied = tenderType == TenderType.CASH ? Math.min(amountPesewas, effectiveRemaining) : amountPesewas;
        long change = tenderType == TenderType.CASH ? amountPesewas - applied : 0;
        if (applied <= 0) throw ApiException.conflict("order_paid", "Order is already fully paid");

        UUID paymentId = UUID.randomUUID();
        try {
            db.sql("""
                INSERT INTO pos_tender_payment (id, order_id, merchant_id, tender_type, amount_pesewas, reference, status, created_at, split_key)
                VALUES (:id, :orderId, :merchantId, :tenderType, :amount, :reference, 'SUCCESS', now(), :splitKey)
                """)
                .param("id", paymentId)
                .param("orderId", orderId)
                .param("merchantId", order.merchantId())
                .param("tenderType", tenderType.name())
                .param("amount", applied)
                .param("reference", reference)
                .param("splitKey", splitKey)
                .update();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            if (tenderType.isExternalRecorded()) {
                throw ApiException.conflict("duplicate_external_reference",
                        tenderType.displayLabel() + " reference '" + reference + "' has already been recorded for this merchant");
            }
            throw e;
        }

        // Update active shift ONLY for CASH (external tenders are excluded from shift cash drawer)
        if (tenderType == TenderType.CASH && order.terminalId() != null) {
            cashierService.getActiveShift(order.terminalId())
                    .ifPresent(shift -> cashierService.recordCashTender(shift.id(), applied));
        }

        if (splitKey != null) {
            db.sql("""
                    UPDATE pos_order_split
                    SET paid_pesewas = paid_pesewas + :applied,
                        status = CASE WHEN paid_pesewas + :applied >= amount_pesewas THEN 'PAID' ELSE 'PARTIAL' END,
                        paid_at = CASE WHEN paid_pesewas + :applied >= amount_pesewas THEN COALESCE(paid_at, now()) ELSE paid_at END
                    WHERE order_id = :orderId AND split_key = :splitKey
                    """)
                    .param("applied", applied)
                    .param("orderId", orderId)
                    .param("splitKey", splitKey)
                    .update();
        }

        long newPaid = Math.addExact(order.paidPesewas(), applied);
        long unpaidSplits = db.sql("""
                SELECT count(*) FROM pos_order_split
                WHERE order_id = :id AND status <> 'PAID'
                """).param("id", orderId).query(Long.class).single();
        String status = (newPaid >= order.totalPesewas() && unpaidSplits == 0) ? "PAID" : "PARTIAL";

        db.sql("""
            UPDATE pos_order
            SET paid_pesewas = :newPaid, change_pesewas = change_pesewas + :change, status = :status
            WHERE id = :id
            """)
            .param("newPaid", newPaid)
            .param("change", change)
            .param("status", status)
            .param("id", orderId)
            .update();

        PosOrder updated = getOrder(orderId).orElseThrow();
        if ("PAID".equals(status)) {
            deductInventoryForOrder(updated);
            receiptServiceProvider.ifAvailable(receipts -> receipts.generateReceipt(orderId));
            autoCreateKdsTicketIfKitchenOrder(updated);
        }

        return updated;
    }

    private void autoCreateKdsTicketIfKitchenOrder(PosOrder order) {
        fireUnfiredItemsToKds(order.id(), null, null, null);
    }

    private UUID resolveOrderTableId(UUID merchantId, UUID orderId) {
        return db.sql("""
                SELECT id FROM restaurant_table
                WHERE merchant_id = :m AND current_order_id = :orderId
                ORDER BY table_number ASC
                LIMIT 1
                """)
                .param("m", merchantId)
                .param("orderId", orderId)
                .query(UUID.class)
                .optional()
                .or(() -> db.sql("""
                        SELECT table_id FROM restaurant_waiter_order
                        WHERE merchant_id = :m AND order_id = :orderId
                        """)
                        .param("m", merchantId)
                        .param("orderId", orderId)
                        .query(UUID.class)
                        .optional())
                .orElse(null);
    }

    private void deductInventoryForOrder(PosOrder order) {
        UUID orderId = order.id();
        UUID merchantId = order.merchantId();
        Optional<UUID> warehouseOpt;
        if (order.branchId() != null) {
            warehouseOpt = db.sql("""
                    SELECT id FROM inventory_warehouse
                    WHERE merchant_id = :m AND branch_id = :b AND is_active = true
                    LIMIT 1
                    """)
                    .param("m", merchantId)
                    .param("b", order.branchId())
                    .query(UUID.class)
                    .optional();
        } else {
            warehouseOpt = db.sql("""
                    SELECT id FROM inventory_warehouse
                    WHERE merchant_id = :m AND branch_id IS NULL AND is_active = true
                    LIMIT 1
                    """)
                    .param("m", merchantId)
                    .query(UUID.class)
                    .optional()
                    .or(() -> db.sql("""
                            SELECT id FROM inventory_warehouse
                            WHERE merchant_id = :m AND is_active = true
                            ORDER BY created_at ASC LIMIT 1
                            """).param("m", merchantId).query(UUID.class).optional());
        }

        if (warehouseOpt.isEmpty()) {
            return;
        }

        UUID warehouseId = warehouseOpt.get();
        List<PosOrderItem> items = listOrderItems(orderId);
        for (PosOrderItem item : items) {
            if (item.variantId() != null) {
                try {
                    savepointTx.executeWithoutResult(st -> stockService.deductStock(
                            warehouseId,
                            item.variantId(),
                            item.quantity(),
                            MovementType.SALE_DEDUCTION,
                            orderId,
                            "POS Sale Order " + orderId
                    ));
                } catch (Exception e) {
                    opsQueue.raise(
                            "pos_low_stock",
                            "medium",
                            "pos-order:" + orderId + ":variant:" + item.variantId(),
                            Map.of(
                                    "orderId", orderId.toString(),
                                    "merchantId", merchantId.toString(),
                                    "warehouseId", warehouseId.toString(),
                                    "variantId", item.variantId().toString(),
                                    "quantity", item.quantity().toPlainString(),
                                    "error", String.valueOf(e.getMessage())
                            )
                    );
                }
                try {
                    savepointTx.executeWithoutResult(st ->
                            recipeCostingService.deductRecipeStock(item.variantId(), item.quantity(), warehouseId, orderId));
                } catch (Exception e) {
                    opsQueue.raise(
                            "pos_recipe_low_stock",
                            "medium",
                            "pos-order:" + orderId + ":recipe:" + item.variantId(),
                            Map.of(
                                    "orderId", orderId.toString(),
                                    "merchantId", merchantId.toString(),
                                    "warehouseId", warehouseId.toString(),
                                    "variantId", item.variantId().toString(),
                                    "quantity", item.quantity().toPlainString(),
                                    "error", String.valueOf(e.getMessage())
                            )
                    );
                }
            }
        }
    }
}
