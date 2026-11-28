package com.elepay.rails;

import com.elepay.common.AlertDispatcher;
import com.elepay.common.ScheduledJobLock;
import com.elepay.ledger.Ledger;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Synchronizes the live Custodian Bank Trust Account balance into
 * {@link CustodianBankBackstopService} and verifies Bank of Ghana safeguarding invariants.
 *
 * <p>No-op when no {@link CustodianBankGateway} bean is active (i.e. {@code elepay.custodian.h2h.enabled=false}).
 */
@Service
public class CustodianTrustBalanceSyncService {

    private static final Logger log = LoggerFactory.getLogger(CustodianTrustBalanceSyncService.class);

    public record SyncResult(
            CustodianBankGateway.TrustBalanceSnapshot snapshot,
            CustodianBankBackstopService.CustodianSafeguardReport safeguarding
    ) {}

    private final CustodianBankBackstopService backstop;
    private final CustodianBankGateway gateway;
    private final AlertDispatcher alerts;
    private final ScheduledJobLock jobLock;
    private final boolean syncEnabled;

    @Autowired
    public CustodianTrustBalanceSyncService(
            CustodianBankBackstopService backstop,
            @Autowired(required = false) CustodianBankGateway gateway,
            @Autowired(required = false) AlertDispatcher alerts,
            @Autowired(required = false) ScheduledJobLock jobLock,
            @Value("${elepay.custodian.h2h.sync-enabled:false}") boolean syncEnabled
    ) {
        this.backstop = backstop;
        this.gateway = gateway;
        this.alerts = alerts;
        this.jobLock = jobLock;
        this.syncEnabled = syncEnabled;
    }

    /**
     * Pulls the live trust balance from the Custodian Bank, updates the backstop snapshot,
     * and checks safeguarding against all ELEPAY customer + merchant + enterprise liabilities.
     */
    public Optional<SyncResult> syncPrimaryTrustBalance(String actor) {
        if (gateway == null) {
            return Optional.empty();
        }
        String effectiveActor = (actor == null || actor.isBlank()) ? "system:custodian-sync" : actor;
        CustodianBankGateway.TrustBalanceSnapshot snapshot =
                gateway.fetchTrustBalance(CustodianBankBackstopService.PRIMARY_TRUST_CODE, Ledger.GHS);

        backstop.recordReconciledBalance(
                snapshot.availableBalancePesewas(),
                snapshot.bankStatementReference(),
                effectiveActor
        );
        CustodianBankBackstopService.CustodianSafeguardReport report = backstop.verifyTrustSafeguarding(Ledger.GHS);

        if (!report.safeguardingSatisfied()) {
            long surplusOrDeficit = report.totalTrustAssetsPesewas() - report.totalSafeguardedLiabilitiesPesewas();
            log.error("Custodian Bank live safeguarding breach: trustAssets={} liabilities={} surplusOrDeficit={}",
                    report.totalTrustAssetsPesewas(),
                    report.totalSafeguardedLiabilitiesPesewas(),
                    surplusOrDeficit);
            if (alerts != null) {
                alerts.notifyHighSeverity(
                        "custodian_live_safeguarding_breach",
                        "trust:" + CustodianBankBackstopService.PRIMARY_TRUST_CODE,
                        Map.of(
                                "totalTrustAssetsPesewas", report.totalTrustAssetsPesewas(),
                                "totalSafeguardedLiabilitiesPesewas", report.totalSafeguardedLiabilitiesPesewas(),
                                "surplusOrDeficitPesewas", surplusOrDeficit,
                                "statementReference", String.valueOf(snapshot.bankStatementReference())
                        )
                );
            }
        }
        return Optional.of(new SyncResult(snapshot, report));
    }

    @Scheduled(fixedDelayString = "${elepay.custodian.h2h.balance-sync-ms:300000}")
    void scheduledSync() {
        if (!syncEnabled || gateway == null) {
            return;
        }
        Runnable task = () -> {
            try {
                syncPrimaryTrustBalance("system:custodian-sync");
            } catch (Exception e) {
                log.error("Scheduled Custodian Bank trust balance sync failed: {}", e.getMessage());
            }
        };
        if (jobLock != null) {
            jobLock.runIfLeader("custodian-trust-balance-sync", task);
        } else {
            task.run();
        }
    }
}
