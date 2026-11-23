package com.elepay.merchanttools.restaurant;

import com.elepay.merchanttools.MerchantToolAccess;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.elepay.merchanttools.MerchantToolAccess.Resource.*;

@RestController
@RequestMapping("/v1/restaurant")
public class RestaurantController {
    private final RestaurantTableService tableService;
    private final ModifierService modifierService;
    private final KdsService kdsService;
    private final KitchenStationService stationService;
    private final KdsPrintService printService;
    private final KitchenDeviceService deviceService;
    private final RecipeCostingService recipeService;
    private final RestaurantSplitBillService splitBillService;
    private final MerchantToolAccess access;

    public RestaurantController(
            RestaurantTableService tableService,
            ModifierService modifierService,
            KdsService kdsService,
            RecipeCostingService recipeService,
            RestaurantSplitBillService splitBillService,
            KitchenStationService stationService,
            KdsPrintService printService,
            KitchenDeviceService deviceService,
            MerchantToolAccess access
    ) {
        this.deviceService = deviceService;
        this.stationService = stationService;
        this.printService = printService;
        this.access = access;
        this.tableService = tableService;
        this.modifierService = modifierService;
        this.kdsService = kdsService;
        this.recipeService = recipeService;
        this.splitBillService = splitBillService;
    }

    // Floor plan and table endpoints
    public record CreateFloorPlanRequest(UUID merchantId, String name, String layoutJson) {}

    @PostMapping("/floor-plans")
    public RestaurantTableService.FloorPlan createFloorPlan(@RequestBody CreateFloorPlanRequest req) {
        access.manage(req.merchantId());
        return tableService.createFloorPlan(req.merchantId(), req.name(), req.layoutJson());
    }

    @GetMapping("/floor-plans/merchant/{merchantId}")
    public List<RestaurantTableService.FloorPlan> listFloorPlans(@PathVariable UUID merchantId) {
        access.view(merchantId);
        return tableService.listFloorPlans(merchantId);
    }

    public record CreateTableRequest(UUID merchantId, UUID sectionId, String tableNumber, int capacity) {}

    @PostMapping("/tables")
    public RestaurantTableService.RestaurantTable createTable(@RequestBody CreateTableRequest req) {
        access.manage(req.merchantId());
        access.belongs(req.merchantId(), FLOOR_PLAN, req.sectionId());
        return tableService.createTable(req.merchantId(), req.sectionId(), req.tableNumber(), req.capacity());
    }

    @GetMapping("/tables/merchant/{merchantId}")
    public List<RestaurantTableService.RestaurantTable> listTables(@PathVariable UUID merchantId) {
        access.view(merchantId);
        return tableService.listTables(merchantId);
    }

    @PostMapping("/tables/{tableId}/status")
    public RestaurantTableService.RestaurantTable updateTableStatus(@PathVariable UUID tableId, @RequestParam TableStatus status) {
        access.manage(TABLE, tableId);
        return tableService.updateTableStatus(tableId, status);
    }

    @PostMapping("/tables/{tableId}/seat")
    public RestaurantTableService.RestaurantTable seatTable(@PathVariable UUID tableId, @RequestParam UUID orderId) {
        UUID merchantId = access.operate(TABLE, tableId);
        access.belongs(merchantId, ORDER, orderId);
        return tableService.seatTable(tableId, orderId);
    }

    @PostMapping("/tables/{tableId}/vacate")
    public RestaurantTableService.RestaurantTable vacateTable(@PathVariable UUID tableId, @RequestParam(defaultValue = "true") boolean markDirty) {
        access.manage(TABLE, tableId);
        return tableService.vacateTable(tableId, markDirty);
    }

