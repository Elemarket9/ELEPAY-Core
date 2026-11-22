package com.elepay.enterprise.wallet;

import com.elepay.common.HmacVerification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive verification of the Universal External Wallet Integration Engine:
 * - Mode A: Enterprise-owned wallet connector (JumiaPay)
 * - Mode B: White-label wallet connector
 * - Mode C: External settlement-only connector
 * - Partner request authentication & token lifecycle management
 * - OAuth2/OIDC token delegation
 * - HMAC webhook signature verification & replay protection
 * - Key rotation and idempotency
 */
class ExternalWalletConnectorTest {

    private EnterpriseTokenService tokenService;
    private HmacWebhookVerifier webhookVerifier;
    private JumiaPayContractAdapter jumiaPayAdapter;
    private WhiteLabelWalletConnector whiteLabelConnector;
    private ExternalSettlementConnector settlementConnector;
    private ExternalWalletEngine walletEngine;

    private static final String PARTNER_ID = "partner_jumiapay_gh";
    private static final String CLIENT_ID = "client_jumia_live";
    private static final String CLIENT_SECRET = "sec_jumia_super_secret_9988";
    private static final String SIGNING_KEY = "k_jumia_hmac_sha256_enterprise_signing_secret_key";

    @BeforeEach
    void setUp() {
        tokenService = new EnterpriseTokenService();
        webhookVerifier = new HmacWebhookVerifier();
        webhookVerifier.clearNonceCacheForTesting();

        jumiaPayAdapter = new JumiaPayContractAdapter(tokenService, webhookVerifier);
        whiteLabelConnector = new WhiteLabelWalletConnector(tokenService, webhookVerifier);
        settlementConnector = new ExternalSettlementConnector(tokenService, webhookVerifier);

        walletEngine = new ExternalWalletEngine(
                List.of(jumiaPayAdapter, whiteLabelConnector, settlementConnector),
                tokenService,
                webhookVerifier
        );

        // Register default JumiaPay partner credentials
        PartnerAuthCredentials creds = PartnerAuthCredentials.create(
                PARTNER_ID,
                "Jumia Ghana",
                CLIENT_ID,
                CLIENT_SECRET,
                SIGNING_KEY,
                Set.of(
                        PartnerScope.WALLET_READ,
                        PartnerScope.WALLET_AUTHORIZE,
                        PartnerScope.WALLET_CAPTURE,
                        PartnerScope.WALLET_CANCEL,
                        PartnerScope.WALLET_REFUND,
                        PartnerScope.WALLET_WITHDRAW,
                        PartnerScope.SETTLEMENT_SUBMIT,
                        PartnerScope.RECONCILIATION_SYNC
                )
        );
        tokenService.registerPartner(creds);
    }

