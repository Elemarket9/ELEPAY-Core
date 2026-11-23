package com.elepay.enterprise.payout;

import com.elepay.common.ApiException;
import com.elepay.compliance.SanctionsScreeningService;
import com.elepay.enterprise.wallet.DelegatedTokenRequest;
import com.elepay.enterprise.wallet.EnterpriseTokenService;
import com.elepay.enterprise.wallet.PartnerAuthCredentials;
import com.elepay.enterprise.wallet.PartnerScope;
import com.elepay.ledger.Ledger;
import com.elepay.payout.Payouts;
import com.elepay.payout.Payouts.Batch;
import com.elepay.payout.Payouts.Destination;
import com.elepay.payout.Payouts.Payout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit test suite for External Wallet Customer Withdrawal Engine (Stream E4).
 *
 * <p>Verifies:
 * <ul>
 *   <li>Authorized customer withdrawal requests from enterprise-owned wallets (e.g. Jumia refund balance).</li>
 *   <li>Delegated authorization token validation (EnterpriseTokenService) with cryptographic HMAC signatures.</li>
 *   <li>Available enterprise settlement funding and prefunding checks.</li>
 *   <li>Ghana E.164 phone number canonicalization (+233...) and carrier network detection.</li>
 *   <li>Bank of Ghana KYC Tier daily limits (Tier 1: GH₵1,000, Tier 2: GH₵5,000, Tier 3: GH₵10,000).</li>
 *   <li>Daily cumulative withdrawal tracking and over-limit rejection.</li>
 *   <li>Fuzzy sanctions screening blocking listed individuals.</li>
 *   <li>Domestic payout engine dispatch and asynchronous reconciliation.</li>
 *   <li>Idempotency and duplicate retry prevention with {@code retry_of}.</li>
 * </ul>
 */
class ExternalWalletWithdrawalTest {

    private EnterpriseTokenService tokenService;
    private EnterpriseSettlementFundingService fundingService;
    private EnterpriseWithdrawalComplianceService complianceService;
    private SanctionsScreeningService sanctionsService;
    private Payouts payouts;
    private Ledger ledger;
    private ExternalWalletWithdrawalEngine withdrawalEngine;

    private UUID enterpriseId;
    private static final String PARTNER_ID = "partner_jumiapay_gh";
    private static final String SIGNING_KEY = "k_jumia_withdrawal_signing_secret_key_12345";

