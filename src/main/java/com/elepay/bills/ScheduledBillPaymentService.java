package com.elepay.bills;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduledBillPaymentService {

    private final JdbcClient db;
    private final AirtimeDataPurchaseEngine airtimeEngine;
    private final UtilityPaymentService utilityService;

    public ScheduledBillPaymentService(JdbcClient db, AirtimeDataPurchaseEngine airtimeEngine, UtilityPaymentService utilityService) {
        this.db = db;
        this.airtimeEngine = airtimeEngine;
        this.utilityService = utilityService;
    }

    public record ScheduledPlan(
            UUID id,
            UUID customerId,
            String providerCode,
            String productCode,
            String billerAccount,
            long amountPesewas,
            String frequency, // DAILY, WEEKLY, MONTHLY
            Integer dayOfMonth,
            LocalDate nextRunDate,
            String status,
            OffsetDateTime lastExecutedAt,
            OffsetDateTime lastNotificationAt
    ) {}

    public record PreDebitNotice(
            UUID planId,
            UUID customerId,
            String providerCode,
            String billerAccount,
            long amountPesewas,
            LocalDate scheduledDebitDate,
            String noticeMessage
    ) {}

    @Transactional
    public ScheduledPlan createSchedule(
            UUID customerId, String providerCode, String productCode,
            String billerAccount, long amountPesewas, String frequency,
            Integer dayOfMonth, LocalDate firstRunDate
    ) {
        UUID id = UUID.randomUUID();
        LocalDate nextRun = firstRunDate != null ? firstRunDate : LocalDate.now().plusDays(1);

        db.sql("""
            INSERT INTO scheduled_bill_payment
            (id, customer_id, provider_code, product_code, biller_account, amount_pesewas,
             frequency, day_of_month, next_run_date, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
        """).params(id, customerId, providerCode, productCode, billerAccount, amountPesewas,
                frequency, dayOfMonth, nextRun)
        .update();

        return getSchedule(id);
    }

    public ScheduledPlan getSchedule(UUID id) {
        return db.sql("""
            SELECT id, customer_id, provider_code, product_code, biller_account,
                   amount_pesewas, frequency, day_of_month, next_run_date,
                   status, last_executed_at, last_notification_at
            FROM scheduled_bill_payment
            WHERE id = ?
        """).param(id)
        .query((rs, rowNum) -> new ScheduledPlan(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("provider_code"),
                rs.getString("product_code"),
                rs.getString("biller_account"),
                rs.getLong("amount_pesewas"),
                rs.getString("frequency"),
                (Integer) rs.getObject("day_of_month"),
                rs.getObject("next_run_date", LocalDate.class),
                rs.getString("status"),
                rs.getObject("last_executed_at", OffsetDateTime.class),
                rs.getObject("last_notification_at", OffsetDateTime.class)
        )).single();
    }

    public List<PreDebitNotice> generatePreDebitNotifications(LocalDate targetDate) {
        // Bills scheduled within the next 24h (next_run_date <= targetDate) that haven't received a notice recently
        List<ScheduledPlan> due = db.sql("""
            SELECT id, customer_id, provider_code, product_code, biller_account,
                   amount_pesewas, frequency, day_of_month, next_run_date,
                   status, last_executed_at, last_notification_at
            FROM scheduled_bill_payment
            WHERE status = 'ACTIVE'
              AND next_run_date <= ?
              AND (last_notification_at IS NULL OR last_notification_at < now() - INTERVAL '12 hours')
        """).param(targetDate)
        .query((rs, rowNum) -> new ScheduledPlan(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("provider_code"),
                rs.getString("product_code"),
                rs.getString("biller_account"),
                rs.getLong("amount_pesewas"),
                rs.getString("frequency"),
                (Integer) rs.getObject("day_of_month"),
                rs.getObject("next_run_date", LocalDate.class),
                rs.getString("status"),
                rs.getObject("last_executed_at", OffsetDateTime.class),
                rs.getObject("last_notification_at", OffsetDateTime.class)
        )).list();

        return due.stream().map(plan -> {
            db.sql("UPDATE scheduled_bill_payment SET last_notification_at = now() WHERE id = ?")
                    .param(plan.id()).update();

            String msg = "Reminder: Your recurring " + plan.providerCode() + " payment of " + BigDecimal.valueOf(plan.amountPesewas(), 2)
                    + " GHS for account " + plan.billerAccount() + " is scheduled for debit on " + plan.nextRunDate() + ".";
            return new PreDebitNotice(plan.id(), plan.customerId(), plan.providerCode(), plan.billerAccount(), plan.amountPesewas(), plan.nextRunDate(), msg);
        }).toList();
    }

    @Transactional
    public int executeDueScheduledBills(LocalDate asOfDate) {
        List<ScheduledPlan> ready = db.sql("""
            SELECT id, customer_id, provider_code, product_code, biller_account,
                   amount_pesewas, frequency, day_of_month, next_run_date,
                   status, last_executed_at, last_notification_at
            FROM scheduled_bill_payment
            WHERE status = 'ACTIVE' AND next_run_date <= ?
        """).param(asOfDate)
        .query((rs, rowNum) -> new ScheduledPlan(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("provider_code"),
                rs.getString("product_code"),
                rs.getString("biller_account"),
                rs.getLong("amount_pesewas"),
                rs.getString("frequency"),
                (Integer) rs.getObject("day_of_month"),
                rs.getObject("next_run_date", LocalDate.class),
                rs.getString("status"),
                rs.getObject("last_executed_at", OffsetDateTime.class),
                rs.getObject("last_notification_at", OffsetDateTime.class)
        )).list();

        int executedCount = 0;
        for (var plan : ready) {
            try {
                if ("MTN".equals(plan.providerCode()) || "TELECEL".equals(plan.providerCode()) || "AIRTELTIGO".equals(plan.providerCode())) {
                    airtimeEngine.purchaseAirtime(new AirtimeDataPurchaseEngine.AirtimePurchaseRequest(
                            plan.customerId(), plan.providerCode(), plan.billerAccount(), plan.amountPesewas()
                    ));
                } else if ("ECG".equals(plan.providerCode())) {
                    utilityService.payEcgPrepaid(plan.customerId(), plan.billerAccount(), plan.amountPesewas());
                } else if ("GWCL".equals(plan.providerCode())) {
                    utilityService.payGwclWater(plan.customerId(), plan.billerAccount(), plan.amountPesewas());
                }

                // Advance next run date
                LocalDate nextDate = switch (plan.frequency()) {
                    case "DAILY" -> plan.nextRunDate().plusDays(1);
                    case "WEEKLY" -> plan.nextRunDate().plusWeeks(1);
                    case "MONTHLY" -> plan.nextRunDate().plusMonths(1);
                    default -> plan.nextRunDate().plusMonths(1);
                };

                db.sql("""
                    UPDATE scheduled_bill_payment
                    SET next_run_date = ?, last_executed_at = now()
                    WHERE id = ?
                """).params(nextDate, plan.id()).update();

                executedCount++;
            } catch (Exception e) {
                // If payment fails (e.g. invalid account), log and continue
            }
        }
        return executedCount;
    }
}
