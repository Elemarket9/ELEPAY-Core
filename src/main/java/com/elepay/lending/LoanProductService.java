package com.elepay.lending;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanProductService {

    private final JdbcClient db;

    public LoanProductService(JdbcClient db) {
        this.db = db;
    }

    public record LoanProduct(
            UUID id,
            String code,
            String name,
            String productType,
            long minAmountPesewas,
            long maxAmountPesewas,
            int minTenorDays,
            int maxTenorDays,
            int interestRateBps,
            int originationFeeBps,
            int penaltyRateBps,
            int dailySweepBps,
            String status
    ) {}

    public List<LoanProduct> listActiveProducts() {
        return db.sql("""
            SELECT id, code, name, product_type, min_amount_pesewas, max_amount_pesewas,
                   min_tenor_days, max_tenor_days, interest_rate_bps, origination_fee_bps,
                   penalty_rate_bps, daily_sweep_bps, status
            FROM loan_product
            WHERE status = 'ACTIVE'
            ORDER BY code ASC
        """).query((rs, rowNum) -> new LoanProduct(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("product_type"),
                rs.getLong("min_amount_pesewas"),
                rs.getLong("max_amount_pesewas"),
                rs.getInt("min_tenor_days"),
                rs.getInt("max_tenor_days"),
                rs.getInt("interest_rate_bps"),
                rs.getInt("origination_fee_bps"),
                rs.getInt("penalty_rate_bps"),
                rs.getInt("daily_sweep_bps"),
                rs.getString("status")
        )).list();
    }

    public Optional<LoanProduct> getProductById(UUID id) {
        return db.sql("""
            SELECT id, code, name, product_type, min_amount_pesewas, max_amount_pesewas,
                   min_tenor_days, max_tenor_days, interest_rate_bps, origination_fee_bps,
                   penalty_rate_bps, daily_sweep_bps, status
            FROM loan_product
            WHERE id = ?
        """).param(id)
        .query((rs, rowNum) -> new LoanProduct(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("product_type"),
                rs.getLong("min_amount_pesewas"),
                rs.getLong("max_amount_pesewas"),
                rs.getInt("min_tenor_days"),
                rs.getInt("max_tenor_days"),
                rs.getInt("interest_rate_bps"),
                rs.getInt("origination_fee_bps"),
                rs.getInt("penalty_rate_bps"),
                rs.getInt("daily_sweep_bps"),
                rs.getString("status")
        )).optional();
    }

    public Optional<LoanProduct> getProductByCode(String code) {
        return db.sql("""
            SELECT id, code, name, product_type, min_amount_pesewas, max_amount_pesewas,
                   min_tenor_days, max_tenor_days, interest_rate_bps, origination_fee_bps,
                   penalty_rate_bps, daily_sweep_bps, status
            FROM loan_product
            WHERE code = ?
        """).param(code)
        .query((rs, rowNum) -> new LoanProduct(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("product_type"),
                rs.getLong("min_amount_pesewas"),
                rs.getLong("max_amount_pesewas"),
                rs.getInt("min_tenor_days"),
                rs.getInt("max_tenor_days"),
                rs.getInt("interest_rate_bps"),
                rs.getInt("origination_fee_bps"),
                rs.getInt("penalty_rate_bps"),
                rs.getInt("daily_sweep_bps"),
                rs.getString("status")
        )).optional();
    }

    @Transactional
    public LoanProduct createProduct(
            String code, String name, String productType,
            long minAmountPesewas, long maxAmountPesewas,
            int minTenorDays, int maxTenorDays,
            int interestRateBps, int originationFeeBps,
            int penaltyRateBps, int dailySweepBps
    ) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO loan_product
            (id, code, name, product_type, min_amount_pesewas, max_amount_pesewas,
             min_tenor_days, max_tenor_days, interest_rate_bps, origination_fee_bps,
             penalty_rate_bps, daily_sweep_bps, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
        """).params(id, code, name, productType, minAmountPesewas, maxAmountPesewas,
                minTenorDays, maxTenorDays, interestRateBps, originationFeeBps,
                penaltyRateBps, dailySweepBps)
        .update();

        return new LoanProduct(id, code, name, productType, minAmountPesewas, maxAmountPesewas,
                minTenorDays, maxTenorDays, interestRateBps, originationFeeBps,
                penaltyRateBps, dailySweepBps, "ACTIVE");
    }
}
