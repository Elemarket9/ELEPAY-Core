package com.elepay.wallet;

import com.elepay.common.ApiException;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class TransferLimits {
    private final JdbcClient db;
    private final long perTransfer;
    private final long perDay;
    private final int perMinute;

    TransferLimits(JdbcClient db,
                   @Value("${elepay.wallet.limits.transfer-pesewas:0}") long perTransfer,
                   @Value("${elepay.wallet.limits.daily-pesewas:0}") long perDay,
                   @Value("${elepay.wallet.limits.transfers-per-minute:0}") int perMinute) {
        if (perTransfer < 0 || perDay < 0 || perMinute < 0) {
            throw new IllegalArgumentException("Wallet transfer limits must be non-negative");
        }
        this.db = db;
        this.perTransfer = perTransfer;
        this.perDay = perDay;
        this.perMinute = perMinute;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void check(UUID sender, long amount) {
        if (perTransfer > 0 && amount > perTransfer) {
            throw ApiException.conflict("transfer_limit", "Amount exceeds the limit for one transfer");
        }
        // Hold the sender row until the matching wallet operation has been recorded.
        boolean active = db.sql("SELECT status = 'active' FROM customer WHERE id = :id FOR UPDATE")
                .param("id", sender).query(Boolean.class).optional().orElse(false);
        if (!active) throw ApiException.conflict("wallet_unavailable", "The sending wallet is unavailable");
        if (perDay == 0 && perMinute == 0) return;
        Usage usage = db.sql("""
                SELECT coalesce(sum(o.amount) FILTER (WHERE o.created_at >=
                           date_trunc('day', statement_timestamp(), 'Africa/Accra')
                           AND coalesce(p.status, o.status) NOT IN ('failed', 'returned', 'rejected')), 0),
                       count(*) FILTER (WHERE o.created_at >= statement_timestamp() - interval '1 minute')
                FROM wallet_operation o
                LEFT JOIN payout p ON p.id = o.related_id AND p.owner_type = 'customer' AND p.owner_id = o.customer_id
                WHERE o.customer_id = :sender
                  AND (o.kind = 'transfer_out' OR (o.kind = 'withdrawal' AND p.client_reference LIKE 'transfer-%'))
                  AND o.created_at >= least(date_trunc('day', statement_timestamp(), 'Africa/Accra'),
                                            statement_timestamp() - interval '1 minute')
                """).param("sender", sender)
                .query((row, index) -> new Usage(row.getBigDecimal(1), row.getLong(2))).single();
        if (perDay > 0 && usage.daily().add(BigDecimal.valueOf(amount)).compareTo(BigDecimal.valueOf(perDay)) > 0) {
            throw ApiException.conflict("daily_transfer_limit", "Amount exceeds the remaining daily transfer limit");
        }
        if (perMinute > 0 && usage.recent() >= perMinute) {
            throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "transfer_rate_limit", "Too many recent transfers. Wait a minute and try again.");
        }
    }

    private record Usage(BigDecimal daily, long recent) {}
}
