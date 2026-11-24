package com.elepay.merchanttools.pos;

import com.elepay.checkout.PosCheckoutGateway;
import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.merchanttools.MerchantToolAccess;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosNfcPaymentService {

    private static final Pattern MASKED_PAN_PATTERN = Pattern.compile("^\\d{6}\\*{4,9}\\d{4}$");
    private static final String GHS_NUMERIC_CURRENCY = "0936";

    private final JdbcClient db;
    private final MerchantToolAccess access;
    private final Idempotency idempotency;
    private final PosOrderService orderService;
    private final PosTerminalService terminalService;
    private final PosDukptKeyManagementService dukptService;
    private final EmvTlvParser emvTlvParser;
    private final PosCheckoutGateway checkoutGateway;

    public PosNfcPaymentService(
            JdbcClient db,
            MerchantToolAccess access,
            Idempotency idempotency,
            PosOrderService orderService,
            PosTerminalService terminalService,
            PosDukptKeyManagementService dukptService,
            EmvTlvParser emvTlvParser,
            PosCheckoutGateway checkoutGateway) {
        this.db = db;
        this.access = access;
        this.idempotency = idempotency;
        this.orderService = orderService;
        this.terminalService = terminalService;
        this.dukptService = dukptService;
        this.emvTlvParser = emvTlvParser;
        this.checkoutGateway = checkoutGateway;
    }

    public record NfcTapRequest(
            UUID terminalId,
            String ksn,
            String emvTlvHex,
            String maskedPan,
            String cardScheme,
            String cardToken,
            long amountPesewas,
            String currency,
            String encryptedPinBlock
    ) {}

    public record NfcTapResult(
            UUID authorizationId,
            UUID orderId,
            UUID terminalId,
            String ksn,
            long ksnCounter,
            String cardScheme,
            String cvmMethod,
            String authCode,
            String checkoutRef,
            String payUrl,
            long amountPesewas,
            String status,
            UUID attemptId
    ) {}

    @Transactional
    public NfcTapResult processNfcTap(UUID orderId, String idempotencyKey, NfcTapRequest req, String actor) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("idempotency_key_required", "Idempotency-Key header is required");
        }
        if (req == null || req.terminalId() == null) {
            throw ApiException.badRequest("terminal_required", "terminalId is required");
        }
        if (req.amountPesewas() <= 0) {
            throw ApiException.badRequest("invalid_amount", "Amount must be positive minor units");
        }
        if (req.maskedPan() != null && !req.maskedPan().isBlank()
                && !MASKED_PAN_PATTERN.matcher(req.maskedPan().trim()).matches()) {
            throw ApiException.badRequest("invalid_masked_pan",
                    "maskedPan must be masked as 6 digits + 4-9 asterisks + 4 digits (raw PANs are forbidden)");
        }

        PosOrderService.PosOrder order = orderService.getOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));

        access.belongs(order.merchantId(), MerchantToolAccess.Resource.TERMINAL, req.terminalId());

        return idempotency.execute(
                "pos-nfc-tap:" + order.merchantId() + ":" + orderId,
                idempotencyKey,
                req,
                NfcTapResult.class,
                () -> executeNfcTap(order, req, actor)
        );
    }

    private NfcTapResult executeNfcTap(PosOrderService.PosOrder order, NfcTapRequest req, String actor) {
        db.sql("SELECT id FROM pos_order WHERE id = :orderId FOR UPDATE")
                .param("orderId", order.id())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        PosOrderService.PosOrder lockedOrder = orderService.getOrder(order.id())
                .orElseThrow(() -> ApiException.notFound("Order not found"));

        PosTerminalService.PosTerminal terminal = terminalService.getTerminal(req.terminalId())
                .orElseThrow(() -> ApiException.notFound("Terminal not found"));
        if (!lockedOrder.merchantId().equals(terminal.merchantId())) {
            throw ApiException.notFound("Terminal not found");
        }
        if (terminal.branchId() != null && lockedOrder.branchId() != null
                && !terminal.branchId().equals(lockedOrder.branchId())) {
            throw ApiException.notFound("Terminal not found in order branch");
        }
        if (!"ACTIVE".equalsIgnoreCase(terminal.status())) {
            throw ApiException.conflict("terminal_not_active", "POS terminal is not ACTIVE");
        }

        long remaining = Math.subtractExact(lockedOrder.totalPesewas(), lockedOrder.paidPesewas());
        if ("PAID".equals(lockedOrder.status()) || remaining <= 0) {
            throw ApiException.conflict("order_paid", "Order is already paid");
        }
        if (req.amountPesewas() > remaining) {
            throw ApiException.badRequest("amount_exceeds_balance", "NFC tap amount exceeds remaining order balance");
        }

        long openLinkedAmount = db.sql("""
                SELECT COALESCE(SUM(amount), 0)
                FROM pos_checkout_link
                WHERE order_id = :orderId AND status = 'open'
                """)
                .param("orderId", lockedOrder.id())
                .query(Long.class)
                .single();
        if (Math.addExact(openLinkedAmount, req.amountPesewas()) > remaining) {
            throw ApiException.conflict("open_checkouts_exceed_balance",
                    "Open checkouts exceed remaining order balance");
        }

        boolean activeDefaultLinkExists = db.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM pos_checkout_link
                    WHERE order_id = :orderId
                      AND COALESCE(split_key, '') = ''
                      AND status IN ('open', 'applied')
                )
                """)
                .param("orderId", lockedOrder.id())
                .query(Boolean.class)
                .single();
        if (activeDefaultLinkExists) {
            throw ApiException.conflict("active_checkout_exists",
                    "An active checkout already exists for this order");
        }

        String currency = (req.currency() == null || req.currency().isBlank())
                ? "GHS"
                : req.currency().trim().toUpperCase(Locale.ROOT);
        if (!"GHS".equals(currency)) {
            throw ApiException.badRequest("unsupported_currency", "Only GHS (0936) is supported for POS NFC tap");
        }

        // 1. Strict EMV BER-TLV decode + PCI redaction gate (rejects raw PAN/Track2/PIN)
        EmvTlvParser.ParsedEmvPayload emv = emvTlvParser.parseStrictlyRedacted(req.emvTlvHex());
        if (!GHS_NUMERIC_CURRENCY.equals(emv.currencyNumeric())) {
            throw ApiException.badRequest("emv_currency_mismatch",
                    "EMV currency tag 5F2A must be 0936 (GHS)");
        }
        if (emv.amountMinorUnits() != req.amountPesewas()) {
            throw ApiException.badRequest("emv_amount_mismatch",
                    "EMV tag 9F02 amount does not match requested NFC tap amount");
        }

        // 2. Verify and consume KSN monotonically under FOR UPDATE lock
        PosDukptKeyManagementService.DukptTransactionKey dukptKey =
                dukptService.verifyAndConsumeKsn(lockedOrder.merchantId(), terminal.id(), req.ksn());

        // 3. Create checkout intent through PosCheckoutGateway (ledger-backed checkout bridge)
        UUID authId = UUID.randomUUID();
        String checkoutRef = checkoutGateway.create(
                lockedOrder.merchantId(),
                lockedOrder.branchId(),
                authId,
                req.amountPesewas(),
                actor != null ? actor : "pos-nfc");

        db.sql("""
            INSERT INTO pos_checkout_link (id, order_id, checkout_ref, amount, status)
            VALUES (:id, :orderId, :ref, :amount, 'open')
            """)
                .param("id", authId)
                .param("orderId", lockedOrder.id())
                .param("ref", checkoutRef)
                .param("amount", req.amountPesewas())
                .update();

        String scheme = (req.cardScheme() != null && !req.cardScheme().isBlank())
                ? req.cardScheme().trim().toUpperCase(Locale.ROOT)
                : emv.cardScheme();

        // The existing acquirer contract is hosted card approval, not native EMV authorization.
        // Prepare only; the controller submits after the order, KSN, link and idempotency record commit.
        var railRes = checkoutGateway.prepareRail(checkoutRef, "card", null, null, null);
        if (railRes == null || railRes.attemptId() == null) {
            throw ApiException.conflict("card_intent_unavailable", "Card payment intent could not be prepared");
        }
        String authCode = null; // Never manufacture an acquirer authorization code.
        String status = "AWAITING_CARD_APPROVAL";

        db.sql("""
            INSERT INTO pos_nfc_authorization (
                id, order_id, terminal_id, ksn, ksn_counter,
                emv_tags_redacted, masked_pan, card_scheme, cvm_method,
                auth_code, checkout_ref, status, created_at
            ) VALUES (
                :id, :orderId, :terminalId, :ksn, :ksnCounter,
                CAST(:emvJson AS jsonb), :maskedPan, :cardScheme, :cvmMethod,
                :authCode, :checkoutRef, :status, now()
            )
            """)
                .param("id", authId)
                .param("orderId", order.id())
                .param("terminalId", terminal.id())
                .param("ksn", dukptKey.ksn())
                .param("ksnCounter", dukptKey.transactionCounter())
                .param("emvJson", emv.redactedJson())
                .param("maskedPan", req.maskedPan() != null ? req.maskedPan().trim() : null)
                .param("cardScheme", scheme)
                .param("cvmMethod", emv.cvmMethod())
                .param("authCode", authCode)
                .param("checkoutRef", checkoutRef)
                .param("status", status)
                .update();

        return new NfcTapResult(
                authId,
                order.id(),
                terminal.id(),
                dukptKey.ksn(),
                dukptKey.transactionCounter(),
                scheme,
                emv.cvmMethod(),
                authCode,
                checkoutRef,
                checkoutGateway.payUrl(checkoutRef),
                req.amountPesewas(),
                status,
                railRes.attemptId()
        );
    }
    /** Hosted continuation only: an EMV payload does not prove acquirer authorization. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public NfcTapResult submitPreparedTap(NfcTapResult prepared) {
        var rail = checkoutGateway.submitPreparedRail(prepared.checkoutRef(), prepared.attemptId());
        String status = "paid".equals(rail.status()) ? "PAYMENT_CONFIRMED"
                : "failed".equals(rail.railStatus()) ? "PAYMENT_FAILED" : "AWAITING_CARD_APPROVAL";
        return new NfcTapResult(prepared.authorizationId(), prepared.orderId(), prepared.terminalId(),
                prepared.ksn(), prepared.ksnCounter(), prepared.cardScheme(), prepared.cvmMethod(),
                null, prepared.checkoutRef(), prepared.payUrl(), prepared.amountPesewas(), status, prepared.attemptId());
    }
}
