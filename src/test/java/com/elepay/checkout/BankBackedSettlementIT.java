package com.elepay.checkout;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.ledger.Ledger;
import com.elepay.payout.Payouts;
import com.elepay.rails.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Import(BankBackedSettlementIT.BankConfiguration.class)
@TestPropertySource(properties = {"elepay.rails.default=test-bank", "elepay.payouts.auto-enabled=false"})
class BankBackedSettlementIT extends PostgresTestBase {
    @TestConfiguration
    static class BankConfiguration {
        @Bean SettlementBank testBank() {
            var bank = mock(SettlementBank.class);
            when(bank.name()).thenReturn("test-bank");
            when(bank.supports("payout", "bank", "TST")).thenReturn(true);
            return bank;
        }
    }

    @Autowired JdbcClient db;
    @Autowired SettlementBank bank;
    @Autowired SettlementFunding funding;
    @Autowired CheckoutReserveService settlements;
    @Autowired Payouts payouts;
    @Autowired Ledger ledger;
    @Autowired RailTransactions rails;

    @Test
    void confirmedSplitSurvivesConcurrentRequestsAndBankTimeout() throws Exception {
        UUID a = merchant("10000001"), b = merchant("10000002"), checkout = UUID.randomUUID();
        var account = new CustodianAccountProvider.Account("TST", "99900001", "GHS");
        String key = Idempotency.sha256("TST|99900001|GHS");
        var now = OffsetDateTime.now();
        when(bank.verifiedTrustAccount()).thenReturn(account);
        assertThatThrownBy(() -> funding.confirmCredit("credit")).isInstanceOf(ApiException.class);
        verify(bank, never()).bookedCredit(any(), anyString());
        try {
            db.sql("UPDATE custodian_trust_account SET bank_code='TST', bank_account_number='99900001' WHERE account_code='CUSTODIAN_TRUST_PRIMARY_GHS'").update();
            db.sql("""
                    INSERT INTO checkout(id,reference,collector_id,status,subtotal,total_paid,payment_method,paid_at,expires_at,created_by)
                    VALUES (?,? ,?,'paid',100000,100000,'card',now(),now()+interval '1 day','test')""").params(checkout,checkout.toString(),a).update();
            UUID charge = UUID.randomUUID(), attempt = UUID.randomUUID();
            String reference = charge.toString();
            db.sql("""
                    INSERT INTO rail_transaction(id,rail,direction,reference,purpose,purpose_id,amount,status)
                    VALUES (?,'card','charge',?,'checkout',?,100000,'succeeded')""").params(charge,reference,attempt).update();
            db.sql("INSERT INTO settlement_bank_collection VALUES (?,?)").params(charge,key).update();
            db.sql("INSERT INTO payment_attempt(id,checkout_id,method,rail_tx,status) VALUES (?,?,'card',?,'succeeded')")
                    .params(attempt,checkout,charge).update();
            seedLine(checkout,1,a,60000);
            seedLine(checkout,2,b,40000);
            assertThat(settlements.settleDue(true,null,checkout)).isEmpty();
            when(bank.bookedCredit(account,"wrong-total")).thenReturn(new SettlementBank.Credit(account,"wrong-total",90000,now,List.of(reference)));
            assertThatThrownBy(() -> funding.confirmCredit("wrong-total")).isInstanceOf(ApiException.class);
            assertThat(settlements.settleDue(true,null,checkout)).isEmpty();
            when(bank.bookedCredit(account,"credit")).thenReturn(new SettlementBank.Credit(account,"credit",100000,now,List.of(reference)));
            funding.confirmCredit("credit");
            funding.confirmCredit("credit");
            settlements.settleDue(true,null,checkout);
            assertThatThrownBy(() -> request(a,"10000002")).isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException)e).code()).isEqualTo("destination_unverified");
            assertThatThrownBy(() -> request(a,"10000001")).isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException)e).code()).isEqualTo("settlement_not_authorized");
            verify(bank, never()).payFrom(any(), any());
            when(bank.mayPay(account)).thenReturn(true);
            var barrier = new CyclicBarrier(2);
            Callable<List<Payouts.Batch>> race = () -> {
                barrier.await(10,TimeUnit.SECONDS);
                try { return List.of(request(a,"10000001")); }
                catch (ApiException rejected) { return List.of(); }
            };
            Payouts.Batch winner;
            try (var executor = Executors.newFixedThreadPool(2)) {
                var attempts = executor.invokeAll(List.of(race,race));
                var winners = new java.util.ArrayList<Payouts.Batch>();
                for (var result : attempts) winners.addAll(result.get());
                assertThat(winners).hasSize(1);
                winner = winners.getFirst();
            }
            when(bank.payFrom(eq(account),any())).thenReturn(PaymentRail.Result.unknown("timeout"));
            payouts.submit(winner.id());
            UUID railId = db.sql("SELECT rail_tx FROM payout WHERE batch_id=?").param(winner.id()).query(UUID.class).single();
            var tx = rails.find(railId).orElseThrow();
            assertThat(tx.status()).isEqualTo("unknown");
            when(bank.queryPayout(eq(account),any(),any())).thenReturn(new PaymentRail.Result(
                    PaymentRail.Status.SUCCEEDED,"debit",50000L,"GHS",0L,null,null));
            rails.recover(1000,true);
            assertThat(rails.find(railId).orElseThrow().status()).isEqualTo("succeeded");
            verify(bank,times(1)).payFrom(eq(account),any());
            when(bank.bookedDebit(account,tx.reference())).thenReturn(new SettlementBank.Debit(account,"statement-debit",
                    tx.reference(),"TST","10000001","GHS",50000,now));
            funding.reconcileDebit(tx.reference());
            assertThat(db.sql("SELECT statement_reference FROM settlement_bank_instruction WHERE rail_tx=?")
                    .param(railId).query(String.class).single()).isEqualTo("statement-debit");
            assertThat(ledger.balance(Ledger.code("merchant",a,"available","GHS"))).isEqualTo(10000);
            assertThat(ledger.balance(Ledger.code("merchant",b,"available","GHS"))).isEqualTo(40000);
        } finally {
            db.sql("UPDATE custodian_trust_account SET bank_code=NULL,bank_account_number=NULL WHERE bank_code='TST' AND bank_account_number='99900001'").update();
        }
    }

    private Payouts.Batch request(UUID merchant,String number) {
        var dest = new Payouts.Destination("bank","TST",number,"Test merchant");
        return payouts.create("merchant",merchant,List.of(new Payouts.Item(UUID.randomUUID().toString(),50000,dest,null,"Verified")),null,"test");
    }

    private UUID merchant(String number) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO merchant(id,legal_name,trading_name,kind,status,contact_phone,merchant_code,created_by)
                VALUES (?,'Test','Test','merchant','active','0241234567',?,'test')""")
                .params(id,Integer.toString(ThreadLocalRandom.current().nextInt(100000,1000000))).update();
        db.sql("""
                INSERT INTO settlement_destination(id,owner_type,owner_id,type,network,account_number,account_name,
                verification,verified_name,oob_verification_status,created_by)
                VALUES (?,'merchant',?,'bank','TST',?,'Test merchant','verified','Verified','confirmed','test')""")
                .params(UUID.randomUUID(),id,number).update();
        ledger.open("merchant",id,"reserved","GHS");
        return id;
    }

    private void seedLine(UUID checkout,int line,UUID merchant,long amount) {
        db.sql("""
                INSERT INTO checkout_allocation(checkout_id,line,merchant_id,kind,amount,merchant_fee,pending_amount)
                VALUES (?,?,?,'sale',?,0,?)""").params(checkout,line,merchant,amount,amount).update();
        String clearing = ledger.openSystem("system:test:"+merchant,"clearing","GHS",true);
        String pending = ledger.open("merchant",merchant,"pending","GHS");
        ledger.post(Ledger.Posting.of("test",merchant.toString(),"seed",
                List.of(Ledger.Leg.debit(clearing,amount),Ledger.Leg.credit(pending,amount))));
    }
}