    /** Staff can transfer a whole order to an available table; financial split is separate. */
    @PostMapping("/tables/{tableId}/move")
    public RestaurantTableService.RestaurantTable moveTable(@PathVariable UUID tableId,
            @RequestParam UUID destinationId) {
        UUID merchantId = access.operate(TABLE, tableId);
        access.belongs(merchantId, TABLE, destinationId);
        var table = tableService.getTable(tableId).orElseThrow(() -> com.elepay.common.ApiException.notFound("Table not found"));
        if (table.currentOrderId() == null)
            throw com.elepay.common.ApiException.conflict("source_table_empty", "Table has no open order");
        access.belongs(merchantId, ORDER, table.currentOrderId());
        return tableService.moveOrder(tableId, destinationId);
    }

    @PostMapping("/tables/{tableId}/close")
    public RestaurantTableService.RestaurantTable closeTable(@PathVariable UUID tableId) {
        access.operate(TABLE, tableId);
        return tableService.closeServedAndPaid(tableId);
    }

    // Modifier endpoints
    public record CreateModifierGroupRequest(UUID merchantId, String name, int minSelection, int maxSelection, boolean isRequired) {}

    @PostMapping("/modifiers/groups")
    public ModifierService.ModifierGroup createModifierGroup(@RequestBody CreateModifierGroupRequest req) {
        access.manage(req.merchantId());
        return modifierService.createModifierGroup(req.merchantId(), req.name(), req.minSelection(), req.maxSelection(), req.isRequired());
    }

    public record AddModifierOptionRequest(String name, long additionalPricePesewas) {}

    @PostMapping("/modifiers/groups/{groupId}/options")
    public ModifierService.ModifierOption addModifierOption(@PathVariable UUID groupId, @RequestBody AddModifierOptionRequest req) {
        access.manage(MODIFIER_GROUP, groupId);
        return modifierService.addModifierOption(groupId, req.name(), req.additionalPricePesewas());
    }

    @GetMapping("/modifiers/groups/merchant/{merchantId}")
    public List<ModifierService.ModifierGroup> listModifierGroups(@PathVariable UUID merchantId) {
        access.view(merchantId);
        return modifierService.listModifierGroups(merchantId);
    }

    // KDS Live Tickets & Bump Bar
    public record CreateTicketRequest(
            UUID merchantId, UUID orderId, UUID tableId, DiningMode diningMode,
            String serverName, String course, List<KdsService.KdsItemInput> items, String notes,
            String clientRef
    ) {}

    @PostMapping("/kds/tickets")
    public KdsService.KdsTicket createTicket(@RequestBody CreateTicketRequest req) {
        access.operate(req.merchantId());
        access.belongs(req.merchantId(), ORDER, req.orderId());
        if (req.tableId() != null) access.belongs(req.merchantId(), TABLE, req.tableId());
        if (req.items() == null || req.items().isEmpty())
            throw com.elepay.common.ApiException.badRequest("items_required", "A kitchen ticket needs at least one item");
        for (KdsService.KdsItemInput item : req.items()) access.belongs(req.merchantId(), ORDER_ITEM, item.orderItemId());
        return kdsService.createTicket(
                req.merchantId(), req.orderId(), req.tableId(), req.diningMode(),
                req.serverName(), req.course(), req.items(), req.notes(), req.clientRef()
        );
    }

    @GetMapping("/kds/tickets/active/{merchantId}")
    public List<KdsService.KdsTicket> getActiveTickets(@PathVariable UUID merchantId) {
        access.view(merchantId);
        return kdsService.getActiveTickets(merchantId);
    }

    @PostMapping("/kds/tickets/{ticketId}/bump")
    public KdsService.KdsTicket bumpTicket(@PathVariable UUID ticketId) {
        access.operate(KDS_TICKET, ticketId);
        return kdsService.bumpTicket(ticketId);
    }

    // Reliable kitchen screen API: change feed, expected-status bumps with action ids, stations, printing

    @GetMapping("/kds/sync")
    public KdsService.SyncResult sync(@RequestParam UUID merchantId, @RequestParam(defaultValue = "0") long since,
                                      @RequestParam(required = false) UUID stationId) {
        access.view(merchantId);
        if (stationId != null) access.belongs(merchantId, STATION, stationId);
        return kdsService.sync(merchantId, since, stationId);
    }

