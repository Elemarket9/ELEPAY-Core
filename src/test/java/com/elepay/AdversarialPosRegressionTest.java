package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.ApiException;
import com.elepay.identity.ApiKeyAuthentication;
import com.elepay.merchanttools.inventory.ProductCatalogService;
import com.elepay.merchanttools.pos.PosCartService;
import com.elepay.merchanttools.pos.PosCashierService;
import com.elepay.merchanttools.pos.PosController;
import com.elepay.merchanttools.pos.PosDukptKeyManagementService;
import com.elepay.merchanttools.pos.PosOfflineSyncService;
import com.elepay.merchanttools.pos.PosOrderService;
import com.elepay.merchanttools.pos.PosTerminalService;
import com.elepay.merchanttools.pos.TenderType;
import com.elepay.merchanttools.restaurant.ModifierService;
import com.elepay.security.KmsSecretProvider;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;

class AdversarialPosRegressionTest extends PostgresTestBase {

    @Autowired
    private JdbcClient db;

    @Autowired
    private PosOfflineSyncService syncService;

    @Autowired
    private PosOrderService orderService;

    @Autowired
    private PosTerminalService terminalService;

    @Autowired
    private PosCashierService cashierService;

    @Autowired
    private PosController posController;

    @Autowired
    private ModifierService modifierService;

    @Autowired
    private ProductCatalogService catalogService;

    @Autowired
    private KmsSecretProvider kmsSecretProvider;

    private UUID merchantId;