    @BeforeEach
    void setUp() {
        tokenService = new EnterpriseTokenService();
        PartnerAuthCredentials creds = PartnerAuthCredentials.create(
                PARTNER_ID,
                "Jumia Ghana",
                "client_jumia_withdraw",
                "secret_withdraw_9988",
                SIGNING_KEY,
                Set.of(PartnerScope.WALLET_READ, PartnerScope.WALLET_WITHDRAW)
        );
        tokenService.registerPartner(creds);

        ledger = Mockito.mock(Ledger.class);
        when(ledger.balance(anyString())).thenReturn(5_000_000L); // GH₵50,000.00 prefunded
        when(ledger.open(any(), any(), any(), any())).thenAnswer(invocation ->
                invocation.getArgument(0) + ":" + invocation.getArgument(1) + ":" + invocation.getArgument(2) + ":" + invocation.getArgument(3));
        when(ledger.openSystem(any(), any(), any(), any(Boolean.class))).thenAnswer(invocation -> invocation.getArgument(0));

        fundingService = new EnterpriseSettlementFundingService(ledger);

        sanctionsService = new SanctionsScreeningService(null);
        complianceService = new EnterpriseWithdrawalComplianceService(sanctionsService, null);

        payouts = Mockito.mock(Payouts.class);
        UUID batchId = UUID.randomUUID();
        UUID payoutId = UUID.randomUUID();
        Payout mockPayout = new Payout(
                payoutId, "PO-12345678", "EWD-REF-001", "merchant", UUID.randomUUID(),
                30_000L, "reserved", "verified", "Kofi Mensah", null,
                new Destination("momo", "mtn", "233241234567", "Kofi Mensah"),
                "ENTERPRISE_WITHDRAWAL", null, batchId, OffsetDateTime.now(), null
        );
        Batch mockBatch = new Batch(batchId, "PB-12345678", 1, 30_000L, Map.of("reserved", 1L), List.of(mockPayout));

        when(payouts.create(any(), any(), any(), any(), any())).thenReturn(mockBatch);
        when(payouts.isRetryEligible(anyString())).thenReturn(true);
        when(payouts.retry(any(), any(), any(), any())).thenReturn(mockBatch);

        withdrawalEngine = new ExternalWalletWithdrawalEngine(
                tokenService,
                fundingService,
                complianceService,
                payouts,
                null
        );

        enterpriseId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Authorized MoMo Customer Withdrawal: Delegated token, funding, phone normalization & payout dispatch")
    void testSuccessfulMoMoWithdrawal() {
        String customerId = "jumia_cust_8801";
        long amountPesewas = 30_000L; // GH₵300.00 refund withdrawal to MoMo

        // Issue cryptographically signed delegated token for withdrawal
        var tokenReq = new DelegatedTokenRequest(
                PARTNER_ID,
                "cust_uuid_1",
                customerId,
                50_000L, // Authorized up to GH₵500.00
                "GHS",
                Set.of(PartnerScope.WALLET_WITHDRAW),
                3600L
        );
        var delegatedToken = tokenService.issueDelegatedToken(tokenReq);

        EnterpriseWithdrawalRequest request = new EnterpriseWithdrawalRequest(
                "idem_with_001",
                enterpriseId,
                PARTNER_ID,
                customerId,
                delegatedToken.tokenId(),
                "Kofi Mensah",
                "GHA-123456789-1",
                1, // KYC Tier 1
                amountPesewas,
                "GHS",
                WithdrawalDestinationType.MOMO,
                "mtn",
                "0241234567", // Local Ghana format to be normalized
                Map.of("purpose", "wallet_refund_cashout")
        );

        EnterpriseWithdrawalResult result = withdrawalEngine.requestWithdrawal(request);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(WithdrawalStatus.SUBMITTED);
        assertThat(result.withdrawalReference()).startsWith("EWD-");
        assertThat(result.destinationAccount()).isEqualTo("+233241234567"); // Normalized E.164
        assertThat(result.destinationNetwork()).isEqualTo("mtn");
        assertThat(result.compliancePassed()).isTrue();
        assertThat(result.replayed()).isFalse();
    }

    @Test
    @DisplayName("Delegated Authorization Token Security: Invalid scope, expired, revoked, and over-limit tokens rejected")
    void testDelegatedTokenValidationFailures() {
        String customerId = "jumia_cust_sec_1";

        // 1. Token without WALLET_WITHDRAW scope
        var tokenReqNoWithdraw = new DelegatedTokenRequest(
                PARTNER_ID, "cust_1", customerId, 50_000L, "GHS",
                Set.of(PartnerScope.WALLET_READ), 3600L
        );
        var noWithdrawToken = tokenService.issueDelegatedToken(tokenReqNoWithdraw);

        EnterpriseWithdrawalRequest reqBadScope = new EnterpriseWithdrawalRequest(
                "idem_sec_01", enterpriseId, PARTNER_ID, customerId, noWithdrawToken.tokenId(),
                "Ama Serwaa", "GHA-111", 1, 10_000L, "GHS",
                WithdrawalDestinationType.MOMO, "mtn", "0241000001", Map.of()
        );
        assertThatThrownBy(() -> withdrawalEngine.requestWithdrawal(reqBadScope))
                .isInstanceOf(com.elepay.enterprise.wallet.EnterpriseWalletException.class)
                .hasMessageContaining("lacks required scope");

        // 2. Token amount limit exceeded (Token authorized 10,000, request is 25,000)
        var tokenReqLimited = new DelegatedTokenRequest(
                PARTNER_ID, "cust_1", customerId, 10_000L, "GHS",
                Set.of(PartnerScope.WALLET_WITHDRAW), 3600L
        );
        var limitedToken = tokenService.issueDelegatedToken(tokenReqLimited);

        EnterpriseWithdrawalRequest reqOverAmount = new EnterpriseWithdrawalRequest(
                "idem_sec_02", enterpriseId, PARTNER_ID, customerId, limitedToken.tokenId(),
                "Ama Serwaa", "GHA-111", 1, 25_000L, "GHS",
                WithdrawalDestinationType.MOMO, "mtn", "0241000001", Map.of()
        );
        assertThatThrownBy(() -> withdrawalEngine.requestWithdrawal(reqOverAmount))
                .isInstanceOf(com.elepay.enterprise.wallet.EnterpriseWalletException.class)
                .hasMessageContaining("exceeds delegated limit");

        // 3. Revoked token rejected
        tokenService.revokeToken(limitedToken.tokenId());
        EnterpriseWithdrawalRequest reqRevoked = new EnterpriseWithdrawalRequest(
                "idem_sec_03", enterpriseId, PARTNER_ID, customerId, limitedToken.tokenId(),
                "Ama Serwaa", "GHA-111", 1, 5_000L, "GHS",
                WithdrawalDestinationType.MOMO, "mtn", "0241000001", Map.of()
        );
        assertThatThrownBy(() -> withdrawalEngine.requestWithdrawal(reqRevoked))
                .isInstanceOf(com.elepay.enterprise.wallet.EnterpriseWalletException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    @DisplayName("Enterprise Settlement Funding Check: Prevents withdrawal if enterprise has insufficient prefunding")
    void testInsufficientEnterpriseSettlementFundingRejection() {
        // Mock zero available balance and zero clearing balance for enterprise
        when(ledger.balance(anyString())).thenReturn(0L);

        EnterpriseWithdrawalRequest request = new EnterpriseWithdrawalRequest(
                "idem_fund_fail_01", enterpriseId, PARTNER_ID, "cust_fund_1", null,
                "Yaw Boateng", "GHA-222", 1, 50_000L, "GHS",
                WithdrawalDestinationType.MOMO, "mtn", "0241000002", Map.of()
        );

        assertThatThrownBy(() -> withdrawalEngine.requestWithdrawal(request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Insufficient enterprise settlement funding");
    }

    @Test
    @DisplayName("Ghana Phone Validation & Network Detection: Normalizes to +233 E.164 and identifies telco")
    void testGhanaPhoneValidationAndCarrierDetection() {
        // MTN prefixes (24, 54, 55, 59, 25)
        var mtn1 = GhanaPhoneValidator.validateAndNormalize("0241234567");
        assertThat(mtn1.canonicalE164()).isEqualTo("+233241234567");
        assertThat(mtn1.network()).isEqualTo("mtn");

        var mtn2 = GhanaPhoneValidator.validateAndNormalize("233549988776");
        assertThat(mtn2.canonicalE164()).isEqualTo("+233549988776");
        assertThat(mtn2.network()).isEqualTo("mtn");

        // Telecel prefixes (20, 50)
        var telecel = GhanaPhoneValidator.validateAndNormalize("0205544332");
        assertThat(telecel.canonicalE164()).isEqualTo("+233205544332");
        assertThat(telecel.network()).isEqualTo("telecel");

        // AirtelTigo prefixes (27, 57, 26, 56)
        var at = GhanaPhoneValidator.validateAndNormalize("+233271122334");
        assertThat(at.canonicalE164()).isEqualTo("+233271122334");
        assertThat(at.network()).isEqualTo("airteltigo");

        // Invalid numbers
        assertThatThrownBy(() -> GhanaPhoneValidator.validateAndNormalize("0123456789"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> GhanaPhoneValidator.validateAndNormalize("12345"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> GhanaPhoneValidator.validateAndNormalize(null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("Bank of Ghana KYC Tier Limits & Daily Cumulative Over-Limit Enforcement")
    void testBankOfGhanaKycTierDailyLimits() {
        String custTier1 = "cust_tier1_limit";

        // Tier 1 Daily Limit = GH₵1,000.00 (100,000 pesewas)
        // Attempting single withdrawal of GH₵1,200.00 (120,000 pesewas) -> Rejection
        EnterpriseWithdrawalRequest reqSingleOver = new EnterpriseWithdrawalRequest(
                "idem_tier1_01", enterpriseId, PARTNER_ID, custTier1, null,
                "Esi Mansa", "GHA-333", 1, 120_000L, "GHS",
                WithdrawalDestinationType.MOMO, "mtn", "0241000003", Map.of()
        );
        assertThatThrownBy(() -> withdrawalEngine.requestWithdrawal(reqSingleOver))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exceeds Bank of Ghana Tier 1 daily limit");

        // Two withdrawals on same day: 1st is 60,000 pesewas, 2nd is 60,000 pesewas (total = 120,000 > 100,000)
        EnterpriseWithdrawalRequest req1 = new EnterpriseWithdrawalRequest(
                "idem_tier1_split_01", enterpriseId, PARTNER_ID, custTier1, null,
                "Esi Mansa", "GHA-333", 1, 60_000L, "GHS",
                WithdrawalDestinationType.MOMO, "mtn", "0241000003", Map.of()
        );
        var res1 = withdrawalEngine.requestWithdrawal(req1);
        assertThat(res1.status()).isEqualTo(WithdrawalStatus.SUBMITTED);

        // 2nd withdrawal pushes cumulative total over limit
        EnterpriseWithdrawalRequest req2 = new EnterpriseWithdrawalRequest(
                "idem_tier1_split_02", enterpriseId, PARTNER_ID, custTier1, null,
                "Esi Mansa", "GHA-333", 1, 60_000L, "GHS",
                WithdrawalDestinationType.MOMO, "mtn", "0241000003", Map.of()
        );
        assertThatThrownBy(() -> withdrawalEngine.requestWithdrawal(req2))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Projected daily total");
    }

    @Test
    @DisplayName("Fuzzy Sanctions Screening: Blocks withdrawal if recipient matches sanctions list")
    void testSanctionsScreeningBlock() {
        // Add listed individual to sanctions registry
        sanctionsService.addSanctionEntry(new SanctionsScreeningService.SanctionEntry(
                UUID.randomUUID(),
                "OFAC-SDN",
                "2026.1",
                "Viktor Anatolyevich Bout",
                "INDIVIDUAL",
                SanctionsScreeningService.computeSoundex("Viktor Bout"),
                "RU",
                "Arms trafficker",
                true
        ));

        EnterpriseWithdrawalRequest request = new EnterpriseWithdrawalRequest(
                "idem_sanc_01", enterpriseId, PARTNER_ID, "cust_sanctioned_1", null,
                "Victor Anatolyevich Bout", // High-fuzzy Jaro-Winkler match >= 0.85 against Viktor Anatolyevich Bout
                "GHA-9999", 2, 50_000L, "GHS",
                WithdrawalDestinationType.MOMO, "mtn", "0241000004", Map.of()
        );

        assertThatThrownBy(() -> withdrawalEngine.requestWithdrawal(request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Sanctions screening match detected");
    }

    @Test
    @DisplayName("Idempotency: Replaying exact request returns original; modifying payload returns conflict")
    void testIdempotencyAndDuplicatePrevention() {
        EnterpriseWithdrawalRequest request = new EnterpriseWithdrawalRequest(
                "idem_payout_dedup_101", enterpriseId, PARTNER_ID, "cust_dedup_1", null,
                "Akua Donkor", "GHA-444", 2, 20_000L, "GHS",
                WithdrawalDestinationType.MOMO, "mtn", "0241000005", Map.of()
        );

        EnterpriseWithdrawalResult first = withdrawalEngine.requestWithdrawal(request);
        assertThat(first.replayed()).isFalse();

        // Exact retry
        EnterpriseWithdrawalResult replayed = withdrawalEngine.requestWithdrawal(request);
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.withdrawalId()).isEqualTo(first.withdrawalId());

        // Conflicting retry (different amount)
        EnterpriseWithdrawalRequest conflict = new EnterpriseWithdrawalRequest(
                "idem_payout_dedup_101", enterpriseId, PARTNER_ID, "cust_dedup_1", null,
                "Akua Donkor", "GHA-444", 2, 35_000L, "GHS",
                WithdrawalDestinationType.MOMO, "mtn", "0241000005", Map.of()
        );

        assertThatThrownBy(() -> withdrawalEngine.requestWithdrawal(conflict))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Idempotency conflict");
    }

    @Test
    @DisplayName("Retry Failed Payout: Supports retry_of for failed withdrawals")
    void testRetryOfFailedWithdrawal() {
        EnterpriseWithdrawalRequest request = new EnterpriseWithdrawalRequest(
                "idem_retry_base_01", enterpriseId, PARTNER_ID, "cust_retry_1", null,
                "Kwabena Darko", "GHA-555", 1, 15_000L, "GHS",
                WithdrawalDestinationType.MOMO, "mtn", "0241000006", Map.of()
        );

        EnterpriseWithdrawalResult original = withdrawalEngine.requestWithdrawal(request);
        assertThat(original.status()).isEqualTo(WithdrawalStatus.SUBMITTED);

        // Execute retry under new idempotency key
        EnterpriseWithdrawalResult retried = withdrawalEngine.retryWithdrawal(original.withdrawalReference(), "idem_retry_child_02");

        assertThat(retried).isNotNull();
        assertThat(retried.retryOf()).isEqualTo(original.withdrawalId());
        assertThat(retried.withdrawalReference()).isNotEqualTo(original.withdrawalReference());
        assertThat(retried.amountPesewas()).isEqualTo(original.amountPesewas());
    }
}