    @Test
    @DisplayName("Mode A: JumiaPay enterprise-owned wallet full lifecycle (Auth, Split Capture, Refund, MoMo Withdrawal)")
    void testModeAEnterpriseOwnedWalletIntegration() {
        String customerId = "jumia_cust_1001";
        // Seed external balance in JumiaPay (GH₵ 500.00 = 50,000 pesewas)
        jumiaPayAdapter.seedCustomerBalance(customerId, 50_000L);

        // 1. Verify connector discovery and capabilities
        assertEquals(WalletOperatingMode.MODE_A_ENTERPRISE_OWNED, jumiaPayAdapter.getOperatingMode());
        assertTrue(jumiaPayAdapter.getCapabilities().supportsExternalBalances());
        assertTrue(jumiaPayAdapter.getCapabilities().supportsCryptoAuthorizations());
        assertTrue(jumiaPayAdapter.getCapabilities().supportsItemLevelRefund());
        assertTrue(jumiaPayAdapter.getCapabilities().supportsWithdrawals());

        // 2. Query initial balance
        ExternalBalanceQueryResult balance = jumiaPayAdapter.queryWalletBalance(
                new ExternalBalanceQueryRequest(PARTNER_ID, customerId, "GHS")
        );
        assertTrue(balance.success());
        assertEquals(50_000L, balance.availableBalanceMinorUnits());

        // 3. Cryptographically sign authorization payload from JumiaPay
        String txRef = "JPA-TX-20261122-001";
        long orderAmount = 30_000L; // GH₵ 300.00
        String canonicalAuth = customerId + ":" + orderAmount + ":GHS:" + txRef;
        String cryptoSignature = HmacVerification.computeHex(SIGNING_KEY, canonicalAuth);

        ExternalPaymentAuthRequest authRequest = new ExternalPaymentAuthRequest(
                "idem_auth_001",
                PARTNER_ID,
                WalletOperatingMode.MODE_A_ENTERPRISE_OWNED,
                txRef,
                customerId,
                orderAmount,
                "GHS",
                cryptoSignature,
                Map.of("platform", "Jumia Ghana Android")
        );

        ExternalPaymentAuthResult authResult = jumiaPayAdapter.authorizePayment(authRequest);
        assertTrue(authResult.success());
        assertNotNull(authResult.authorizationId());
        assertEquals(PaymentExecutionState.AUTHORIZED, authResult.state());

        // Verify external balance held in JumiaPay
        ExternalBalanceQueryResult balAfterAuth = jumiaPayAdapter.queryWalletBalance(
                new ExternalBalanceQueryRequest(PARTNER_ID, customerId, "GHS")
        );
        assertEquals(20_000L, balAfterAuth.availableBalanceMinorUnits());

        // 4. Capture payment with multi-merchant split allocations
        List<PaymentAllocationSplit> splits = List.of(
                new PaymentAllocationSplit("merch_tech_hub", 18_000L, 1_800L, 1_200L, "ITEM-LAPTOP-BAG", "Laptop Backpack"),
                new PaymentAllocationSplit("merch_elec_gh", 12_000L, 1_200L, 800L, "ITEM-MOUSE-BT", "Wireless Mouse")
        );

        ExternalPaymentCaptureRequest captureRequest = new ExternalPaymentCaptureRequest(
                "idem_cap_001",
                authResult.authorizationId(),
                txRef,
                orderAmount,
                "GHS",
                splits
        );

        ExternalPaymentCaptureResult captureResult = jumiaPayAdapter.capturePayment(captureRequest);
        assertTrue(captureResult.success());
        assertEquals(PaymentExecutionState.CAPTURED, captureResult.state());
        assertNotNull(captureResult.captureId());

        // 5. Item-level partial refund back to JumiaPay wallet (customer returned the mouse)
        long refundAmount = 12_000L;
        List<RefundAllocationItem> refundItems = List.of(
                new RefundAllocationItem("merch_elec_gh", "ITEM-MOUSE-BT", 12_000L, 1_200L, "Defective scroll wheel")
        );

        ExternalWalletRefundRequest refundRequest = new ExternalWalletRefundRequest(
                "idem_ref_001",
                PARTNER_ID,
                captureResult.captureId(),
                txRef,
                customerId,
                refundAmount,
                "GHS",
                "Item return",
                refundItems
        );

        ExternalWalletRefundResult refundResult = jumiaPayAdapter.refundToWallet(refundRequest);
        assertTrue(refundResult.success());
        assertEquals(PaymentExecutionState.PARTIALLY_REFUNDED, refundResult.state());

        // Verify customer wallet balance in JumiaPay is credited back: 20,000 + 12,000 = 32,000 pesewas
        ExternalBalanceQueryResult balAfterRefund = jumiaPayAdapter.queryWalletBalance(
                new ExternalBalanceQueryRequest(PARTNER_ID, customerId, "GHS")
        );
        assertEquals(32_000L, balAfterRefund.availableBalanceMinorUnits());

        // 6. Customer withdraws refund balance from JumiaPay wallet to MTN Mobile Money
        long withdrawalAmount = 15_000L; // GH₵ 150.00 (within Tier 1 limit of GH₵ 1,000)
        ExternalWalletWithdrawalRequest withdrawRequest = new ExternalWalletWithdrawalRequest(
                "idem_wdr_001",
                PARTNER_ID,
                customerId,
                withdrawalAmount,
                "GHS",
                "MTN_MOMO",
                "+233244123456",
                "TIER_1"
        );

        ExternalWalletWithdrawalResult withdrawResult = jumiaPayAdapter.requestWithdrawal(withdrawRequest);
        assertTrue(withdrawResult.success());
        assertNotNull(withdrawResult.withdrawalId());
        assertEquals("SUBMITTED", withdrawResult.state());

        // Customer wallet balance after withdrawal: 32,000 - 15,000 = 17,000 pesewas
        ExternalBalanceQueryResult balAfterWithdrawal = jumiaPayAdapter.queryWalletBalance(
                new ExternalBalanceQueryRequest(PARTNER_ID, customerId, "GHS")
        );
        assertEquals(17_000L, balAfterWithdrawal.availableBalanceMinorUnits());

        // 7. Verify BoG KYC Tier limit enforcement on withdrawal
        ExternalWalletWithdrawalRequest overLimitWithdrawal = new ExternalWalletWithdrawalRequest(
                "idem_wdr_over",
                PARTNER_ID,
                customerId,
                150_000L, // GH₵ 1,500.00 > Tier 1 limit of GH₵ 1,000.00
                "GHS",
                "MTN_MOMO",
                "+233244123456",
                "TIER_1"
        );

        EnterpriseWalletException limitEx = assertThrows(EnterpriseWalletException.class, () ->
                jumiaPayAdapter.requestWithdrawal(overLimitWithdrawal)
        );
        assertEquals(EnterpriseWalletException.ErrorCode.LIMIT_EXCEEDED, limitEx.getErrorCode());
    }

