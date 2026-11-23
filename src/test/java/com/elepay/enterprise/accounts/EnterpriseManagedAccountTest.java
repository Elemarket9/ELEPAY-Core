package com.elepay.enterprise.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EnterpriseManagedAccountTest extends PostgresTestBase {

    @Autowired
    private EnterpriseManagedAccountService accountService;

    @Autowired
    private Ledger ledger;

    private Enterprise marketplaceEnterprise;

    @BeforeEach
    void setUp() {
        String code = "MARKETPLACE_" + UUID.randomUUID().toString().substring(0, 8);
        marketplaceEnterprise = accountService.registerEnterprise(
                code,
                "Marketplace Ghana Marketplace",
                "finance@" + code.toLowerCase() + ".com",
                "0241000001",
                "https://api.marketplace.com.gh/webhooks/elepay"
        );
    }

    @Test
    @DisplayName("Enterprise registration initializes default tier limits and clearing accounts")
    void enterpriseRegistrationInitializesDefaults() {
        assertThat(marketplaceEnterprise).isNotNull();
        assertThat(marketplaceEnterprise.id()).isNotNull();
        assertThat(marketplaceEnterprise.isActive()).isTrue();

        // Verify tier limits 1, 2, 3 exist
        EnterpriseTierLimit t1 = accountService.getTierLimit(marketplaceEnterprise.id(), 1);
        EnterpriseTierLimit t2 = accountService.getTierLimit(marketplaceEnterprise.id(), 2);
        EnterpriseTierLimit t3 = accountService.getTierLimit(marketplaceEnterprise.id(), 3);

        assertThat(t1.maxBalance()).isEqualTo(100_000L);
        assertThat(t2.maxBalance()).isEqualTo(1_000_000L);
        assertThat(t3.maxBalance()).isEqualTo(5_000_000L);

        // Verify custom tier configuration
        EnterpriseTierLimit custom = accountService.configureTierLimit(
                marketplaceEnterprise.id(), 1, 150_000L, 120_000L, 1_500_000L, 120_000L
        );
        assertThat(custom.maxBalance()).isEqualTo(150_000L);
        assertThat(accountService.getTierLimit(marketplaceEnterprise.id(), 1).maxBalance()).isEqualTo(150_000L);
    }

    @Test
    @DisplayName("Single account creation generates deterministic identity mapping and ledger subaccounts")
    void createAccountGeneratesDeterministicMappingAndLedgerSubaccounts() {
        String externalId = "MARKETPLACE-CUST-" + UUID.randomUUID().toString().substring(0, 8);

        CreateManagedAccountRequest req = CreateManagedAccountRequest.builder()
                .enterpriseId(marketplaceEnterprise.id())
                .externalCustomerId(externalId)
                .displayName("Kofi Mensah")
                .phone("0244123456")
                .email("kofi.mensah@example.com")
                .nationalId("GHA-123456789-1")
                .initialTier(1)
                .currency("GHS")
                .metadata(Map.of("marketplace_tier", "gold"))
                .build();

        EnterpriseManagedAccount account = accountService.createAccount(req);

        assertThat(account).isNotNull();
        assertThat(account.enterpriseId()).isEqualTo(marketplaceEnterprise.id());
        assertThat(account.externalCustomerId()).isEqualTo(externalId);
        assertThat(account.displayName()).isEqualTo("Kofi Mensah");
        assertThat(account.phone()).isEqualTo("233244123456");
        assertThat(account.email()).isEqualTo("kofi.mensah@example.com");
        assertThat(account.nationalId()).isEqualTo("GHA-123456789-1");
        assertThat(account.kycTier()).isEqualTo(1);
        assertThat(account.status()).isEqualTo("active");

        // Verify deterministic ID
        UUID expectedId = accountService.deriveDeterministicAccountId(marketplaceEnterprise.id(), externalId);
        assertThat(account.id()).isEqualTo(expectedId);

        // Verify identity mapping lookup
        EnterpriseManagedAccount resolved = accountService.getAccount(marketplaceEnterprise.id(), externalId);
        assertThat(resolved.id()).isEqualTo(account.id());

        String hash = accountService.computeIdentityHash(marketplaceEnterprise.id(), externalId);
        EnterpriseManagedAccount byHash = accountService.findAccountByIdentityHash(hash).orElseThrow();
        assertThat(byHash.id()).isEqualTo(account.id());

        // Verify all 4 subaccount balances are initialized to 0
        SubaccountBalances balances = accountService.getSubaccountBalances(account.id(), "GHS");
        assertThat(balances.operating()).isEqualTo(0L);
        assertThat(balances.holding()).isEqualTo(0L);
        assertThat(balances.reserved()).isEqualTo(0L);
        assertThat(balances.rewards()).isEqualTo(0L);
        assertThat(balances.total()).isEqualTo(0L);

        // Verify ledger account codes match deterministic pattern
        String opCode = accountService.getLedgerAccountCode(marketplaceEnterprise.id(), externalId, SubaccountBucket.OPERATING, "GHS");
        assertThat(opCode).isEqualTo("enterprise_managed:" + account.id() + ":operating:GHS");
    }

    @Test
    @DisplayName("Create account is idempotent: repeated requests return existing account")
    void createAccountIsIdempotent() {
        String externalId = "MARKETPLACE-CUST-IDEMPOTENT-01";
        CreateManagedAccountRequest req = CreateManagedAccountRequest.builder()
                .enterpriseId(marketplaceEnterprise.id())
                .externalCustomerId(externalId)
                .displayName("Ama Serwaa")
                .phone("0201112233")
                .email("ama.serwaa@example.com")
                .build();

        EnterpriseManagedAccount first = accountService.createAccount(req);
        EnterpriseManagedAccount second = accountService.createAccount(req);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
    }

    @Test
    @DisplayName("Account duplicate detection flags or rejects matching phone, email, and national ID")
    void duplicateDetectionFlagsAndRejectsDuplicates() {
        String phone = "0249876543";
        String email = "duplicate.test@example.com";
        String nationalId = "GHA-999888777-2";

        CreateManagedAccountRequest primary = CreateManagedAccountRequest.builder()
                .enterpriseId(marketplaceEnterprise.id())
                .externalCustomerId("PRIMARY-001")
                .displayName("Original Customer")
                .phone(phone)
                .email(email)
                .nationalId(nationalId)
                .build();
        EnterpriseManagedAccount original = accountService.createAccount(primary);
        assertThat(original.status()).isEqualTo("active");

        // 1. Duplicate phone with FLAG action -> created with flagged_duplicate status
        CreateManagedAccountRequest dupPhone = CreateManagedAccountRequest.builder()
                .enterpriseId(marketplaceEnterprise.id())
                .externalCustomerId("DUP-PHONE-002")
                .displayName("Duplicate Phone")
                .phone(phone)
                .email("other.email@example.com")
                .duplicateAction(DuplicateAction.FLAG)
                .build();
        EnterpriseManagedAccount flaggedPhone = accountService.createAccount(dupPhone);
        assertThat(flaggedPhone.status()).isEqualTo("flagged_duplicate");

        List<EnterpriseDuplicateRecord> dups = accountService.getDuplicatesForAccount(flaggedPhone.id());
        assertThat(dups).isNotEmpty();
        assertThat(dups.get(0).matchField()).isEqualTo("phone");
        assertThat(dups.get(0).matchedAccountId()).isEqualTo(original.id());

        // 2. Resolve duplicate
        accountService.resolveDuplicate(dups.get(0).id(), DuplicateResolution.DISMISSED, "Verified distinct family member");
        List<EnterpriseDuplicateRecord> afterResolve = accountService.getDuplicatesForAccount(flaggedPhone.id());
        assertThat(afterResolve.get(0).resolution()).isEqualTo("dismissed");

        // 3. Duplicate email with REJECT action -> throws ApiException conflict
        CreateManagedAccountRequest dupEmail = CreateManagedAccountRequest.builder()
                .enterpriseId(marketplaceEnterprise.id())
                .externalCustomerId("DUP-EMAIL-003")
                .displayName("Duplicate Email")
                .email(email)
                .duplicateAction(DuplicateAction.REJECT)
                .build();
        assertThatThrownBy(() -> accountService.createAccount(dupEmail))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Duplicate account detected matching email");

        // 4. Duplicate national ID with REJECT action -> throws ApiException conflict
        CreateManagedAccountRequest dupNid = CreateManagedAccountRequest.builder()
                .enterpriseId(marketplaceEnterprise.id())
                .externalCustomerId("DUP-NID-004")
                .displayName("Duplicate National ID")
                .nationalId(nationalId)
                .duplicateAction(DuplicateAction.REJECT)
                .build();
        assertThatThrownBy(() -> accountService.createAccount(dupNid))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Duplicate account detected matching national_id");
    }

    @Test
    @DisplayName("Bulk account creation processes batches and reports summary counts")
    void bulkAccountCreation() {
        List<CreateManagedAccountRequest> batch = List.of(
                CreateManagedAccountRequest.builder()
                        .enterpriseId(marketplaceEnterprise.id())
                        .externalCustomerId("BULK-001")
                        .displayName("Bulk User 1")
                        .phone("0240000001")
                        .build(),
                CreateManagedAccountRequest.builder()
                        .enterpriseId(marketplaceEnterprise.id())
                        .externalCustomerId("BULK-002")
                        .displayName("Bulk User 2")
                        .phone("0240000002")
                        .build(),
                CreateManagedAccountRequest.builder()
                        .enterpriseId(marketplaceEnterprise.id())
                        .externalCustomerId("BULK-003")
                        .displayName("Bulk User 3 (Duplicate Phone)")
                        .phone("0240000001") // duplicate with BULK-001
                        .duplicateAction(DuplicateAction.FLAG)
                        .build()
        );

        BulkCreateAccountsRequest bulkReq = new BulkCreateAccountsRequest(
                marketplaceEnterprise.id(), batch, DuplicateAction.FLAG
        );

        BulkCreateResult result = accountService.createAccountsBulk(bulkReq);

        assertThat(result.totalRequested()).isEqualTo(3);
        assertThat(result.createdCount()).isEqualTo(3);
        assertThat(result.duplicatesFlagged()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.results()).hasSize(3);

        // Re-running the bulk creation detects existing accounts
        BulkCreateResult repeatResult = accountService.createAccountsBulk(bulkReq);
        assertThat(repeatResult.createdCount()).isEqualTo(0);
        assertThat(repeatResult.existingCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("KYC Tier synchronization updates tier limits and enforces maximum balance rules")
    void kycTierSynchronizationEnforcesLimits() {
        String externalId = "MARKETPLACE-KYC-SYNC-01";
        EnterpriseManagedAccount account = accountService.createAccount(
                CreateManagedAccountRequest.builder()
                        .enterpriseId(marketplaceEnterprise.id())
                        .externalCustomerId(externalId)
                        .displayName("Kwame Nkrumah")
                        .initialTier(1)
                        .build()
        );
        assertThat(account.kycTier()).isEqualTo(1);

        // Upgrade to Tier 2
        EnterpriseManagedAccount upgraded = accountService.syncKycTier(new KycSyncRequest(
                account.id(), 2, "Ghana Card biometric verified", Map.of("id_type", "GHANA_CARD")
        ));
        assertThat(upgraded.kycTier()).isEqualTo(2);

        // Fund with 200,000 pesewas (exceeds Tier 1 ceiling of 100,000 pesewas, but valid for Tier 2)
        accountService.fundOperatingBalance(
                account.id(), 200_000L, "GHS", "tx-fund-tier2", "Funding under Tier 2 limit"
        );
        SubaccountBalances balances = accountService.getSubaccountBalances(account.id(), "GHS");
        assertThat(balances.operating()).isEqualTo(200_000L);

        // Attempting to downgrade back to Tier 1 when balance exceeds Tier 1 maxBalance fails
        assertThatThrownBy(() -> accountService.syncKycTier(new KycSyncRequest(
                account.id(), 1, "Downgrade requested", Map.of()
        )))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exceeds Tier 1 limit");
    }

    @Test
    @DisplayName("Double-entry subaccount lifecycle: operating, reserved, holding, and rewards")
    void doubleEntrySubaccountLifecycleAndConservationOfMoney() {
        String externalId = "MARKETPLACE-LIFECYCLE-01";
        EnterpriseManagedAccount account = accountService.createAccount(
                CreateManagedAccountRequest.builder()
                        .enterpriseId(marketplaceEnterprise.id())
                        .externalCustomerId(externalId)
                        .displayName("Esi Taylor")
                        .initialTier(2)
                        .build()
        );

        // 1. Fund operating subaccount with GH₵400 (40,000 pesewas)
        accountService.fundOperatingBalance(
                account.id(), 40_000L, "GHS", "tx-fund-op", "Initial customer deposit"
        );
        SubaccountBalances b1 = accountService.getSubaccountBalances(account.id(), "GHS");
        assertThat(b1.operating()).isEqualTo(40_000L);
        assertThat(b1.total()).isEqualTo(40_000L);

        // 2. Reserve GH₵150 (15,000 pesewas) for a pending delivery checkout
        accountService.reserveFunds(
                account.id(), 15_000L, "GHS", "tx-reserve-01", "Reserve checkout amount"
        );
        SubaccountBalances b2 = accountService.getSubaccountBalances(account.id(), "GHS");
        assertThat(b2.operating()).isEqualTo(25_000L);
        assertThat(b2.reserved()).isEqualTo(15_000L);
        assertThat(b2.total()).isEqualTo(40_000L); // Conservation of money

        // 3. Release GH₵50 (5,000 pesewas) from reserved back to operating
        accountService.releaseReservedFunds(
                account.id(), 5_000L, "GHS", "tx-release-01", "Partial checkout cancellation"
        );
        SubaccountBalances b3 = accountService.getSubaccountBalances(account.id(), "GHS");
        assertThat(b3.operating()).isEqualTo(30_000L);
        assertThat(b3.reserved()).isEqualTo(10_000L);
        assertThat(b3.total()).isEqualTo(40_000L);

        // 4. Credit GH₵100 (10,000 pesewas) to holding subaccount (e.g. escrow for order delivery)
        accountService.creditHolding(
                account.id(), 10_000L, "GHS", "tx-credit-hold", "Marketplace escrow hold"
        );
        SubaccountBalances b4 = accountService.getSubaccountBalances(account.id(), "GHS");
        assertThat(b4.holding()).isEqualTo(10_000L);
        assertThat(b4.total()).isEqualTo(50_000L);

        // 5. Release GH₵100 (10,000 pesewas) from holding to operating
        accountService.releaseHoldingToOperating(
                account.id(), 10_000L, "GHS", "tx-release-hold", "Escrow fulfilled release"
        );
        SubaccountBalances b5 = accountService.getSubaccountBalances(account.id(), "GHS");
        assertThat(b5.holding()).isEqualTo(0L);
        assertThat(b5.operating()).isEqualTo(40_000L);
        assertThat(b5.total()).isEqualTo(50_000L);

        // 6. Award GH₵20 (2,000 pesewas) rewards points
        accountService.creditRewards(
                account.id(), 2_000L, "GHS", "tx-reward-01", "Marketplace loyalty cashback"
        );
        SubaccountBalances b6 = accountService.getSubaccountBalances(account.id(), "GHS");
        assertThat(b6.rewards()).isEqualTo(2_000L);
        assertThat(b6.total()).isEqualTo(52_000L);

        // 7. Transfer rewards to operating balance
        accountService.transferBetweenSubaccounts(
                account.id(), SubaccountBucket.REWARDS, SubaccountBucket.OPERATING,
                2_000L, "GHS", "tx-rewards-transfer", "Convert rewards to operating cash"
        );
        SubaccountBalances b7 = accountService.getSubaccountBalances(account.id(), "GHS");
        assertThat(b7.rewards()).isEqualTo(0L);
        assertThat(b7.operating()).isEqualTo(42_000L);
        assertThat(b7.total()).isEqualTo(52_000L);

        // 8. Verify Ledger Integrity
        Ledger.Integrity integrity = ledger.integrity();
        assertThat(integrity.ok()).isTrue();
    }
}