    @BeforeEach
    void setupMerchant() {
        merchantId = UUID.randomUUID();
        db.sql("""
            INSERT INTO merchant (
                id, legal_name, trading_name, kind, status, contact_phone,
                merchant_code, fee_bps, fee_fixed, settlement_delay_hours, created_by
            ) VALUES (
                :id, 'POS Regression Ltd', 'POS Reg', 'merchant', 'active', '0241234567',
                :code, 0, 0, 24, 'system'
            ) ON CONFLICT DO NOTHING
            """)
            .param("id", merchantId)
            .param("code", String.format("%06d", (int) (Math.random() * 900_000 + 100_000)))
            .update();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("CRIT-06: Offline sync rejects null terminalId with 400 invalid_argument")
    void offlineSyncRejectsNullTerminalIdWith400() {
        var item = new PosOrderService.OrderItemInput(
                null, "Water", BigDecimal.ONE, 1000L, 0L, 0L, "[]", null, null, null);
        var payload = new PosOfflineSyncService.OfflineOrderPayload(
                "OFF-NULL-" + UUID.randomUUID(),
                "IDEM-NULL-" + UUID.randomUUID(),
                merchantId, null, null, null, null,
                List.of(item), 0L, 0L, 0L, 1000L,
                List.of(new PosOfflineSyncService.OfflinePayment(TenderType.CASH, 1000L, "CASH-1")),
                "SERVER_ADJUSTED"
        );

        assertThatThrownBy(() -> syncService.syncOfflineOrder(payload))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("invalid_argument");
                });
    }

    @Test
    @DisplayName("CRIT-06: Offline sync returns original serverOrderId on identical replay and 409 idempotency_conflict on payload mismatch")
    void offlineSyncReplayReturnsOriginalOrderAndRejectsPayloadMismatchWith409() {
        var terminalA = terminalService.registerTerminal(
                merchantId, null, "SN-SYNCA-" + UUID.randomUUID().toString().substring(0, 8), "PAX A920");
        var terminalB = terminalService.registerTerminal(
                merchantId, null, "SN-SYNCB-" + UUID.randomUUID().toString().substring(0, 8), "PAX A920");

        String idemKey = "IDEM-SYNC-" + UUID.randomUUID();
        String txId = "TX-SYNC-" + UUID.randomUUID();

        var itemOriginal = new PosOrderService.OrderItemInput(
                null, "Coffee", BigDecimal.ONE, 1000L, 0L, 0L, "[]", null, null, null);
        var payloadA = new PosOfflineSyncService.OfflineOrderPayload(
                txId, idemKey, merchantId, null, terminalA.id(), null, null,
                List.of(itemOriginal), 0L, 0L, 0L, 1000L,
                List.of(new PosOfflineSyncService.OfflinePayment(TenderType.CASH, 1000L, "CASH-A")),
                "SERVER_ADJUSTED"
        );

        var first = syncService.syncOfflineOrder(payloadA);
        assertThat(first.status()).isEqualTo("SYNCED");
        assertThat(first.serverOrderId()).isNotNull();

        // Identical retry must return 200 with the original serverOrderId
        var replay = syncService.syncOfflineOrder(payloadA);
        assertThat(replay.status()).isEqualTo("SYNCED");
        assertThat(replay.serverOrderId()).isEqualTo(first.serverOrderId());

        // Same (terminalId, idempotencyKey) with different payload must throw 409 idempotency_conflict
        var itemTampered = new PosOrderService.OrderItemInput(
                null, "Coffee", BigDecimal.ONE, 5000L, 0L, 0L, "[]", null, null, null);
        var tamperedPayload = new PosOfflineSyncService.OfflineOrderPayload(
                txId, idemKey, merchantId, null, terminalA.id(), null, null,
                List.of(itemTampered), 0L, 0L, 0L, 5000L,
                List.of(new PosOfflineSyncService.OfflinePayment(TenderType.CASH, 5000L, "CASH-A")),
                "SERVER_ADJUSTED"
        );
        assertThatThrownBy(() -> syncService.syncOfflineOrder(tamperedPayload))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("idempotency_conflict");
                });

        // Different terminal with the same idempotencyKey must remain isolated and succeed
        var payloadB = new PosOfflineSyncService.OfflineOrderPayload(
                txId, idemKey, merchantId, null, terminalB.id(), null, null,
                List.of(itemOriginal), 0L, 0L, 0L, 1000L,
                List.of(new PosOfflineSyncService.OfflinePayment(TenderType.CASH, 1000L, "CASH-B")),
                "SERVER_ADJUSTED"
        );
        var resultB = syncService.syncOfflineOrder(payloadB);
        assertThat(resultB.status()).isEqualTo("SYNCED");
        assertThat(resultB.serverOrderId()).isNotNull().isNotEqualTo(first.serverOrderId());
    }

    @Test
    @DisplayName("CRIT-11: PosOrderService rejects omitted required modifiers and enforces minSelection")
    void orderRejectsMissingRequiredModifierAndEnforcesMinSelection() {
        var category = catalogService.createCategory(merchantId, "Grill");
        var product = catalogService.createProduct(merchantId, category.id(), "Tilapia Plate", "Grilled", "Kitchen");
        var variant = catalogService.createVariant(
                merchantId, product.id(), "TIL-" + UUID.randomUUID().toString().substring(0, 6),
                null, "Regular Tilapia", "{}", 1000L, 2000L, false);

        var proteinGroup = modifierService.createModifierGroup(merchantId, "Spice Level", 1, 2, true);
        var mild = modifierService.addModifierOption(proteinGroup.id(), "Mild", 0L);
        var hot = modifierService.addModifierOption(proteinGroup.id(), "Extra Hot", 500L);

        // Omitting required modifier with "[]" must fail with 400 required_modifier_missing
        assertThatThrownBy(() -> orderService.createOrder(
                merchantId, null, null, null, null,
                List.of(new PosOrderService.OrderItemInput(
                        variant.id(), "Regular Tilapia", BigDecimal.ONE, 2000L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("required_modifier_missing");
                });

        // Omitting required modifier with null must also fail with 400 required_modifier_missing
        assertThatThrownBy(() -> orderService.createOrder(
                merchantId, null, null, null, null,
                List.of(new PosOrderService.OrderItemInput(
                        variant.id(), "Regular Tilapia", BigDecimal.ONE, 2000L, 0L, 0L, null, 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("required_modifier_missing");
                });

        // Selecting a valid required modifier option succeeds with authoritative price (2000 + 500)
        var okOrder = orderService.createOrder(
                merchantId, null, null, null, null,
                List.of(new PosOrderService.OrderItemInput(
                        variant.id(), "Regular Tilapia", BigDecimal.ONE, 2000L, 0L, 0L,
                        "[\"" + hot.id() + "\"]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN");
        assertThat(okOrder.totalPesewas()).isEqualTo(2500L);

        // Add a second group with minSelection=2, isRequired=false
        var sidesGroup = modifierService.createModifierGroup(merchantId, "Sides", 2, 3, false);
        var side1 = modifierService.addModifierOption(sidesGroup.id(), "Banku", 300L);
        var side2 = modifierService.addModifierOption(sidesGroup.id(), "Shito", 200L);

        // Selecting only 1 option from a minSelection=2 group must fail with 400 required_modifier_missing
        assertThatThrownBy(() -> orderService.createOrder(
                merchantId, null, null, null, null,
                List.of(new PosOrderService.OrderItemInput(
                        variant.id(), "Regular Tilapia", BigDecimal.ONE, 2000L, 0L, 0L,
                        "[\"" + mild.id() + "\",\"" + side1.id() + "\"]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("required_modifier_missing");
                });

        // Selecting both required options succeeds (2000 + 0 + 300 + 200 = 2500)
        var fullOrder = orderService.createOrder(
                merchantId, null, null, null, null,
                List.of(new PosOrderService.OrderItemInput(
                        variant.id(), "Regular Tilapia", BigDecimal.ONE, 2000L, 0L, 0L,
                        "[\"" + mild.id() + "\",\"" + side1.id() + "\",\"" + side2.id() + "\"]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN");
        assertThat(fullOrder.totalPesewas()).isEqualTo(2500L);
    }

    @Test
    @DisplayName("HIGH: DUKPT uses ephemeral BDK in sandbox and fails closed in production or with public test BDK")
    void dukptUsesEphemeralBdkInSandboxAndFailsClosedInProduction() {
        var sandboxEnv = new MockEnvironment()
                .withProperty("elepay.rails.sandbox.enabled", "true");
        var sandboxDukpt = new PosDukptKeyManagementService(db, kmsSecretProvider, "", sandboxEnv);
        var sandboxTerminal = terminalService.registerTerminal(
                merchantId, null, "SN-DUKPT-" + UUID.randomUUID().toString().substring(0, 8), "PAX A920");
        var profile = sandboxDukpt.provisionTerminal(sandboxTerminal.id(), merchantId, null);
        assertThat(profile.status()).isEqualTo("ACTIVE");
        assertThat(profile.initialKsn()).hasSize(20);

        // Production with blank BDK must fail closed with 422 dukpt_key_missing
        var prodEnv = new MockEnvironment()
                .withProperty("elepay.environment", "production")
                .withProperty("elepay.rails.sandbox.enabled", "false");
        var prodDukpt = new PosDukptKeyManagementService(db, kmsSecretProvider, "", prodEnv);
        assertThatThrownBy(() -> prodDukpt.provisionTerminal(UUID.randomUUID(), merchantId, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(api.code()).isEqualTo("dukpt_key_missing");
                });

        // Public ANSI test BDK must be rejected even in sandbox
        var publicKeyDukpt = new PosDukptKeyManagementService(
                db, kmsSecretProvider, "0123456789ABCDEFFEDCBA9876543210", sandboxEnv);
        assertThatThrownBy(() -> publicKeyDukpt.provisionTerminal(UUID.randomUUID(), merchantId, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(api.code()).isEqualTo("dukpt_key_missing");
                });
    }

    @Test
    @DisplayName("MED: PosController shifts and held carts enforce requireTerminal for pos_live terminal keys")
    void posTerminalScopedKeyCannotAccessAnotherTerminalsShiftsOrHeldCarts() {
        var regA = terminalService.registerTerminal(
                merchantId, null, "SN-SCOPA-" + UUID.randomUUID().toString().substring(0, 8), "PAX A920");
        var pairedA = terminalService.pairTerminal(regA.serialNumber(), regA.pairingCode(), "HW-FP-SCOPA");

        var regB = terminalService.registerTerminal(
                merchantId, null, "SN-SCOPB-" + UUID.randomUUID().toString().substring(0, 8), "PAX A920");
        var pairedB = terminalService.pairTerminal(regB.serialNumber(), regB.pairingCode(), "HW-FP-SCOPB");

        var cashier = cashierService.registerCashier(
                merchantId, "Ama Cashier", "1234", "BG-" + UUID.randomUUID().toString().substring(0, 6));

        // Authenticate as Terminal B and create a shift and a held cart on Terminal B
        authenticateAsPosTerminal(pairedB);
        PosCashierService.Shift shiftB = posController.openShift(new PosController.OpenShiftRequest(
                pairedB.terminalId(), cashier.id(), merchantId, 5000L, "Shift B"));
        assertThat(posController.getActiveShift(pairedB.terminalId()).getBody()).isNotNull();

        PosCartService.HeldCart cartB = posController.holdCart(new PosController.HoldCartRequest(
                pairedB.terminalId(), cashier.id(), merchantId, "Customer B", "[]", 1500L));
        assertThat(posController.listHeldCarts(pairedB.terminalId())).hasSize(1);

        // Now authenticate as Terminal A: own terminal operations work, Terminal B operations must return 404
        authenticateAsPosTerminal(pairedA);
        PosCashierService.Shift shiftA = posController.openShift(new PosController.OpenShiftRequest(
                pairedA.terminalId(), cashier.id(), merchantId, 3000L, "Shift A"));
        assertThat(shiftA.terminalId()).isEqualTo(pairedA.terminalId());
        assertThat(posController.getActiveShift(pairedA.terminalId()).getBody()).isNotNull();

        PosCartService.HeldCart cartA = posController.holdCart(new PosController.HoldCartRequest(
                pairedA.terminalId(), cashier.id(), merchantId, "Customer A", "[]", 1000L));
        assertThat(posController.listHeldCarts(pairedA.terminalId())).hasSize(1);
        assertThat(posController.recallHeldCart(cartA.id()).id()).isEqualTo(cartA.id());
        assertThat(posController.closeShift(shiftA.id(), new PosController.CloseShiftRequest(3000L, "Done")).status())
                .isEqualTo("CLOSED");

        // All 6 cross-terminal shift/cart calls from Terminal A targeting Terminal B must fail with 404
        assertNotFound(() -> posController.openShift(new PosController.OpenShiftRequest(
                pairedB.terminalId(), cashier.id(), merchantId, 1000L, "Hijack")));
        assertNotFound(() -> posController.closeShift(
                shiftB.id(), new PosController.CloseShiftRequest(5000L, "Hijack close")));
        assertNotFound(() -> posController.getActiveShift(pairedB.terminalId()));
        assertNotFound(() -> posController.holdCart(new PosController.HoldCartRequest(
                pairedB.terminalId(), cashier.id(), merchantId, "Hijack", "[]", 500L)));
        assertNotFound(() -> posController.listHeldCarts(pairedB.terminalId()));
        assertNotFound(() -> posController.recallHeldCart(cartB.id()));
    }

    private void authenticateAsPosTerminal(PosTerminalService.PairingResult paired) {
        var auth = new ApiKeyAuthentication(
                paired.credentialId(),
                merchantId,
                Set.of("collect", "view"),
                paired.signingSecret(),
                "pos_terminal",
                List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