    @Test
    @DisplayName("Mode B: White-label wallet connector (ELEPAY authoritative ledger, reservations, cancellation)")
    void testModeBWhiteLabelWalletIntegration() {
        String customerId = "wl_cust_5500";
        // Credit internal white-label customer ledger: GH₵ 800.00 = 80,000 pesewas
        whiteLabelConnector.creditCustomerWallet(customerId, 80_000L, "GHS");

        assertEquals(WalletOperatingMode.MODE_B_WHITE_LABEL, whiteLabelConnector.getOperatingMode());
        assertTrue(whiteLabelConnector.getCapabilities().supportsInternalLedger());

        // 1. Initial balance check
        ExternalBalanceQueryResult initialBal = whiteLabelConnector.queryWalletBalance(
                new ExternalBalanceQueryRequest(PARTNER_ID, customerId, "GHS")
        );
        assertEquals(80_000L, initialBal.availableBalanceMinorUnits());
        assertEquals(0L, initialBal.reservedBalanceMinorUnits());

        // 2. Authorize 30,000 pesewas
        ExternalPaymentAuthRequest authReq = new ExternalPaymentAuthRequest(
                "idem_wl_auth_1",
                PARTNER_ID,
                WalletOperatingMode.MODE_B_WHITE_LABEL,
                "WL-REF-001",
                customerId,
                30_000L,
                "GHS",
                null,
                Map.of()
        );

        ExternalPaymentAuthResult authResult = whiteLabelConnector.authorizePayment(authReq);
        assertTrue(authResult.success());
        assertEquals(PaymentExecutionState.AUTHORIZED, authResult.state());

        // Balance during authorization: available=50,000, reserved=30,000
        ExternalBalanceQueryResult balDuringHold = whiteLabelConnector.queryWalletBalance(
                new ExternalBalanceQueryRequest(PARTNER_ID, customerId, "GHS")
        );
        assertEquals(50_000L, balDuringHold.availableBalanceMinorUnits());
        assertEquals(30_000L, balDuringHold.reservedBalanceMinorUnits());

        // 3. Attempt authorization exceeding remaining available funds (60,000 > 50,000)
        ExternalPaymentAuthRequest excessiveAuth = new ExternalPaymentAuthRequest(
                "idem_wl_auth_excess",
                PARTNER_ID,
                WalletOperatingMode.MODE_B_WHITE_LABEL,
                "WL-REF-002",
                customerId,
                60_000L,
                "GHS",
                null,
                Map.of()
        );
        EnterpriseWalletException insEx = assertThrows(EnterpriseWalletException.class, () ->
                whiteLabelConnector.authorizePayment(excessiveAuth)
        );
        assertEquals(EnterpriseWalletException.ErrorCode.INSUFFICIENT_FUNDS, insEx.getErrorCode());

        // 4. Cancel authorization -> reserved funds restored to available
        ExternalPaymentCancelResult cancelResult = whiteLabelConnector.cancelPayment(
                new ExternalPaymentCancelRequest("idem_cancel_1", authResult.authorizationId(), "Customer abandoned cart")
        );
        assertTrue(cancelResult.success());
        assertEquals(PaymentExecutionState.CANCELLED, cancelResult.state());

        ExternalBalanceQueryResult balAfterCancel = whiteLabelConnector.queryWalletBalance(
                new ExternalBalanceQueryRequest(PARTNER_ID, customerId, "GHS")
        );
        assertEquals(80_000L, balAfterCancel.availableBalanceMinorUnits());
        assertEquals(0L, balAfterCancel.reservedBalanceMinorUnits());
    }

