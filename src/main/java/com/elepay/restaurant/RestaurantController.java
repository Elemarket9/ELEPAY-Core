package com.elepay.restaurant;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/restaurant")
public class RestaurantController {
    private final RestaurantTableService tableService;
    private final ModifierService modifierService;
    private final KdsService kdsService;
    private final RecipeCostingService recipeService;
    private final RestaurantSplitBillService splitBillService;

    public RestaurantController(
            RestaurantTableService tableService,
            ModifierService modifierService,
            KdsService kdsService,
            RecipeCostingService recipeService,
            RestaurantSplitBillService splitBillService
    ) {
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
        return tableService.createFloorPlan(req.merchantId(), req.name(), req.layoutJson());
    }

    @GetMapping("/floor-plans/merchant/{merchantId}")
    public List<RestaurantTableService.FloorPlan> listFloorPlans(@PathVariable UUID merchantId) {
        return tableService.listFloorPlans(merchantId);
    }

    public record CreateTableRequest(UUID merchantId, UUID sectionId, String tableNumber, int capacity) {}

    @PostMapping("/tables")
    public RestaurantTableService.RestaurantTable createTable(@RequestBody CreateTableRequest req) {
        return tableService.createTable(req.merchantId(), req.sectionId(), req.tableNumber(), req.capacity());
    }

    @GetMapping("/tables/merchant/{merchantId}")
    public List<RestaurantTableService.RestaurantTable> listTables(@PathVariable UUID merchantId) {
        return tableService.listTables(merchantId);
    }

    @PostMapping("/tables/{tableId}/status")
    public RestaurantTableService.RestaurantTable updateTableStatus(@PathVariable UUID tableId, @RequestParam TableStatus status) {
        return tableService.updateTableStatus(tableId, status);
    }

    @PostMapping("/tables/{tableId}/seat")
    public RestaurantTableService.RestaurantTable seatTable(@PathVariable UUID tableId, @RequestParam UUID orderId) {
        return tableService.seatTable(tableId, orderId);
    }

    @PostMapping("/tables/{tableId}/vacate")
    public RestaurantTableService.RestaurantTable vacateTable(@PathVariable UUID tableId, @RequestParam(defaultValue = "true") boolean markDirty) {
        return tableService.vacateTable(tableId, markDirty);
    }

    // Modifier endpoints
    public record CreateModifierGroupRequest(UUID merchantId, String name, int minSelection, int maxSelection, boolean isRequired) {}

    @PostMapping("/modifiers/groups")
    public ModifierService.ModifierGroup createModifierGroup(@RequestBody CreateModifierGroupRequest req) {
        return modifierService.createModifierGroup(req.merchantId(), req.name(), req.minSelection(), req.maxSelection(), req.isRequired());
    }

    public record AddModifierOptionRequest(String name, long additionalPricePesewas) {}

    @PostMapping("/modifiers/groups/{groupId}/options")
    public ModifierService.ModifierOption addModifierOption(@PathVariable UUID groupId, @RequestBody AddModifierOptionRequest req) {
        return modifierService.addModifierOption(groupId, req.name(), req.additionalPricePesewas());
    }

    @GetMapping("/modifiers/groups/merchant/{merchantId}")
    public List<ModifierService.ModifierGroup> listModifierGroups(@PathVariable UUID merchantId) {
        return modifierService.listModifierGroups(merchantId);
    }

    // KDS Live Tickets & Bump Bar
    public record CreateTicketRequest(
            UUID merchantId, UUID orderId, UUID tableId, DiningMode diningMode,
            String serverName, String course, List<KdsService.KdsItemInput> items, String notes
    ) {}

    @PostMapping("/kds/tickets")
    public KdsService.KdsTicket createTicket(@RequestBody CreateTicketRequest req) {
        return kdsService.createTicket(
                req.merchantId(), req.orderId(), req.tableId(), req.diningMode(),
                req.serverName(), req.course(), req.items(), req.notes()
        );
    }

    @GetMapping("/kds/tickets/active/{merchantId}")
    public List<KdsService.KdsTicket> getActiveTickets(@PathVariable UUID merchantId) {
        return kdsService.getActiveTickets(merchantId);
    }

    @PostMapping("/kds/tickets/{ticketId}/bump")
    public KdsService.KdsTicket bumpTicket(@PathVariable UUID ticketId) {
        return kdsService.bumpTicket(ticketId);
    }

    @PostMapping("/kds/items/{itemId}/bump")
    public ResponseEntity<Void> bumpItem(@PathVariable UUID itemId) {
        kdsService.bumpItem(itemId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/kds/orders/{orderId}/fire-course")
    public ResponseEntity<Void> fireCourse(@PathVariable UUID orderId, @RequestParam Course course) {
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
        return recipeService.createRecipe(
                req.merchantId(), req.menuItemVariantId(), req.recipeName(),
                req.yieldQuantity(), req.preparationInstructions(), req.ingredients()
        );
    }

    @GetMapping("/recipes/{recipeId}/cost")
    public RecipeCostingService.RecipeCostSummary calculateRecipeCost(@PathVariable UUID recipeId) {
        return recipeService.calculateRecipeCost(recipeId);
    }

    public record DeductRecipeStockRequest(UUID menuItemVariantId, BigDecimal dishQuantity, UUID warehouseId, UUID referenceId) {}

    @PostMapping("/recipes/deduct-stock")
    public ResponseEntity<Void> deductRecipeStock(@RequestBody DeductRecipeStockRequest req) {
        recipeService.deductRecipeStock(req.menuItemVariantId(), req.dishQuantity(), req.warehouseId(), req.referenceId());
        return ResponseEntity.ok().build();
    }

    // Restaurant Split Bill
    @PostMapping("/orders/{orderId}/split-by-seat")
    public RestaurantSplitBillService.RestaurantSplitResult splitBySeat(@PathVariable UUID orderId) {
        return splitBillService.splitBySeat(orderId);
    }
}
