package com.elepay.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.Idempotency;
import com.elepay.common.OpsQueue;
import com.elepay.compliance.SanctionsScreeningService;
import com.elepay.enterprise.payout.EnterpriseSettlementFundingService;
import com.elepay.enterprise.payout.EnterpriseWithdrawalComplianceService;
import com.elepay.enterprise.payout.EnterpriseWithdrawalRequest;
import com.elepay.enterprise.payout.ExternalWalletWithdrawalEngine;
import com.elepay.enterprise.payout.WithdrawalDestinationType;
import com.elepay.identity.Actor;
import com.elepay.identity.KillSwitchService;
import com.elepay.ledger.Ledger;
import com.elepay.merchant.MerchantAccess;
import com.elepay.operations.EmtechCapture;
import com.elepay.outbox.Outbox;
import com.elepay.rails.NameVerifications;
import com.elepay.rails.RailTransactions;
import com.elepay.security.DualControl;
import com.elepay.security.MakerCheckerService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class PayoutAndKillSwitchRegressionTest extends PostgresTestBase {

    @Autowired JdbcClient db;
    @Autowired Ledger ledger;
    @Autowired Audit audit;
    @Autowired OpsQueue ops;
    @Autowired ObjectMapper json;
    @Autowired Idempotency idempotency;
    @Autowired PlatformTransactionManager txManager;
    @Autowired MakerCheckerService makerChecker;
    @Autowired MerchantAccess merchantAccess;
    @Autowired Payouts payouts;
    @Autowired RailTransactions rails;
    @Autowired Outbox outbox;
    @Autowired ApplicationEventPublisher events;
    @Autowired EmtechCapture regulatory;
    @Autowired PayoutVelocityService velocityService;
    @Autowired LargePayoutHandler largePayoutHandler;

    @Test
    @DisplayName("Regression: KillSwitchService syncs module disable, global unfreeze, and merchant unfreeze via Redis and fails closed on Redis outage")
    @SuppressWarnings("unchecked")
    void testKillSwitchMultiInstanceRedisSyncAndFailClosed() {
        Map<String, String> kv = new ConcurrentHashMap<>();
        Map<String, Set<String>> sets = new ConcurrentHashMap<>();
        AtomicBoolean redisDown = new AtomicBoolean(false);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valOps = mock(ValueOperations.class);
        SetOperations<String, String> setOps = mock(SetOperations.class, inv -> {
            if (redisDown.get()) throw new RuntimeException("Redis down");
            String method = inv.getMethod().getName();
            if ("add".equals(method)) {
                String key = inv.getArgument(0);
                Set<String> set = sets.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
                for (int i = 1; i < inv.getArguments().length; i++) {
                    Object arg = inv.getArguments()[i];
                    if (arg instanceof Object[] arr) {
                        for (Object o : arr) if (o != null) set.add(o.toString());
                    } else if (arg != null) {
                        set.add(arg.toString());
                    }
                }
                return 1L;
            }
            if ("remove".equals(method)) {
                Set<String> s = sets.get(inv.getArgument(0));
                if (s == null) return 0L;
                long count = 0;
                for (int i = 1; i < inv.getArguments().length; i++) {
                    Object arg = inv.getArguments()[i];
                    if (arg instanceof Object[] arr) {
                        for (Object o : arr) if (o != null && s.remove(o.toString())) count++;
                    } else if (arg != null && s.remove(arg.toString())) {
                        count++;
                    }
                }
                return count;
            }
            if ("isMember".equals(method)) {
                Set<String> s = sets.get(inv.getArgument(0));
                Object member = inv.getArgument(1);
                return s != null && member != null && s.contains(member.toString());
            }
            return null;
        });
        when(redis.opsForValue()).thenReturn(valOps);
        when(redis.opsForSet()).thenReturn(setOps);

        doAnswer(inv -> {
            if (redisDown.get()) throw new RuntimeException("Redis down");
            kv.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valOps).set(anyString(), anyString());

        when(valOps.get(anyString())).thenAnswer(inv -> {
            if (redisDown.get()) throw new RuntimeException("Redis down");
            return kv.get(inv.getArgument(0));
        });

        KillSwitchService instanceA = new KillSwitchService(db, null, null, redis, true, true, true, true, true, true);
        KillSwitchService instanceB = new KillSwitchService(db, null, null, redis, true, true, true, true, true, true);
        boolean wasPosEnabled = instanceA.isModuleEnabled("pos");

        try {
            // 1. Module disable on A propagates to B
            instanceA.setModuleEnabled("pos", true, "init", "ops-0");
            assertThat(instanceB.isModuleEnabled("pos")).isTrue();
            instanceA.setModuleEnabled("pos", false, "incident", "ops-1");
            assertThat(instanceB.isModuleEnabled("pos")).isFalse();

            // 2. Global freeze and unfreeze on A propagates to B
            instanceA.setGlobalFrozen(true, "emergency", "ops-1");
            assertThat(instanceB.isGlobalFrozen()).isTrue();
            instanceA.setGlobalFrozen(false, "resolved", "ops-2");
            assertThat(instanceB.isGlobalFrozen()).isFalse();

            // 3. Merchant freeze and unfreeze on A propagates to B
            UUID merchantId = UUID.randomUUID();
            instanceA.setMerchantFrozen(merchantId, true, "risk", "ops-1");
            assertThat(instanceB.isMerchantFrozen(merchantId)).isTrue();
            instanceA.setMerchantFrozen(merchantId, false, "cleared", "ops-2");
            assertThat(instanceB.isMerchantFrozen(merchantId)).isFalse();

            // 4. Redis outage after being healthy fails closed
            redisDown.set(true);
            assertThat(instanceB.isModuleEnabled("pos")).isFalse();
            assertThat(instanceB.isGlobalFrozen()).isTrue();
            assertThat(instanceB.isMerchantFrozen(merchantId)).isTrue();
        } finally {
            redisDown.set(false);
            instanceA.setModuleEnabled("pos", wasPosEnabled, "restore after kill-switch test", "test-cleanup");
        }
    }

    @Test
    @DisplayName("Regression: MerchantPayouts.batch enforces largePayoutPesewas dual control and LargePayoutHandler executes batch on approval")
    void testBatchPayoutDualControlAndLargePayoutHandler() {
        UUID merchantId = UUID.randomUUID();
        String s = UUID.randomUUID().toString().substring(0, 8);
        String code = String.format("%06d", Math.abs(merchantId.hashCode()) % 900000 + 100000);
        db.sql("""
                INSERT INTO merchant (id, legal_name, trading_name, kind, status, contact_phone, merchant_code, created_by)
                VALUES (:id, :name, :name, 'merchant', 'active', '0241234567', :code, 'test')""")
                .param("id", merchantId).param("name", "Batch Dual Control " + s).param("code", code).update();

        String availAcc = ledger.open("merchant", merchantId, "available", Ledger.GHS);
        ledger.open("merchant", merchantId, "reserved", Ledger.GHS);
        String seedAcc = ledger.openSystem("system:seed:batch:" + s, "clearing", Ledger.GHS, true);
        ledger.post(Ledger.Posting.of("seed", "batch-" + s, "seed",
                List.of(Ledger.Leg.debit(seedAcc, 20_000_000L), Ledger.Leg.credit(availAcc, 20_000_000L))));

        DualControl enforcedDualControl = new DualControl(makerChecker, true);
        MerchantPayouts controller = new MerchantPayouts(payouts, merchantAccess, idempotency, db, txManager, enforcedDualControl);

        for (String[] dest : List.of(
                new String[]{"momo", "mtn", "233241234567", "Kofi Mensah"},
                new String[]{"momo", "mtn", "233241234568", "Ama Serwaa"})) {
            db.sql("""
                    INSERT INTO settlement_destination
                        (id, owner_type, owner_id, type, network, account_number, account_name, verification, verified_name, status, created_by, cooling_off_until)
                    VALUES (:id, 'merchant', :m, :t, :n, :a, :name, 'verified', :name, 'active', 'test', now() - interval '1 hour')""")
                    .param("id", UUID.randomUUID()).param("m", merchantId).param("t", dest[0])
                    .param("n", dest[1]).param("a", dest[2]).param("name", dest[3]).update();
        }

        // Authenticate as merchant member
        db.sql("INSERT INTO merchant_member (merchant_id, subject, role) VALUES (:m, :sub, 'owner')")
                .param("m", merchantId).param("sub", "maker-user-" + s).update();
        Jwt jwt = Jwt.withTokenValue("tok").header("alg", "none").subject("maker-user-" + s).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(() -> "ROLE_merchant")));

        try {
            var batchInput = new MerchantPayouts.BatchInput(
                    List.of(
                            new MerchantPayouts.ItemInput("ref-1-" + s, 3_000_000L, "momo", "mtn", "0241234567", "Kofi Mensah", null),
                            new MerchantPayouts.ItemInput("ref-2-" + s, 3_000_000L, "momo", "mtn", "0241234568", "Ama Serwaa", null)
                    ),
                    "Split large batch totaling 6M pesewas"
            );

            // Batch total 6,000,000 >= 5,000,000 triggers dual control hold (202 pending_approval)
            assertThatThrownBy(() -> controller.batch(merchantId, "batch-key-" + s, batchInput))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("approve proposal");

            UUID proposalId = db.sql("SELECT id FROM maker_checker_proposal WHERE merchant_id = :m AND action_type = 'LARGE_PAYOUT' ORDER BY proposed_at DESC LIMIT 1")
                    .param("m", merchantId).query(UUID.class).single();

            // Distinct checker approves -> LargePayoutHandler executes batch and submits after commit
            var approved = makerChecker.approve(proposalId, "checker-admin-" + s, "Approved");
            assertThat(approved.status()).isEqualTo("APPROVED");

            Long batchCount = db.sql("SELECT count(*) FROM payout_batch WHERE owner_id = :m")
                    .param("m", merchantId).query(Long.class).single();
            assertThat(batchCount).isEqualTo(1L);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("Regression: ExternalWalletWithdrawalEngine enforces authoritative kyc_tier from DB and persists PostgreSQL idempotency")
    void testExternalWalletWithdrawalAuthoritativeKycTierAndPostgresIdempotency() {
        UUID enterpriseId = UUID.randomUUID();
        String s = UUID.randomUUID().toString().substring(0, 8);
        String code = String.format("%06d", Math.abs(enterpriseId.hashCode()) % 900000 + 100000);
        db.sql("""
                INSERT INTO merchant (id, legal_name, trading_name, kind, status, contact_phone, merchant_code, created_by)
                VALUES (:id, :name, :name, 'merchant', 'active', '0241234567', :code, 'test')""")
                .param("id", enterpriseId).param("name", "Enterprise " + s).param("code", code).update();
        db.sql("""
                INSERT INTO enterprise (id, code, name, status)
                VALUES (:id, :code, :name, 'active')""")
                .param("id", enterpriseId).param("code", "ENT-" + s).param("name", "Enterprise " + s).update();

        String custId = "cust-" + s;
        db.sql("""
                INSERT INTO enterprise_managed_account (id, enterprise_id, external_customer_id, display_name, kyc_tier, status, currency)
                VALUES (:id, :eid, :cid, 'Tier 1 User', 1, 'active', 'GHS')""")
                .param("id", UUID.randomUUID()).param("eid", enterpriseId).param("cid", custId).update();

        String entAvail = ledger.open("merchant", enterpriseId, "available", Ledger.GHS);
        ledger.open("merchant", enterpriseId, "reserved", Ledger.GHS);
        String seed = ledger.openSystem("system:seed:ent:" + s, "clearing", Ledger.GHS, true);
        ledger.post(Ledger.Posting.of("seed-ent", s, "seed",
                List.of(Ledger.Leg.debit(seed, 5_000_000L), Ledger.Leg.credit(entAvail, 5_000_000L))));

        EnterpriseSettlementFundingService funding = new EnterpriseSettlementFundingService(ledger);
        EnterpriseWithdrawalComplianceService compliance = new EnterpriseWithdrawalComplianceService(
                new SanctionsScreeningService(db), db);

        ExternalWalletWithdrawalEngine engine1 = new ExternalWalletWithdrawalEngine(
                null, funding, compliance, payouts, db, txManager);

        // Caller tries to forge kycTier = 3 for a Tier 1 customer (Tier 1 limit = 100,000 pesewas)
        var forgedReq = new EnterpriseWithdrawalRequest(
                "ewd-forged-" + s, enterpriseId, "partner-test", custId, null,
                "Tier 1 User", null, 3, 200_000L, "GHS",
                WithdrawalDestinationType.MOMO, "mtn", "0241234567", Map.of()
        );
        assertThatThrownBy(() -> engine1.requestWithdrawal(forgedReq))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("daily limit");

        // Valid Tier 1 withdrawal (50,000 pesewas)
        var validReq = new EnterpriseWithdrawalRequest(
                "ewd-ok-" + s, enterpriseId, "partner-test", custId, null,
                "Tier 1 User", null, 3, 50_000L, "GHS",
                WithdrawalDestinationType.MOMO, "mtn", "0241234567", Map.of()
        );
        var res1 = engine1.requestWithdrawal(validReq);
        assertThat(res1.replayed()).isFalse();

        // Simulate restart with a fresh engine instance: PostgreSQL idempotency returns replay
        ExternalWalletWithdrawalEngine engine2 = new ExternalWalletWithdrawalEngine(
                null, funding, compliance, payouts, db, txManager);
        var res2 = engine2.requestWithdrawal(validReq);
        assertThat(res2.replayed()).isTrue();
        assertThat(res2.withdrawalId()).isEqualTo(res1.withdrawalId());
    }

    @Test
    @DisplayName("Regression: Payouts.create does not record velocity outflow for not_found rejected items")
    void testPayoutsCreateSkipsVelocityForNotFoundRejectedItems() {
        UUID merchantId = UUID.randomUUID();
        String s = UUID.randomUUID().toString().substring(0, 8);
        String code = String.format("%06d", Math.abs(merchantId.hashCode()) % 900000 + 100000);
        db.sql("""
                INSERT INTO merchant (id, legal_name, trading_name, kind, status, contact_phone, merchant_code, created_by)
                VALUES (:id, :name, :name, 'merchant', 'active', '0241234567', :code, 'test')""")
                .param("id", merchantId).param("name", "VelTest " + s).param("code", code).update();

        NameVerifications notFoundVerifier = mock(NameVerifications.class);
        when(notFoundVerifier.verify(anyString(), anyString(), anyString()))
                .thenReturn(new com.elepay.rails.NameVerification.Result(
                        com.elepay.rails.NameVerification.Outcome.NOT_FOUND, null, "test"));

        Payouts customPayouts = new Payouts(db, ledger, rails, notFoundVerifier, outbox, audit, ops, json,
                events, regulatory, null, velocityService);

        long beforeHourly = db.sql("SELECT COALESCE(SUM(total_outflow_pesewas), 0) FROM payout_global_velocity_window WHERE window_type = 'HOURLY'")
                .query(Long.class).single();

        var batch = customPayouts.create("merchant", merchantId,
                List.of(new Payouts.Item("nf-" + s, 123_456L,
                        new Payouts.Destination("momo", "mtn", "0249999999", "Unknown"), null, null)),
                "Not found test", "tester");

        assertThat(batch.items()).hasSize(1);
        assertThat(batch.items().get(0).status()).isEqualTo("rejected");

        long afterHourly = db.sql("SELECT COALESCE(SUM(total_outflow_pesewas), 0) FROM payout_global_velocity_window WHERE window_type = 'HOURLY'")
                .query(Long.class).single();
        assertThat(afterHourly).isEqualTo(beforeHourly);
    }
}