    @Test
    @DisplayName("Mode C: External settlement-only connector (escrow allocation and multi-merchant splitting)")
    void testModeCSettlementOnlyIntegration() {
        assertEquals(WalletOperatingMode.MODE_C_SETTLEMENT_ONLY, settlementConnector.getOperatingMode());
        assertTrue(settlementConnector.getCapabilities().supportsMultiMerchantSplit());
        assertFalse(settlementConnector.getCapabilities().supportsExternalBalances());

        long orderTotal = 152_000L; // GH₵ 1,520.00 across 3 merchants
        ExternalPaymentAuthRequest authReq = new ExternalPaymentAuthRequest(
                "idem_mode_c_001",
                PARTNER_ID,
                WalletOperatingMode.MODE_C_SETTLEMENT_ONLY,
                "EXT-SETTLE-REF-100",
                "ext_cust_999",
                orderTotal,
                "GHS",
                null,
                Map.of("externalGateway", "StandardChartered_HostToHost")
        );

        ExternalPaymentAuthResult authResult = settlementConnector.authorizePayment(authReq);
        assertTrue(authResult.success());

        // Split across 3 merchants:
        // Merchant 1: 80,000 (commission 8,000, delivery 2,000 -> net 70,000)
        // Merchant 2: 50,000 (commission 5,000, delivery 1,000 -> net 44,000)
        // Merchant 3: 22,000 (commission 2,200, delivery 800 -> net 19,000)
        // Total = 152,000 (exact conservation)
        List<PaymentAllocationSplit> allocations = List.of(
                new PaymentAllocationSplit("merch_accra_1", 80_000L, 8_000L, 2_000L, "SKU-01", "Phones"),
                new PaymentAllocationSplit("merch_kumasi_2", 50_000L, 5_000L, 1_000L, "SKU-02", "Accessories"),
                new PaymentAllocationSplit("merch_takoradi_3", 22_000L, 2_200L, 800L, "SKU-03", "Cases")
        );

        ExternalPaymentCaptureResult captureResult = settlementConnector.capturePayment(
                new ExternalPaymentCaptureRequest(
                        "idem_mode_c_cap_001",
                        authResult.authorizationId(),
                        "EXT-SETTLE-REF-100",
                        orderTotal,
                        "GHS",
                        allocations
                )
        );

        assertTrue(captureResult.success());
        assertEquals(70_000L, settlementConnector.getMerchantAccruedBalance("merch_accra_1"));
        assertEquals(44_000L, settlementConnector.getMerchantAccruedBalance("merch_kumasi_2"));
        assertEquals(19_000L, settlementConnector.getMerchantAccruedBalance("merch_takoradi_3"));

        // Verify unsupported operations fail cleanly with UNSUPPORTED_OPERATION
        assertThrows(EnterpriseWalletException.class, () ->
                settlementConnector.queryWalletBalance(new ExternalBalanceQueryRequest(PARTNER_ID, "cust", "GHS"))
        );
        assertThrows(EnterpriseWalletException.class, () ->
                settlementConnector.requestWithdrawal(new ExternalWalletWithdrawalRequest("id", PARTNER_ID, "cust", 100L, "GHS", "MOMO", "+233", "TIER_1"))
        );
    }

