package com.elepay.merchanttools.restaurant;

import com.elepay.merchanttools.MerchantToolAccess;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.elepay.merchanttools.MerchantToolAccess.Resource.TABLE;

@RestController
@RequestMapping("/v1/restaurant/tables")
public class RestaurantTableController {
    private final RestaurantTableService tableService;
    private final MerchantToolAccess access;

    public RestaurantTableController(RestaurantTableService tableService, MerchantToolAccess access) {
        this.tableService = tableService;
        this.access = access;
    }

    @PostMapping("/{from}/move")
    public RestaurantTableService.MoveTableResult moveTable(
            @PathVariable UUID from,
            @RequestParam UUID to,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        UUID merchantId = access.owner(TABLE, from);
        var caller = access.operateAccess(merchantId);
        access.belongs(merchantId, TABLE, to);
        if (caller != null && caller.branchScoped()) {
            access.requireBranch(caller, tableService.tableBranchId(from));
            access.requireBranch(caller, tableService.tableBranchId(to));
        }
        String actor = caller != null && caller.actor() != null ? caller.actor().subject() : "system";
        return tableService.moveTable(from, to, actor, idempotencyKey);
    }

    @PostMapping("/{target}/merge")
    public RestaurantTableService.MergeTablesResult mergeTables(
            @PathVariable UUID target,
            @RequestParam UUID source
    ) {
        UUID merchantId = access.owner(TABLE, target);
        var caller = access.operateAccess(merchantId);
        access.belongs(merchantId, TABLE, source);
        if (caller != null && caller.branchScoped()) {
            access.requireBranch(caller, tableService.tableBranchId(target));
            access.requireBranch(caller, tableService.tableBranchId(source));
        }
        String actor = caller != null && caller.actor() != null ? caller.actor().subject() : "system";
        return tableService.mergeTables(target, source, actor);
    }

    @PostMapping("/{tableId}/print-bill")
    public RestaurantTableService.RestaurantTable printBill(@PathVariable UUID tableId) {
        var caller = access.operateAccess(TABLE, tableId);
        if (caller != null && caller.branchScoped()) {
            access.requireBranch(caller, tableService.tableBranchId(tableId));
        }
        return tableService.markBillPrinted(tableId);
    }
}
