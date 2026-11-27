package com.elepay.merchanttools.restaurant;

import com.elepay.PostgresTestBase;
import com.elepay.merchanttools.inventory.MovementType;
import com.elepay.merchanttools.inventory.ProductCatalogService;
import com.elepay.merchanttools.inventory.StockService;
import com.elepay.merchanttools.inventory.WarehouseService;
import com.elepay.merchanttools.pos.PosOrderService;
import com.elepay.merchanttools.pos.TenderType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

public class RestaurantKdsTest extends PostgresTestBase {

    @Autowired
    private RestaurantTableService tableService;

    @Autowired
    private ModifierService modifierService;

    @Autowired
    private KdsService kdsService;

    @Autowired
    private KitchenStationService stationService;

    @Autowired
    private KdsPrintService printService;

    @Autowired
    private RecipeCostingService recipeService;

    @Autowired
    private RestaurantSplitBillService splitBillService;

    @Autowired
    private PosOrderService posOrderService;

    @Autowired
    private WarehouseService warehouseService;

    @Autowired
    private ProductCatalogService catalogService;

    @Autowired
    private StockService stockService;

    @Autowired
    private JdbcClient db;

    private UUID merchantId;
    private WarehouseService.Warehouse warehouse;

    @BeforeEach
    void setup() {
        merchantId = UUID.randomUUID();
        int inserted = 0;
        for (int attempt = 0; attempt < 20 && inserted == 0; attempt++) {
            String code = String.format("%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100000, 1000000));
            inserted = db.sql("""
                INSERT INTO merchant (
                    id, legal_name, trading_name, kind, status, contact_phone,
                    merchant_code, fee_bps, fee_fixed, settlement_delay_hours, created_by
                ) VALUES (
                    :id, 'Buka Restaurant & Bar', 'Buka', 'merchant', 'active', '0243334455',
                    :code, 0, 0, 24, 'system'
                ) ON CONFLICT DO NOTHING
                """)
                .param("id", merchantId)
                .param("code", code)
                .update();
        }
        assertThat(inserted).isEqualTo(1);

        warehouse = warehouseService.createWarehouse(merchantId, null, "WH-REST-" + UUID.randomUUID().toString().substring(0, 5), "Restaurant Pantry", "Airport Residential");
    }

    @Test
    @DisplayName("Domain 4: Table and floor plan management, seating, and table status lifecycle")
    void testFloorPlansAndTables() {
        var plan = tableService.createFloorPlan(merchantId, "Main Dining Room", "{\"grid\":\"4x4\"}");
        assertThat(plan.id()).isNotNull();

        var table1 = tableService.createTable(merchantId, plan.id(), "T-12", 4);
        var table2 = tableService.createTable(merchantId, plan.id(), "T-14", 6);

        assertThat(table1.status()).isEqualTo("AVAILABLE");
        assertThat(table1.capacity()).isEqualTo(4);

        // Seat table with an order
        var posOrder = posOrderService.createOrder(
                merchantId, null, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Fried Yam", BigDecimal.ONE, 2000L, 0L, 0L, "[]", 1, "APPETIZER", null)),
                0L, 0L, 0L, "RESTAURANT"
        );
        var seated = tableService.seatTable(table1.id(), posOrder.id());
        assertThat(seated.status()).isEqualTo("OCCUPIED");
        assertThat(seated.currentOrderId()).isEqualTo(posOrder.id());

        // Vacate table and mark as DIRTY for busboy
        var vacated = tableService.vacateTable(table1.id(), true);
        assertThat(vacated.status()).isEqualTo("DIRTY");
        assertThat(vacated.currentOrderId()).isNull();

        // Busboy cleans table -> update to AVAILABLE
        var cleaned = tableService.updateTableStatus(table1.id(), TableStatus.AVAILABLE);
        assertThat(cleaned.status()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("Domain 4: Modifiers and options for customized dishes")
    void testItemModifiers() {
        var group = modifierService.createModifierGroup(merchantId, "Meat Choice", 1, 1, true);
        assertThat(group.name()).isEqualTo("Meat Choice");
        assertThat(group.isRequired()).isTrue();

        var opt1 = modifierService.addModifierOption(group.id(), "Grilled Tilapia", 1000L); // +10.00 GHS
        var opt2 = modifierService.addModifierOption(group.id(), "Goat Meat", 1500L); // +15.00 GHS

        var options = modifierService.listOptions(group.id());
        assertThat(options).hasSize(2);
        assertThat(options.get(0).additionalPricePesewas()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("Domain 4: Kitchen Display System (KDS) live order ticket state machine, bump bar, and course firing")
    void testKdsLiveTicketStateMachineAndBumpBar() {
        var plan = tableService.createFloorPlan(merchantId, "Terrace", "{}");
        var table = tableService.createTable(merchantId, plan.id(), "T-05", 2);

        var realOrder = posOrderService.createOrder(
                merchantId, null, null, null, null,
                List.of(
                        new PosOrderService.OrderItemInput(null, "Kelewele with Peanuts", BigDecimal.ONE, 2500L, 0L, 0L, "[]", 1, "APPETIZER", "Extra Peanuts"),
                        new PosOrderService.OrderItemInput(null, "Jollof Rice with Fried Chicken", BigDecimal.ONE, 4500L, 0L, 0L, "[]", 1, "MAIN", "Spicy")
                ),
                0L, 0L, 0L, "RESTAURANT"
        );
        var orderItems = posOrderService.listOrderItems(realOrder.id());

        var item1 = new KdsService.KdsItemInput(orderItems.get(0).id(), "Kelewele with Peanuts", BigDecimal.ONE, "Extra Peanuts", "APPETIZER");
        var item2 = new KdsService.KdsItemInput(orderItems.get(1).id(), "Jollof Rice with Fried Chicken", BigDecimal.ONE, "Spicy", "MAIN");

        // 1. Create KDS Ticket (status NEW)
        var ticket = kdsService.createTicket(
                merchantId, realOrder.id(), table.id(), DiningMode.DINE_IN,
                "Waiter Sam", "ALL", List.of(item1, item2), "Rush order"
        );
        assertThat(ticket.status()).isEqualTo("NEW");
        assertThat(ticket.urgencyLevel()).isEqualTo("NORMAL");

        var ticketItems = kdsService.listTicketItems(ticket.id());
        assertThat(ticketItems).hasSize(2);

        // 2. Kitchen Bump Bar Action: Bump 1 -> status transitions from NEW to PREPARING
        var bumpedToPrep = kdsService.bumpTicket(ticket.id());
        assertThat(bumpedToPrep.status()).isEqualTo("PREPARING");
        assertThat(bumpedToPrep.startedAt()).isNotNull();

        // 3. Kitchen Bump Bar Action: Bump 2 -> status transitions from PREPARING to READY
        var bumpedToReady = kdsService.bumpTicket(ticket.id());
        assertThat(bumpedToReady.status()).isEqualTo("READY");
        assertThat(bumpedToReady.readyAt()).isNotNull();

        // 4. Kitchen Bump Bar Action: Bump 3 -> status transitions from READY to SERVED
        var bumpedToServed = kdsService.bumpTicket(ticket.id());
        assertThat(bumpedToServed.status()).isEqualTo("SERVED");
        assertThat(bumpedToServed.servedAt()).isNotNull();

        // Active tickets query should now exclude SERVED ticket
        var active = kdsService.getActiveTickets(merchantId);
        assertThat(active.stream().noneMatch(t -> t.id().equals(ticket.id()))).isTrue();

        // 5. Course Firing test
        var realOrder2 = posOrderService.createOrder(
                merchantId, null, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Kelewele with Peanuts", BigDecimal.ONE, 2500L, 0L, 0L, "[]", 1, "APPETIZER", null)),
                0L, 0L, 0L, "RESTAURANT"
        );
        var orderItems2 = posOrderService.listOrderItems(realOrder2.id());
        var itemApp = new KdsService.KdsItemInput(orderItems2.get(0).id(), "Kelewele with Peanuts", BigDecimal.ONE, null, "APPETIZER");

        var ticket2 = kdsService.createTicket(
                merchantId, realOrder2.id(), table.id(), DiningMode.DINE_IN,
                "Waiter Sam", "APPETIZER", List.of(itemApp), "Appetizer course"
        );
        assertThat(ticket2.status()).isEqualTo("NEW");

        kdsService.fireCourse(realOrder2.id(), Course.APPETIZER);
        var firedTicket = kdsService.getTicket(ticket2.id()).orElseThrow();
        assertThat(firedTicket.status()).isEqualTo("PREPARING");
    }

    @Test
    @DisplayName("Domain 4: Food recipe ingredient costing and linked inventory stock deductions")
    void testRecipeCostingAndInventoryDeductions() {
        var category = catalogService.createCategory(merchantId, "Kitchen Ingredients");
        var prodRice = catalogService.createProduct(merchantId, category.id(), "Raw Jasmine Rice", "Grains", "Local");
        var ingRice = catalogService.createVariant(merchantId, prodRice.id(), "ING-RICE-KG", null, "1kg Rice", "{}", 2000L, 0L, true);

        var prodTom = catalogService.createProduct(merchantId, category.id(), "Tomato Paste", "Canned", "Gino");
        var ingTom = catalogService.createVariant(merchantId, prodTom.id(), "ING-TOM-TIN", null, "Tin 400g", "{}", 500L, 0L, true);

        // Stock up ingredients in restaurant warehouse
        stockService.adjustStock(warehouse.id(), ingRice.id(), BigDecimal.valueOf(50), MovementType.RECEIVED, null, "Stock");
        stockService.adjustStock(warehouse.id(), ingTom.id(), BigDecimal.valueOf(100), MovementType.RECEIVED, null, "Stock");

        // Menu item variant: "Gourmet Jollof Dish"
        var prodDish = catalogService.createProduct(merchantId, category.id(), "Gourmet Jollof Plate", "Prepared food", "Buka");
        var menuItemDish = catalogService.createVariant(merchantId, prodDish.id(), "MENU-JOLLOF", null, "Standard Plate", "{}", 0L, 5000L, false);

        // 1. Create Recipe: 1 portion requires 0.25 kg rice + 1 tin tomato paste
        var recipe = recipeService.createRecipe(
                merchantId, menuItemDish.id(), "Traditional Jollof Recipe", BigDecimal.ONE,
                "Simmer tomatoes, parboil rice, bake till smoky",
                List.of(
                        new RecipeCostingService.IngredientInput(ingRice.id(), new BigDecimal("0.25"), "KG"),
                        new RecipeCostingService.IngredientInput(ingTom.id(), new BigDecimal("1.00"), "TIN")
                )
        );
        assertThat(recipe.id()).isNotNull();

        // 2. Recipe Cost Calculation:
        // Rice: 0.25 kg * 2000 pesewas = 500 pesewas
        // Tomato: 1 tin * 500 pesewas = 500 pesewas
        // Total Recipe Cost = 1000 pesewas (10.00 GHS)
        var costSummary = recipeService.calculateRecipeCost(recipe.id());
        assertThat(costSummary.totalCostPesewas()).isEqualTo(1000L);
        assertThat(costSummary.costPerPortionPesewas()).isEqualTo(1000L);
        assertThat(costSummary.ingredients()).hasSize(2);

        // 3. Linked Inventory Deduction: When 4 portions of Jollof are prepared/served:
        // Expected rice deduction: 4 * 0.25 = 1.00 kg (Remaining: 50 - 1 = 49 kg)
        // Expected tomato deduction: 4 * 1.00 = 4.00 tins (Remaining: 100 - 4 = 96 tins)
        recipeService.deductRecipeStock(menuItemDish.id(), BigDecimal.valueOf(4), warehouse.id(), UUID.randomUUID());

        var stockRice = stockService.getOrCreateStock(warehouse.id(), ingRice.id());
        var stockTom = stockService.getOrCreateStock(warehouse.id(), ingTom.id());

        assertThat(stockRice.quantityOnHand()).isEqualByComparingTo("49.00");
        assertThat(stockTom.quantityOnHand()).isEqualByComparingTo("96.00");
    }

    @Test
    @DisplayName("Domain 4: Restaurant split bill by seat with proportional tax and tip allocation")
    void testRestaurantSplitBillBySeat() {
        // Create an order with tax and tip:
        // Seat 1: Banku & Tilapia @ 60.00 GHS (6000 pesewas)
        // Seat 2: Jollof & Beef @ 40.00 GHS (4000 pesewas)
        // Subtotal = 10,000 pesewas. Tax = 1,000 pesewas (10%), Tip = 1,000 pesewas (10%). Total = 12,000 pesewas.
        var order = posOrderService.createOrder(
                merchantId, null, null, null, null,
                List.of(
                        new PosOrderService.OrderItemInput(null, "Banku & Tilapia", BigDecimal.ONE, 6000L, 0L, 0L, "[]", 1, "MAIN", null),
                        new PosOrderService.OrderItemInput(null, "Jollof & Beef", BigDecimal.ONE, 4000L, 0L, 0L, "[]", 2, "MAIN", null)
                ),
                0L, 1000L, 1000L, "RESTAURANT"
        );

        var split = splitBillService.splitBySeat(order.id());
        assertThat(split.seatBills()).hasSize(2);

        var seat1 = split.seatBills().stream().filter(b -> b.seatNumber() == 1).findFirst().orElseThrow();
        var seat2 = split.seatBills().stream().filter(b -> b.seatNumber() == 2).findFirst().orElseThrow();

        // Seat 1 is 60% of items -> 6000 items + 600 tax + 600 tip = 7200 pesewas
        assertThat(seat1.itemsTotalPesewas()).isEqualTo(6000L);
        assertThat(seat1.allocatedTaxPesewas()).isEqualTo(600L);
        assertThat(seat1.allocatedTipPesewas()).isEqualTo(600L);
        assertThat(seat1.totalDuePesewas()).isEqualTo(7200L);

        // Seat 2 is 40% of items -> 4000 items + 400 tax + 400 tip = 4800 pesewas
        assertThat(seat2.itemsTotalPesewas()).isEqualTo(4000L);
        assertThat(seat2.allocatedTaxPesewas()).isEqualTo(400L);
        assertThat(seat2.allocatedTipPesewas()).isEqualTo(400L);
        assertThat(seat2.totalDuePesewas()).isEqualTo(4800L);

        // Sum of seat bills equals total order
        assertThat(seat1.totalDuePesewas() + seat2.totalDuePesewas()).isEqualTo(12000L);
    }

    @Test
    @DisplayName("Phase 0 Bug #1: Auto-created KDS ticket on PAID resolves tableId from seated table or waiter order fallback")
    void autoKdsTicketOnPaidResolvesTableIdFromSeatedTableAndWaiterFallback() {
        stationService.create(merchantId, new KitchenStationService.StationInput(
                "Main Kitchen", null, true, KitchenStationService.Output.PRINTER, "tcp://192.168.1.60:9100", 42));
        var plan = tableService.createFloorPlan(merchantId, "Dining Hall", "{}");
        var table12 = tableService.createTable(merchantId, plan.id(), "T-12", 4);
        var table14 = tableService.createTable(merchantId, plan.id(), "T-14", 2);

        // 1. Seated DINE_IN order: auto-KDS ticket on PAID must resolve table12.id() and print TABLE T-12
        var dineInOrder = posOrderService.createOrder(
                merchantId, null, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Jollof Rice", BigDecimal.ONE, 4500L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN"
        );
        tableService.seatTable(table12.id(), dineInOrder.id());
        posOrderService.addTenderPayment(dineInOrder.id(), TenderType.CASH, dineInOrder.totalPesewas(), "CASH-1");

        var seatedTickets = kdsService.getActiveTickets(merchantId).stream()
                .filter(t -> dineInOrder.id().equals(t.orderId())).toList();
        assertThat(seatedTickets).hasSize(1);
        assertThat(seatedTickets.get(0).tableId()).isEqualTo(table12.id());

        var slip = printService.claimNext(merchantId, "pos-1").orElseThrow();
        assertThat(slip.content()).contains("TABLE T-12");
        printService.report(slip.id(), "pos-1", true, null);

        // 2. Fallback to restaurant_waiter_order when restaurant_table.current_order_id is NULL
        var waiterFallbackOrder = posOrderService.createOrder(
                merchantId, null, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Waakye", BigDecimal.ONE, 3500L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN"
        );
        // Link a prior ticket FK row in restaurant_waiter_order while table14.current_order_id stays NULL
        db.sql("""
                INSERT INTO restaurant_waiter_order(order_id, merchant_id, table_id, waiter_subject, ticket_id)
                VALUES (:o, :m, :t, 'waiter-fallback', :k)
                """)
                .param("o", waiterFallbackOrder.id())
                .param("m", merchantId)
                .param("t", table14.id())
                .param("k", seatedTickets.get(0).id())
                .update();
        posOrderService.addTenderPayment(waiterFallbackOrder.id(), TenderType.CASH, waiterFallbackOrder.totalPesewas(), "CASH-2");

        var fallbackTickets = kdsService.getActiveTickets(merchantId).stream()
                .filter(t -> waiterFallbackOrder.id().equals(t.orderId())).toList();
        assertThat(fallbackTickets).hasSize(1);
        assertThat(fallbackTickets.get(0).tableId()).isEqualTo(table14.id());

        // 3. Unseated TAKEAWAY order: tableId stays null cleanly
        var takeawayOrder = posOrderService.createOrder(
                merchantId, null, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Meat Pie", BigDecimal.ONE, 1500L, 0L, 0L, "[]", null, "MAIN", null)),
                0L, 0L, 0L, "TAKEAWAY"
        );
        posOrderService.addTenderPayment(takeawayOrder.id(), TenderType.CASH, takeawayOrder.totalPesewas(), "CASH-3");

        var takeawayTickets = kdsService.getActiveTickets(merchantId).stream()
                .filter(t -> takeawayOrder.id().equals(t.orderId())).toList();
        assertThat(takeawayTickets).hasSize(1);
        assertThat(takeawayTickets.get(0).tableId()).isNull();
    }
}