    @Test
    @DisplayName("Secure partner request authentication via OAuth2 Client Credentials")
    void testPartnerAuthenticationLifecycle() {
        // 1. Success case
        PartnerAuthRequest validReq = new PartnerAuthRequest(CLIENT_ID, CLIENT_SECRET, Set.of(PartnerScope.WALLET_READ, PartnerScope.WALLET_AUTHORIZE));
        PartnerAuthResult authResult = tokenService.authenticateClientCredentials(validReq);
        assertTrue(authResult.authenticated());
        assertNotNull(authResult.accessToken());
        assertEquals("Bearer", authResult.tokenType());
        assertTrue(authResult.grantedScopes().contains(PartnerScope.WALLET_AUTHORIZE));

        // 2. Invalid secret rejection
        PartnerAuthRequest badSecretReq = new PartnerAuthRequest(CLIENT_ID, "wrong_secret_123", Set.of());
        PartnerAuthResult badSecretResult = tokenService.authenticateClientCredentials(badSecretReq);
        assertFalse(badSecretResult.authenticated());

        // 3. Unknown client ID rejection
        PartnerAuthRequest unknownClientReq = new PartnerAuthRequest("non_existent_client", "pwd", Set.of());
        PartnerAuthResult unknownResult = tokenService.authenticateClientCredentials(unknownClientReq);
        assertFalse(unknownResult.authenticated());
    }

    @Test
    @DisplayName("OAuth2/OIDC token delegation lifecycle, scope validation, and limit enforcement")
    void testOAuth2OidcTokenDelegation() {
        long authorizedLimit = 45_000L; // GH₵ 450.00
        DelegatedTokenRequest delReq = new DelegatedTokenRequest(
                PARTNER_ID,
                "elepay_user_777",
                "jumia_user_888",
                authorizedLimit,
                "GHS",
                Set.of(PartnerScope.WALLET_READ, PartnerScope.WALLET_AUTHORIZE, PartnerScope.WALLET_CAPTURE),
                1800L
        );

        // 1. Issue delegated token
        DelegatedToken token = tokenService.issueDelegatedToken(delReq);
        assertNotNull(token);
        assertNotNull(token.tokenId());
        assertNotNull(token.cryptographicSignature());

        // 2. Validate token for an authorized operation under the limit
        DelegatedToken validated = tokenService.validateDelegatedToken(
                token.tokenId(),
                PartnerScope.WALLET_AUTHORIZE,
                30_000L,
                "GHS"
        );
        assertEquals(token.tokenId(), validated.tokenId());

        // 3. Validation failure: exceeds authorized limit
        EnterpriseWalletException limitEx = assertThrows(EnterpriseWalletException.class, () ->
                tokenService.validateDelegatedToken(token.tokenId(), PartnerScope.WALLET_AUTHORIZE, 50_000L, "GHS")
        );
        assertEquals(EnterpriseWalletException.ErrorCode.LIMIT_EXCEEDED, limitEx.getErrorCode());

        // 4. Validation failure: missing scope
        EnterpriseWalletException scopeEx = assertThrows(EnterpriseWalletException.class, () ->
                tokenService.validateDelegatedToken(token.tokenId(), PartnerScope.SETTLEMENT_SUBMIT, 10_000L, "GHS")
        );
        assertEquals(EnterpriseWalletException.ErrorCode.INSUFFICIENT_SCOPE, scopeEx.getErrorCode());

        // 5. Token revocation
        tokenService.revokeToken(token.tokenId());
        EnterpriseWalletException revEx = assertThrows(EnterpriseWalletException.class, () ->
                tokenService.validateDelegatedToken(token.tokenId(), PartnerScope.WALLET_READ, 0L, "GHS")
        );
        assertEquals(EnterpriseWalletException.ErrorCode.TOKEN_REVOKED, revEx.getErrorCode());
    }