    public record AdvanceRequest(String from, String actionId) {}

    @PostMapping("/kds/tickets/{ticketId}/advance")
    public KdsService.BumpResult advanceTicket(@PathVariable UUID ticketId, @RequestBody AdvanceRequest req) {
        access.operate(KDS_TICKET, ticketId);
        return kdsService.advanceTicket(ticketId, req.from(), req.actionId());
    }

    @PostMapping("/kds/tickets/{ticketId}/stations/{stationId}/advance")
    public KdsService.BumpResult advanceStation(@PathVariable UUID ticketId, @PathVariable UUID stationId,
                                                @RequestBody AdvanceRequest req) {
        UUID merchantId = access.operate(KDS_TICKET, ticketId);
        access.belongs(merchantId, STATION, stationId);
        return kdsService.advanceStation(ticketId, stationId, req.from(), req.actionId());
    }

    public record CancelRequest(String actionId, String reason) {}

    @PostMapping("/kds/tickets/{ticketId}/cancel")
    public KdsService.BumpResult cancelTicket(@PathVariable UUID ticketId, @RequestBody CancelRequest req) {
        access.manage(KDS_TICKET, ticketId);
        return kdsService.cancel(ticketId, req.actionId(), req.reason());
    }

    @PostMapping("/kds/tickets/{ticketId}/reprint")
    public KdsPrintService.PrintJob reprint(@PathVariable UUID ticketId, @RequestParam UUID stationId) {
        UUID merchantId = access.operate(KDS_TICKET, ticketId);
        access.belongs(merchantId, STATION, stationId);
        return printService.reprint(ticketId, stationId);
    }

    public record CreateStationRequest(UUID merchantId, String name, UUID branchId, Boolean isDefault,
                                       KitchenStationService.Output output, String printerAddress, Integer paperWidth) {}

    @PostMapping("/kds/stations")
    public KitchenStationService.Station createStation(@RequestBody CreateStationRequest req) {
        access.manage(req.merchantId());
        access.branch(req.merchantId(), req.branchId());
        return stationService.create(req.merchantId(), new KitchenStationService.StationInput(
                req.name(), req.branchId(), Boolean.TRUE.equals(req.isDefault()), req.output(), req.printerAddress(), req.paperWidth()));
    }

    @GetMapping("/kds/stations/merchant/{merchantId}")
    public List<KitchenStationService.Station> listStations(@PathVariable UUID merchantId) {
        access.view(merchantId);
        return stationService.list(merchantId);
    }

    @PostMapping("/kds/stations/{stationId}/deactivate")
    public ResponseEntity<Void> deactivateStation(@PathVariable UUID stationId) {
        access.manage(STATION, stationId);
        stationService.deactivate(stationId);
        return ResponseEntity.ok().build();
    }

    public record RouteRequest(UUID categoryId, UUID variantId) {}

