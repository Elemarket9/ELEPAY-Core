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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class PosOrderService {
    private static final Set<String> MOMO_NETWORKS = Set.of("mtn", "telecel", "airteltigo");

    private final JdbcClient db;
    private final StockService stockService;
    private final PosCashierService cashierService;
    private final PosCheckoutGateway checkout;
    private final OpsQueue opsQueue;
    private final RecipeCostingService recipeCostingService;
    private final KdsService kdsService;
    private final ObjectProvider<PosReceiptService> receiptServiceProvider;
    private final MerchantAccess merchants;
    private final ObjectMapper json;

    public PosOrderService(JdbcClient db,
                           StockService stockService,
                           PosCashierService cashierService,
                           PosCheckoutGateway checkout,
                           OpsQueue opsQueue,
                           RecipeCostingService recipeCostingService,
                           KdsService kdsService,
                           ObjectProvider<PosReceiptService> receiptServiceProvider,
                           MerchantAccess merchants,
                           ObjectMapper json) {
        this.db = db;
        this.stockService = stockService;
        this.cashierService = cashierService;
        this.checkout = checkout;
        this.opsQueue = opsQueue;
        this.recipeCostingService = recipeCostingService;
        this.kdsService = kdsService;
        this.receiptServiceProvider = receiptServiceProvider;
        this.merchants = merchants;
        this.json = json;
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
            String notes
    ) {}

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
            OffsetDateTime createdAt
    ) {}

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
        if (item.variantId() == null) {
            return item.unitPricePesewas();
        }
        long basePrice = db.sql("SELECT retail_price_pesewas FROM inventory_variant WHERE id = :id AND merchant_id = :m")
                .param("id", item.variantId())
                .param("m", merchantId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Variant not found"));
        long modifierPrice = resolveModifierAdditionalPrice(merchantId, item.modifiersJson());
        return Math.addExact(basePrice, modifierPrice);
    }

    private long resolveModifierAdditionalPrice(UUID merchantId, String modifiersJson) {
        if (modifiersJson == null || modifiersJson.isBlank() || "[]".equals(modifiersJson.trim())) {
            return 0L;
        }
        JsonNode root;
        try {
            root = json.readTree(modifiersJson);
        } catch (Exception e) {
            throw ApiException.badRequest("invalid_modifiers", "Invalid modifiers JSON");
        }
        if (!root.isArray()) {
            throw ApiException.badRequest("invalid_modifiers", "Modifiers must be a JSON array");
        }
        long total = 0L;
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
                long add = db.sql("""
                        SELECT o.additional_price_pesewas
                        FROM restaurant_modifier_option o
                        JOIN restaurant_modifier_group g ON g.id = o.group_id
                        WHERE o.id = :id AND g.merchant_id = :m
                        """)
                        .param("id", optionId)
                        .param("m", merchantId)
                        .query(Long.class)
                        .optional()
                        .orElseThrow(() -> ApiException.notFound("Modifier option not found"));
                total = Math.addExact(total, add);
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
                   seat_number, course, notes
            FROM pos_order_item WHERE order_id = :orderId
            """)
            .param("orderId", orderId)
            .query(PosOrderItem.class)
            .list();
    }

    public List<TenderPayment> listTenderPayments(UUID orderId) {
        return db.sql("""
            SELECT id, order_id, tender_type, amount_pesewas, reference, status, created_at
            FROM pos_tender_payment WHERE order_id = :orderId
            ORDER BY created_at ASC
            """)
            .param("orderId", orderId)
            .query(TenderPayment.class)
            .list();
    }

    public record DigitalCheckout(UUID id, UUID orderId, String reference, long amount, String status, String payUrl) {}
    private record Link(UUID id, UUID orderId, String checkoutRef, long amount, String status, String splitKey) {}

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

    @Transactional
    public DigitalCheckout startDigital(UUID orderId, String actor) {
        PosOrder order = lockOrder(orderId);
        Optional<Link> existing = linkForSplit(orderId, null);
        if (existing.isPresent()) {
            Link l = existing.get();
            return new DigitalCheckout(l.id(), orderId, l.checkoutRef(), l.amount(), l.status(), checkout.payUrl(l.checkoutRef()));
        }
        long amount = Math.subtractExact(order.totalPesewas(), order.paidPesewas());
        if ("PAID".equals(order.status()) || amount <= 0)
            throw ApiException.conflict("order_paid", "Order already paid");
        if (db.sql("SELECT EXISTS (SELECT 1 FROM pos_checkout_link WHERE order_id = :id AND status = 'open')")
                .param("id", orderId).query(Boolean.class).single()) {
            throw ApiException.conflict("digital_payment_active", "Resolve existing split checkouts before requesting the whole balance");
        }
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
        PosOrder order = lockOrder(orderId);
        Link l = link(orderId).orElseThrow(() -> ApiException.notFound("Digital checkout not found"));
        if ("applied".equals(l.status())) return order;
        if (l.amount() > Math.subtractExact(order.totalPesewas(), order.paidPesewas()))
            throw ApiException.conflict("pos_payment_mismatch", "POS balance no longer matches checkout");
        var evidence = checkout.requirePaid(l.checkoutRef(), order.merchantId(), order.branchId(), l.amount());
        TenderType type = mapMethodToTenderType(evidence.method());
        db.sql("UPDATE pos_checkout_link SET status = 'applied', journal_tx = :journal WHERE id = :id")
                .param("journal", evidence.journalTx()).param("id", l.id()).update();
        return recordTender(order, type, l.amount(), l.checkoutRef());
    }

    @Transactional
    public void cancelDigital(UUID orderId, String actor) {
        PosOrder order = lockOrder(orderId);
        Link l = link(orderId).orElseThrow(() -> ApiException.notFound("Digital checkout not found"));
        if (!"open".equals(l.status()))
            throw ApiException.conflict("checkout_paid", "Paid checkout cannot be cancelled");
        checkout.cancel(order.merchantId(), order.branchId(), l.checkoutRef(), actor);
        db.sql("UPDATE pos_checkout_link SET status = 'cancelled' WHERE id = :id").param("id", l.id()).update();
    }

    @Transactional
    public TenderResult initiateTender(UUID orderId, TenderRequest req, String actor) {
        if (req == null || req.tenderType() == null) {
            throw ApiException.badRequest("tender_required", "Tender type is required");
        }
        PosOrder order = lockOrder(orderId);
        if ("PAID".equals(order.status())) {
            throw ApiException.conflict("order_paid", "Order is already fully paid");
        }
        long remaining = Math.subtractExact(order.totalPesewas(), order.paidPesewas());
        long amount = req.amountPesewas() != null ? req.amountPesewas() : remaining;
        if (amount <= 0) {
            throw ApiException.badRequest("invalid_amount", "Tender amount must be positive");
        }

        if (req.tenderType() == TenderType.CASH) {
            PosOrder updated = recordTender(order, TenderType.CASH, amount, req.reference());
            return new TenderResult(orderId, updated.status(), TenderType.CASH, amount,
                    null, null, null, "paid", null, null, null, "Cash tender recorded", updated);
        }

        if (amount > remaining) {
            throw ApiException.badRequest("amount_exceeds_balance", "Digital tender amount cannot exceed remaining order balance");
        }

        String splitKey = (req.splitKey() != null && !req.splitKey().isBlank()) ? req.splitKey().trim() : null;
        Optional<Link> existing = linkForSplit(orderId, splitKey);
        Link linkRecord;
        if (existing.isPresent()) {
            linkRecord = existing.get();
            if (linkRecord.amount() != amount) throw ApiException.conflict("pos_payment_mismatch", "Existing tender has a different amount; resolve it before requesting another");
            if ("applied".equals(linkRecord.status())) {
                return new TenderResult(orderId, order.status(), req.tenderType(), linkRecord.amount(),
                        linkRecord.id(), linkRecord.checkoutRef(), null, "paid",
                        checkout.payUrl(linkRecord.checkoutRef()), null, null, "Tender already applied", order);
            }
        } else {
            long openLinkedAmount = db.sql("""
                    SELECT COALESCE(SUM(amount), 0) FROM pos_checkout_link
                    WHERE order_id = :id AND status = 'open'
                    """).param("id", orderId).query(Long.class).single();
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
            case CASH -> throw new IllegalStateException("Handled above");
        };
    }

    @EventListener
    @Transactional
    public void onCheckoutPaid(PosCheckoutGateway.CheckoutPaidEvent event) {
        Optional<Link> linkOpt = db.sql("""
                SELECT id, order_id, checkout_ref, amount, status, split_key
                FROM pos_checkout_link
                WHERE checkout_ref = :ref
                FOR UPDATE
                """).param("ref", event.reference()).query(Link.class).optional();
        if (linkOpt.isEmpty()) {
            return; // Not a POS checkout
        }
        Link l = linkOpt.get();
        if (!"open".equals(l.status())) {
            return; // Already applied or cancelled
        }
        PosOrder order = lockOrder(l.orderId());
        TenderType tenderType = mapMethodToTenderType(event.method());
        db.sql("UPDATE pos_checkout_link SET status = 'applied', journal_tx = :journal WHERE id = :id")
                .param("journal", event.journalTx())
                .param("id", l.id())
                .update();
        recordTender(order, tenderType, l.amount(), l.checkoutRef());
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
        if (tenderType != TenderType.CASH)
            throw ApiException.unprocessable("digital_payment_unverified", "Use the linked ELEPAY checkout");
        PosOrder order = lockOrder(orderId);
        return recordTender(order, tenderType, amountPesewas, reference);
    }

    private PosOrder recordTender(PosOrder order, TenderType tenderType, long amountPesewas, String reference) {
        if (amountPesewas <= 0) throw ApiException.badRequest("invalid_amount", "Payment amount must be positive");
        UUID orderId = order.id();
        if ("PAID".equals(order.status()))
            throw ApiException.conflict("order_paid", "Order is already fully paid");

        // Central guard covers both cash entry points and every open split checkout.
        if (tenderType == TenderType.CASH && db.sql("SELECT EXISTS (SELECT 1 FROM pos_checkout_link WHERE order_id = :id AND status = 'open')")
                .param("id", orderId).query(Boolean.class).single()) {
            throw ApiException.conflict("digital_payment_active", "Resolve all linked digital checkouts before recording cash");
        }
        long outstanding = Math.subtractExact(order.totalPesewas(), order.paidPesewas());
        if (outstanding <= 0 || (tenderType != TenderType.CASH && amountPesewas > outstanding)) {
            throw ApiException.conflict("pos_payment_mismatch", "Tender does not match the remaining order balance");
        }
        UUID paymentId = UUID.randomUUID();
        db.sql("""
            INSERT INTO pos_tender_payment (id, order_id, tender_type, amount_pesewas, reference, status, created_at)
            VALUES (:id, :orderId, :tenderType, :amount, :reference, 'SUCCESS', now())
            """)
            .param("id", paymentId)
            .param("orderId", orderId)
            .param("tenderType", tenderType.name())
            .param("amount", amountPesewas)
            .param("reference", reference)
            .update();

        // Update active shift if cash
        if (tenderType == TenderType.CASH && order.terminalId() != null) {
            cashierService.getActiveShift(order.terminalId())
                    .ifPresent(shift -> cashierService.recordCashTender(shift.id(), Math.min(amountPesewas, outstanding)));
        }

        long newPaid = Math.addExact(order.paidPesewas(), amountPesewas);
        long change = 0;
        String status = "PARTIAL";

        if (newPaid >= order.totalPesewas()) {
            status = "PAID";
            if (tenderType == TenderType.CASH && newPaid > order.totalPesewas()) {
                change = newPaid - order.totalPesewas();
            }
        }

        db.sql("""
            UPDATE pos_order
            SET paid_pesewas = :newPaid, change_pesewas = :change, status = :status
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
        DiningMode mode = switch (order.orderType() == null ? "" : order.orderType().toUpperCase()) {
            case "DINE_IN" -> DiningMode.DINE_IN;
            case "TAKEAWAY", "TAKEOUT" -> DiningMode.TAKEOUT;
            case "DELIVERY" -> DiningMode.DELIVERY;
            default -> null;
        };
        if (mode == null) {
            return;
        }
        List<PosOrderItem> orderItems = listOrderItems(order.id());
        if (orderItems.isEmpty()) {
            return;
        }
        List<KdsService.KdsItemInput> kdsItems = orderItems.stream()
                .map(i -> new KdsService.KdsItemInput(i.id(), i.itemName(), i.quantity(), i.modifiersJson(), i.course()))
                .toList();
        String serverName = order.cashierId() == null ? null
                : cashierService.getCashier(order.cashierId()).map(PosCashierService.Cashier::name).orElse(null);
        kdsService.createTicket(order.merchantId(), order.id(), null, mode, serverName, null, kdsItems, null,
                "pos:" + order.id());
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
                    stockService.deductStock(
                            warehouseId,
                            item.variantId(),
                            item.quantity(),
                            MovementType.SALE_DEDUCTION,
                            orderId,
                            "POS Sale Order " + orderId
                    );
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
                    recipeCostingService.deductRecipeStock(item.variantId(), item.quantity(), warehouseId, orderId);
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