    @Test
    @DisplayName("HMAC Webhook signature verification, replay protection, and zero-downtime key rotation")
    void testHmacWebhookSignatureVerification() {
        String payload = "{\"event\":\"payment.authorized\",\"orderId\":\"ORD-8899\",\"amount\":25000}";
        long timestamp = Instant.now().getEpochSecond();
        String nonce = UUID.randomUUID().toString();

        String signature = HmacWebhookVerifier.computeSignature(SIGNING_KEY, timestamp, nonce, payload);

        // 1. Valid signature
        WebhookSignatureVerificationRequest req = new WebhookSignatureVerificationRequest(
                PARTNER_ID,
                payload,
                signature,
                String.valueOf(timestamp),
                nonce,
                "1"
        );

        WebhookSignatureVerificationResult res = jumiaPayAdapter.verifyWebhook(req);
        assertTrue(res.valid());

        // 2. Replay attack rejection (identical nonce)
        WebhookSignatureVerificationResult replayRes = jumiaPayAdapter.verifyWebhook(req);
        assertFalse(replayRes.valid());
        assertTrue(replayRes.failureReason().contains("Replay attack detected"));

        // 3. Corrupted payload rejection
        String tamperedPayload = "{\"event\":\"payment.authorized\",\"orderId\":\"ORD-8899\",\"amount\":999999}";
        String newNonce = UUID.randomUUID().toString();
        String tamperedSig = HmacWebhookVerifier.computeSignature(SIGNING_KEY, timestamp, newNonce, payload);
        WebhookSignatureVerificationRequest tamperedReq = new WebhookSignatureVerificationRequest(
                PARTNER_ID,
                tamperedPayload,
                tamperedSig,
                String.valueOf(timestamp),
                newNonce,
                "1"
        );
        WebhookSignatureVerificationResult tamperedRes = jumiaPayAdapter.verifyWebhook(tamperedReq);
        assertFalse(tamperedRes.valid());
        assertEquals("HMAC signature mismatch", tamperedRes.failureReason());

        // 4. Expired timestamp rejection
        long oldTimestamp = timestamp - 600L; // 10 minutes ago
        String expiredNonce = UUID.randomUUID().toString();
        String expiredSig = HmacWebhookVerifier.computeSignature(SIGNING_KEY, oldTimestamp, expiredNonce, payload);
        WebhookSignatureVerificationRequest expiredReq = new WebhookSignatureVerificationRequest(
                PARTNER_ID,
                payload,
                expiredSig,
                String.valueOf(oldTimestamp),
                expiredNonce,
                "1"
        );
        WebhookSignatureVerificationResult expiredRes = jumiaPayAdapter.verifyWebhook(expiredReq);
        assertFalse(expiredRes.valid());
        assertTrue(expiredRes.failureReason().contains("Timestamp expired"));

        // 5. Zero-downtime key rotation: webhook signed with previous key is accepted during rollover
        String newKey = "k_new_rotated_signing_key_99999";
        tokenService.rotatePartnerKey(PARTNER_ID, newKey);

        String rotatedNonce = UUID.randomUUID().toString();
        // Signed with previous key
        String prevKeySig = HmacWebhookVerifier.computeSignature(SIGNING_KEY, timestamp, rotatedNonce, payload);
        WebhookSignatureVerificationRequest prevKeyReq = new WebhookSignatureVerificationRequest(
                PARTNER_ID,
                payload,
                prevKeySig,
                String.valueOf(timestamp),
                rotatedNonce,
                "1"
        );
        WebhookSignatureVerificationResult prevKeyRes = jumiaPayAdapter.verifyWebhook(prevKeyReq);
        assertTrue(prevKeyRes.valid(), "Signature signed with previous key during rollover must be valid");
    }

