package com.elepay.merchanttools.restaurant;

import com.elepay.common.ApiException;
import com.elepay.merchanttools.MerchantToolAccess;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Restaurant reporting: real POS orders, stock and kitchen timings, never synthetic revenue. */
@RestController
@RequestMapping("/v1/restaurant/insights")
public class RestaurantInsightsController {
    private final MerchantToolAccess access;
    private final RestaurantInsightsService insights;

    public RestaurantInsightsController(MerchantToolAccess access, RestaurantInsightsService insights) {
        this.access = access;
        this.insights = insights;
    }

    private UUID authorizedBranch(UUID merchantId, UUID requestedBranch) {
        UUID scope = access.operatingBranch(merchantId);
        if (scope != null && requestedBranch != null && !scope.equals(requestedBranch))
            throw ApiException.forbidden("Branch reports are restricted to your assigned branch");
        UUID branch = scope != null ? scope : requestedBranch;
        access.branch(merchantId, branch);
        return branch;
    }

    @GetMapping("/{merchantId}")
    public RestaurantInsightsService.Report report(@PathVariable UUID merchantId,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate through) {
        access.operate(merchantId);
        return insights.report(merchantId, authorizedBranch(merchantId, branchId), from, through);
    }

    @GetMapping(value="/{merchantId}/csv", produces = "text/csv")
    public ResponseEntity<String> exportCsv(@PathVariable UUID merchantId,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate through) {
        access.operate(merchantId);
        String csv = insights.csv(merchantId, authorizedBranch(merchantId, branchId), from, through);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"elepay-pos-orders.csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(csv);
    }
}
