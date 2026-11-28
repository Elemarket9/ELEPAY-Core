package com.elepay.rails;

import com.elepay.common.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementFunding {
    private final List<SettlementBank> banks;
    private final JdbcClient db;

    SettlementFunding(List<SettlementBank> banks, JdbcClient db) {
        this.banks = banks;
        this.db = db;
    }

    SettlementBank bank() {
        if (banks.size() != 1) throw ApiException.conflict("settlement_bank_unconfigured", "One approved bank connection is required");
        return banks.get(0);
    }

    CustodianAccountProvider.Account source() {
        var approved = db.sql("""
                SELECT bank_code, bank_account_number, currency FROM custodian_trust_account
                WHERE account_code = :code AND bank_code IS NOT NULL AND bank_account_number IS NOT NULL""")
                .param("code", CustodianBankBackstopService.PRIMARY_TRUST_CODE)
                .query((r,i) -> new CustodianAccountProvider.Account(r.getString(1), r.getString(2), r.getString(3))).optional();
        var current = approved.orElseThrow(() -> ApiException.conflict("settlement_bank_unconfigured", "No approved settlement account"));
        if (!current.equals(bank().verifiedTrustAccount())) throw ApiException.conflict("settlement_account_changed", "Bank account binding changed");
        return current;
    }

    @Transactional
    public void confirmCredit(String reference) {
        var account = source();
        var credit = bank().bookedCredit(account, reference);
        if (credit == null || !account.equals(credit.account()) || !reference.equals(credit.reference())
                || credit.amount() <= 0 || credit.bookedAt() == null || credit.collectionReferences() == null
                || credit.collectionReferences().isEmpty()) throw invalidCredit();
        String key = CustodianConnection.fingerprint(account);
        int added = db.sql("""
                INSERT INTO settlement_bank_credit VALUES (:key,:ref,:amount,:booked)
                ON CONFLICT DO NOTHING""").param("key",key).param("ref",reference)
                .param("amount",credit.amount()).param("booked",credit.bookedAt()).update();
        if (added == 0) {
            boolean matches = db.sql("""
                    SELECT EXISTS(SELECT 1 FROM settlement_bank_credit
                    WHERE source_key=:key AND bank_reference=:ref AND amount=:amount AND booked_at=:booked)""")
                    .param("key", key).param("ref", reference).param("amount", credit.amount())
                    .param("booked", credit.bookedAt()).query(Boolean.class).single();
            var prior = db.sql("""
                    SELECT t.reference FROM settlement_funded_checkout f JOIN payment_attempt a ON a.checkout_id=f.checkout_id
                    JOIN rail_transaction t ON t.id=a.rail_tx WHERE f.source_key=:key AND f.bank_reference=:ref AND a.status='succeeded'""")
                    .param("key", key).param("ref", reference).query(String.class).list();
            if (!matches || prior.size() != credit.collectionReferences().size()
                    || !new java.util.HashSet<>(prior).equals(new java.util.HashSet<>(credit.collectionReferences()))) throw invalidCredit();
            return;
        }
        long total = 0;
        for (String collection : credit.collectionReferences()) {
            var row = db.sql("""
                    SELECT c.id, c.total_paid FROM checkout c JOIN payment_attempt a ON a.checkout_id=c.id
                    JOIN rail_transaction t ON t.id=a.rail_tx JOIN settlement_bank_collection sc ON sc.rail_tx=t.id AND sc.source_key=:key
                    WHERE t.reference=:ref AND t.direction='charge' AND t.status='succeeded'
                    AND a.status='succeeded' AND c.status='paid' AND c.currency='GHS' AND c.total_paid=t.amount
                    AND NOT EXISTS (SELECT 1 FROM checkout_allocation x WHERE x.checkout_id=c.id AND x.settled_at IS NOT NULL)
                    FOR UPDATE OF c""").param("ref",collection).param("key",key)
                    .query((r,i) -> java.util.Map.entry(r.getObject(1,UUID.class),r.getLong(2))).optional().orElseThrow(SettlementFunding::invalidCredit);
            if (db.sql("INSERT INTO settlement_funded_checkout VALUES (:id,:key,:ref) ON CONFLICT DO NOTHING")
                    .param("id",row.getKey()).param("key",key).param("ref",reference).update()!=1) throw invalidCredit();
            total = Math.addExact(total,row.getValue());
        }
        if (total != credit.amount()) throw invalidCredit();
    }

    @Transactional
    public void reconcileDebit(String reference) {
        record Expected(UUID id, String sourceKey, String network, String beneficiary, long amount) {}
        var instruction = db.sql("""
                SELECT t.id, i.source_key, t.request->>'network', t.request->>'accountNumber', i.amount
                FROM rail_transaction t JOIN settlement_bank_instruction i ON i.rail_tx=t.id
                WHERE t.reference=:ref AND t.status='succeeded'""")
                .param("ref", reference).query((r,i) -> new Expected(r.getObject(1, UUID.class),
                        r.getString(2), r.getString(3), r.getString(4), r.getLong(5))).optional()
                .orElseThrow(() -> ApiException.notFound("Completed settlement instruction not found"));
        var account = source();
        if (!instruction.sourceKey().equals(CustodianConnection.fingerprint(account))) throw debitMismatch();
        var debit = bank().bookedDebit(account, reference);
        if (debit == null || !account.equals(debit.source()) || !reference.equals(debit.instructionReference())
                || !instruction.network().equals(debit.beneficiaryNetwork()) || !instruction.beneficiary().equals(debit.beneficiaryAccount())
                || !account.currency().equals(debit.currency()) || instruction.amount() != debit.amount()
                || debit.bookedAt() == null || debit.reference() == null || debit.reference().isBlank()) throw debitMismatch();
        int matched = db.sql("""
                UPDATE settlement_bank_instruction SET statement_reference=:statement, bank_booked_at=:booked
                WHERE rail_tx=:id AND (statement_reference IS NULL OR (statement_reference=:statement AND bank_booked_at=:booked))""")
                .param("statement", debit.reference()).param("booked", debit.bookedAt()).param("id", instruction.id()).update();
        if (matched != 1) throw debitMismatch();
    }

    private static ApiException debitMismatch() {
        return ApiException.conflict("bank_debit_unmatched", "Bank debit does not match the authorized payout");
    }

    private static ApiException invalidCredit() {
        return ApiException.conflict("unmatched_bank_credit","Bank credit does not match unpaid settlement allocations");
    }
}