    @Test
    @DisplayName("Idempotency protection: duplicate authorization returns cached result, altered payload returns 409")
    void testIdempotencyProtection() {
        String customerId = "idem_cust_001";
        jumiaPayAdapter.seedCustomerBalance(customerId, 100_000L);

        String txRef = "IDEM-REF-101";
        long amount = 20_000L;
        String canonical = customerId + ":" + amount + ":GHS:" + txRef;
        String signature = HmacVerification.computeHex(SIGNING_KEY, canonical);

        ExternalPaymentAuthRequest originalReq = new ExternalPaymentAuthRequest(
                "idem_unique_key_999",
                PARTNER_ID,
                WalletOperatingMode.MODE_A_ENTERPRISE_OWNED,
                txRef,
                customerId,
                amount,
                "GHS",
                signature,
                Map.of()
        );

        ExternalPaymentAuthResult res1 = jumiaPayAdapter.authorizePayment(originalReq);
        assertTrue(res1.success());

        // Repeated request with exact same payload
        ExternalPaymentAuthResult res2 = jumiaPayAdapter.authorizePayment(originalReq);
        assertTrue(res2.success());
        assertEquals(res1.authorizationId(), res2.authorizationId());

        // Altered payload with same idempotency key
        ExternalPaymentAuthRequest alteredReq = new ExternalPaymentAuthRequest(
                "idem_unique_key_999",
                PARTNER_ID,
                WalletOperatingMode.MODE_A_ENTERPRISE_OWNED,
                "DIFFERENT-REF",
                customerId,
                amount,
                "GHS",
                signature,
                Map.of()
        );

        EnterpriseWalletException conflictEx = assertThrows(EnterpriseWalletException.class, () ->
                jumiaPayAdapter.authorizePayment(alteredReq)
        );
        assertEquals(EnterpriseWalletException.ErrorCode.IDEMPOTENCY_CONFLICT, conflictEx.getErrorCode());
        assertEquals(409, conflictEx.getHttpStatus());
    }

    @Test
    @DisplayName("Ledger reconciliation sync between external partner and internal records")
    void testLedgerReconciliationSync() {
        String customerId = "recon_cust_001";
        jumiaPayAdapter.seedCustomerBalance(customerId, 50_000L);

        String txRef = "RECON-TX-001";
        String canonical = customerId + ":10000:GHS:" + txRef;
        String sig = HmacVerification.computeHex(SIGNING_KEY, canonical);

        ExternalPaymentAuthResult auth = jumiaPayAdapter.authorizePayment(new ExternalPaymentAuthRequest(
                "idem_recon_1", PARTNER_ID, WalletOperatingMode.MODE_A_ENTERPRISE_OWNED, txRef, customerId, 10_000L, "GHS", sig, Map.of()
        ));

        ExternalPaymentCaptureResult cap = jumiaPayAdapter.capturePayment(new ExternalPaymentCaptureRequest(
                "idem_recon_cap_1", auth.authorizationId(), txRef, 10_000L, "GHS",
                List.of(new PaymentAllocationSplit("merch_1", 10_000L, 1_000L, 500L, "SKU-R", "Item"))
        ));

        // Submit external ledger lines for reconciliation: 1 matched, 1 mismatch
        List<ExternalLedgerRecord> records = List.of(
                new ExternalLedgerRecord("EXT-LINE-1", cap.captureId(), "CAPTURE", 10_000L, "GHS", "SUCCESS", Instant.now()),
                new ExternalLedgerRecord("EXT-LINE-2", "non_existent_ref", "CAPTURE", 5_000L, "GHS", "SUCCESS", Instant.now())
        );

        ExternalReconciliationSyncResult syncResult = jumiaPayAdapter.syncLedgerReconciliation(
                new ExternalReconciliationSyncRequest(PARTNER_ID, LocalDate.now(), "GHS", records)
        );

        assertEquals(2, syncResult.totalRecords());
        assertEquals(1, syncResult.matchedCount());
        assertEquals(1, syncResult.discrepancyCount());
        assertEquals(10_000L, syncResult.matchedAmountMinorUnits());
        assertEquals(5_000L, syncResult.discrepancyAmountMinorUnits());
    }

    @Test
    @DisplayName("ExternalWalletEngine coordinator routes correctly across all modes")
    void testWalletEngineCoordination() {
        assertEquals(3, walletEngine.getAllConnectors().size());
        assertEquals(1, walletEngine.getConnectorsByMode(WalletOperatingMode.MODE_A_ENTERPRISE_OWNED).size());
        assertEquals(1, walletEngine.getConnectorsByMode(WalletOperatingMode.MODE_B_WHITE_LABEL).size());
        assertEquals(1, walletEngine.getConnectorsByMode(WalletOperatingMode.MODE_C_SETTLEMENT_ONLY).size());

        assertTrue(walletEngine.getConnector("jumiapay-gh-v1").isPresent());
        assertTrue(walletEngine.getConnector("elepay-whitelabel-v1").isPresent());
        assertTrue(walletEngine.getConnector("elepay-settlement-only-v1").isPresent());
        assertFalse(walletEngine.getConnector("non_existent").isPresent());
    }
}
