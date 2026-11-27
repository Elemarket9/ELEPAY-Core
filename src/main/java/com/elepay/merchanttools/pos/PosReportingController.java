package com.elepay.merchanttools.pos;

import com.elepay.identity.Actor;
import com.elepay.merchant.MerchantAccess;
import com.elepay.merchanttools.MerchantToolAccess;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 6 (Live Order Management) & Phase 7 (Owner Daily Report) endpoints under {@code /v1/pos}.
 * Enforces merchant ownership and strict branch-scope isolation for branch-scoped staff and paired POS terminals.
 */
@RestController
@RequestMapping("/v1/pos")
public class PosReportingController {
    private final MerchantToolAccess access;
    private final PosOrderQueryService orderQueryService;
    private final PosDailyReportService dailyReportService;

    public PosReportingController(
            MerchantToolAccess access,
            PosOrderQueryService orderQueryService,
            PosDailyReportService dailyReportService
    ) {
        this.access = access;
        this.orderQueryService = orderQueryService;
        this.dailyReportService = dailyReportService;
    }

    private record ResolvedScope(UUID merchantId, UUID branchId) {}

    /**
     * Phase 6: Cursor-paged live order list.
     * Supports filtering by branchId, status (PENDING|PARTIAL|PAID|VOIDED|OPEN), type (DINE_IN|TAKEAWAY|DELIVERY|STANDARD),
     * since (ISO-8601 timestamp), cursor, and limit (1..200, default 50).
     */
    @GetMapping("/orders")
    public PosOrderQueryService.LiveOrderPage listOrders(
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit
    ) {
        ResolvedScope scope = resolveScope(merchantId, branchId);
        return orderQueryService.listOrders(
                scope.merchantId(),
                scope.branchId(),
                status,
                type,
                since,
                cursor,
                limit
        );
    }

    /**
     * Phase 7: Owner daily report for an Africa/Accra business day.
     * Aggregates all branches when branchId is omitted and caller is not branch-scoped;
     * strictly locked to the caller's branch when branch-scoped.
     */
    @GetMapping("/reports/daily")
    public PosDailyReportService.DailyReport dailyReport(
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        ResolvedScope scope = resolveScope(merchantId, branchId);
        return dailyReportService.generateDailyReport(scope.merchantId(), scope.branchId(), date);
    }

    private ResolvedScope resolveScope(UUID requestedMerchantId, UUID requestedBranchId) {
        UUID merchantId = requestedMerchantId;
        if (merchantId == null) {
            Actor actor = Actor.current();
            if (actor.isApiKey()) {
                merchantId = actor.apiKeyMerchant();
            }
        }
        MerchantAccess.Access caller = access.viewAccess(merchantId);
        UUID effectiveBranchId;
        if (caller.branchScoped()) {
            if (requestedBranchId != null) {
                access.requireBranch(caller, requestedBranchId);
            }
            effectiveBranchId = caller.branchId();
        } else if (requestedBranchId != null) {
            access.branch(merchantId, requestedBranchId);
            effectiveBranchId = requestedBranchId;
        } else {
            effectiveBranchId = null;
        }
        return new ResolvedScope(merchantId, effectiveBranchId);
    }
}