    @PostMapping("/kds/stations/{stationId}/routes")
    public ResponseEntity<Void> routeToStation(@PathVariable UUID stationId, @RequestBody RouteRequest req) {
        UUID merchantId = access.manage(STATION, stationId);
        if (req.categoryId() != null) access.belongs(merchantId, CATEGORY, req.categoryId());
        if (req.variantId() != null) access.belongs(merchantId, VARIANT, req.variantId());
        stationService.route(merchantId, stationId, req.categoryId(), req.variantId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/kds/print-jobs/claim")
    public ResponseEntity<KdsPrintService.PrintJob> claimPrintJob(@RequestParam UUID merchantId,
                                                                  @RequestParam String deviceId) {
        access.operate(merchantId);
        return printService.claimNext(merchantId, deviceId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    public record PrintResultRequest(String deviceId, Boolean printed, String error) {}

    @PostMapping("/kds/print-jobs/{jobId}/result")
    public KdsPrintService.PrintJob reportPrintResult(@PathVariable UUID jobId, @RequestBody PrintResultRequest req) {
        access.operate(PRINT_JOB, jobId);
        return printService.report(jobId, req.deviceId(), Boolean.TRUE.equals(req.printed()), req.error());
    }

    // Kitchen devices: pair once with a code, list, revoke

    public record PairingCodeRequest(UUID merchantId, UUID stationId) {}

    @PostMapping("/kds/devices/pairing-codes")
    public KitchenDeviceService.PairingCode createPairingCode(@RequestBody PairingCodeRequest req) {
        access.manage(req.merchantId());
        if (req.stationId() != null) access.belongs(req.merchantId(), STATION, req.stationId());
        return deviceService.createCode(req.merchantId(), req.stationId(), com.elepay.identity.Actor.current().subject());
    }

    @GetMapping("/kds/devices/merchant/{merchantId}")
    public List<com.elepay.identity.ApiKeys.KitchenDevice> listDevices(@PathVariable UUID merchantId) {
        access.manage(merchantId);
        return deviceService.list(merchantId);
    }

    @PostMapping("/kds/devices/{deviceId}/revoke")
    public ResponseEntity<Void> revokeDevice(@PathVariable UUID deviceId, @RequestParam UUID merchantId) {
        access.manage(merchantId);
        deviceService.revoke(merchantId, deviceId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/kds/items/{itemId}/bump")
    public ResponseEntity<Void> bumpItem(@PathVariable UUID itemId) {
        access.operate(KDS_ITEM, itemId);
        kdsService.bumpItem(itemId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/kds/orders/{orderId}/fire-course")
    public ResponseEntity<Void> fireCourse(@PathVariable UUID orderId, @RequestParam Course course) {
        access.operate(ORDER, orderId);
        kdsService.fireCourse(orderId, course);
        return ResponseEntity.ok().build();
    }

    // Recipe Costing & Stock Linkage
    public record CreateRecipeRequest(
            UUID merchantId, UUID menuItemVariantId, String recipeName,
            BigDecimal yieldQuantity, String preparationInstructions,
            List<RecipeCostingService.IngredientInput> ingredients
    ) {}

    @PostMapping("/recipes")
    public RecipeCostingService.Recipe createRecipe(@RequestBody CreateRecipeRequest req) {
        access.manage(req.merchantId());
        access.belongs(req.merchantId(), VARIANT, req.menuItemVariantId());
        if (req.ingredients() != null)
            for (RecipeCostingService.IngredientInput ing : req.ingredients())
                access.belongs(req.merchantId(), VARIANT, ing.ingredientVariantId());
        return recipeService.createRecipe(
                req.merchantId(), req.menuItemVariantId(), req.recipeName(),
                req.yieldQuantity(), req.preparationInstructions(), req.ingredients()
        );
    }

    @GetMapping("/recipes/{recipeId}/cost")
    public RecipeCostingService.RecipeCostSummary calculateRecipeCost(@PathVariable UUID recipeId) {
        access.view(RECIPE, recipeId);
        return recipeService.calculateRecipeCost(recipeId);
    }

    public record DeductRecipeStockRequest(UUID menuItemVariantId, BigDecimal dishQuantity, UUID warehouseId, UUID referenceId) {}

    @PostMapping("/recipes/deduct-stock")
    public ResponseEntity<Void> deductRecipeStock(@RequestBody DeductRecipeStockRequest req) {
        UUID merchantId = access.manage(VARIANT, req.menuItemVariantId());
        access.belongs(merchantId, WAREHOUSE, req.warehouseId());
        recipeService.deductRecipeStock(req.menuItemVariantId(), req.dishQuantity(), req.warehouseId(), req.referenceId());
        return ResponseEntity.ok().build();
    }

    // Restaurant Split Bill
    @PostMapping("/orders/{orderId}/split-by-seat")
    public RestaurantSplitBillService.RestaurantSplitResult splitBySeat(@PathVariable UUID orderId) {
        access.view(ORDER, orderId);
        return splitBillService.splitBySeat(orderId);
    }
}
