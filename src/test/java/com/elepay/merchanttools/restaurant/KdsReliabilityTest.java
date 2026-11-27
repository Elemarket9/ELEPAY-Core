package com.elepay.merchanttools.restaurant;

import com.elepay.PostgresTestBase;
import com.elepay.merchanttools.inventory.ProductCatalogService;
import com.elepay.merchanttools.pos.PosOrderService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThat;

/** What a busy Ghanaian kitchen does to a KDS: flaky networks, several screens, power cuts, printers. */
class KdsReliabilityTest extends PostgresTestBase {
    @Autowired KdsService kds;
    @Autowired KitchenStationService stations;
    @Autowired KdsPrintService printing;
    @Autowired PosOrderService orders;
    @Autowired ProductCatalogService catalog;
    @Autowired JdbcClient db;

    UUID merchantId;

    @BeforeEach
    void merchant() {
        merchantId = UUID.randomUUID();
        db.sql("""
            INSERT INTO merchant (id, legal_name, trading_name, kind, status, contact_phone,
                                  merchant_code, fee_bps, fee_fixed, settlement_delay_hours, created_by)
            VALUES (:id, 'Auntie Muni Chop Bar', 'Auntie Muni', 'merchant', 'active', '0244000000',
                    :code, 0, 0, 24, 'system')
            """).param("id", merchantId)
                .param("code", String.format("%06d", (int) (Math.random() * 900000 + 100000))).update();
    }

    private record Order(UUID orderId, List<KdsService.KdsItemInput> items) {}

    private Order order(UUID... variants) {
        List<PosOrderService.OrderItemInput> in = new ArrayList<>();
        String[] names = {"Waakye with Wele", "Sobolo", "Banku and Tilapia", "Kelewele"};
        for (int i = 0; i < Math.max(1, variants.length); i++) {
            UUID v = variants.length == 0 ? null : variants[i];
            in.add(new PosOrderService.OrderItemInput(v, names[i % names.length], BigDecimal.ONE, 3000L, 0L, 0L,
                    "[]", null, "MAIN", null));
        }
        var o = orders.createOrder(merchantId, null, null, null, null, in, 0L, 0L, 0L, "RESTAURANT");
        var items = orders.listOrderItems(o.id()).stream()
                .map(i -> new KdsService.KdsItemInput(i.id(), i.itemName(), i.quantity(), "extra shito", "MAIN"))
                .toList();
        return new Order(o.id(), items);
    }

    private KdsService.KdsTicket ticket(Order o, String clientRef) {
        return kds.createTicket(merchantId, o.orderId(), null, DiningMode.TAKEOUT, "Ama", null, o.items(), null, clientRef);
    }

    @Test
    @DisplayName("Order resent after a network drop does not reach the kitchen twice")
    void resentOrderCreatesOneTicket() {
        Order o = order();
        var first = ticket(o, "pos-7f3a-0001");
        var again = ticket(o, "pos-7f3a-0001");
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(kds.listTicketItems(first.id())).hasSize(1);
        assertThat(db.sql("SELECT count(*) FROM restaurant_kds_ticket WHERE merchant_id = :m")
                .param("m", merchantId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("Order numbers count up per merchant each day, for calling out pickups")
    void dailyOrderNumbers() {
        assertThat(ticket(order(), "a").ticketNumber()).isEqualTo(1);
        assertThat(ticket(order(), "b").ticketNumber()).isEqualTo(2);
        assertThat(ticket(order(), null).ticketNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("Two screens bumping the same ticket at once move it one stage, not two")
    void concurrentBumpsDoNotSkipStages() throws Exception {
        var t = ticket(order(), null);
        List<KdsService.BumpResult> results = race(2, i -> kds.advanceTicket(t.id(), "NEW", "screen-" + i));
        assertThat(results.stream().filter(KdsService.BumpResult::applied)).hasSize(1);
        assertThat(results.stream().filter(r -> !r.applied()).findFirst().orElseThrow().reason()).isEqualTo("STATUS_CHANGED");
        assertThat(kds.getTicket(t.id()).orElseThrow().status()).isEqualTo("PREPARING");
    }

    @Test
    @DisplayName("Legacy bump bar under concurrency never skips or loses a stage")
    void legacyConcurrentBumpsAreSerialised() throws Exception {
        var t = ticket(order(), null);
        race(2, i -> kds.bumpTicket(t.id()));
        var after = kds.getTicket(t.id()).orElseThrow();
        assertThat(after.status()).isEqualTo("READY");
        assertThat(after.startedAt()).isNotNull();
        assertThat(after.readyAt()).isNotNull();
    }

    @Test
    @DisplayName("A bump replayed from the offline queue is applied exactly once")
    void replayedActionAppliedOnce() {
        var t = ticket(order(), null);
        var first = kds.advanceTicket(t.id(), "NEW", "act-42");
        var replay = kds.advanceTicket(t.id(), "NEW", "act-42");
        assertThat(first.applied()).isTrue();
        assertThat(replay.applied()).isFalse();
        assertThat(replay.reason()).isEqualTo("ALREADY_APPLIED");
        assertThat(replay.view().ticket().status()).isEqualTo("PREPARING");
    }

    @Test
    @DisplayName("Items go to their station; the ticket is ready only when every station is done")
    void stationsRouteAndGateReadiness() {
        var kitchen = stations.create(merchantId, new KitchenStationService.StationInput(
                "Kitchen", null, true, KitchenStationService.Output.SCREEN, null, null));
        var drinks = stations.create(merchantId, new KitchenStationService.StationInput(
                "Drinks", null, false, KitchenStationService.Output.SCREEN, null, null));
        var cat = catalog.createCategory(merchantId, "Drinks");
        var product = catalog.createProduct(merchantId, cat.id(), "Sobolo", null, null);
        var sobolo = catalog.createVariant(merchantId, product.id(), "SOB-" + UUID.randomUUID().toString().substring(0, 6),
                null, "500ml", "{}", 500L, 1000L, false);
        stations.route(merchantId, drinks.id(), cat.id(), null);

        var food = catalog.createProduct(merchantId, null, "Waakye", null, null);
        var waakye = catalog.createVariant(merchantId, food.id(), "WAK-" + UUID.randomUUID().toString().substring(0, 6),
                null, "Plate", "{}", 1500L, 3000L, false);

        var t = ticket(order(waakye.id(), sobolo.id()), null);
        var items = kds.listTicketItems(t.id());
        assertThat(items).extracting(KdsService.KdsItem::stationId)
                .containsExactlyInAnyOrder(kitchen.id(), drinks.id());

        assertThat(kds.advanceStation(t.id(), drinks.id(), "NEW", null).applied()).isTrue();
        assertThat(kds.advanceStation(t.id(), drinks.id(), "PREPARING", null).view().ticket().status())
                .isEqualTo("PREPARING");
        kds.advanceStation(t.id(), kitchen.id(), "NEW", null);
        var done = kds.advanceStation(t.id(), kitchen.id(), "PREPARING", null);
        assertThat(done.view().ticket().status()).isEqualTo("READY");
        assertThat(done.view().ticket().readyAt()).isNotNull();

        // Each screen sees only its own station's tickets and items.
        var drinksView = kds.sync(merchantId, 0, drinks.id());
        assertThat(drinksView.tickets()).singleElement()
                .satisfies(v -> assertThat(v.items()).extracting(KdsService.KdsItem::stationId).containsOnly(drinks.id()));
    }

    @Test
    @DisplayName("A screen that was offline catches up on new, bumped and cancelled tickets")
    void syncCatchesUpAfterOutage() {
        var a = ticket(order(), null);
        var b = ticket(order(), null);
        var start = kds.sync(merchantId, 0, null);
        assertThat(start.tickets()).extracting(v -> v.ticket().id()).containsExactly(a.id(), b.id());

        // Screen goes offline. Meanwhile: one bumped, one cancelled, one new order.
        kds.advanceTicket(a.id(), "NEW", null);
        kds.cancel(b.id(), null, "Customer left");
        var c = ticket(order(), null);

        var caughtUp = kds.sync(merchantId, start.cursor(), null);
        assertThat(caughtUp.cursor()).isGreaterThan(start.cursor());
        var byId = caughtUp.tickets().stream().collect(java.util.stream.Collectors.toMap(v -> v.ticket().id(), v -> v.ticket()));
        assertThat(byId.get(a.id()).status()).isEqualTo("PREPARING");
        assertThat(byId.get(b.id()).status()).isEqualTo("CANCELLED");
        assertThat(byId.get(b.id()).notes()).contains("Customer left");
        assertThat(byId.get(c.id()).status()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("Printer stations get a ticket; only one device prints it; failures retry then alert")
    void printJobsClaimedOnceAndRetried() {
        var grill = stations.create(merchantId, new KitchenStationService.StationInput(
                "Grill", null, true, KitchenStationService.Output.PRINTER, "tcp://192.168.1.50:9100", 32));
        var t = ticket(order(), null);

        var job = printing.claimNext(merchantId, "kitchen-tablet").orElseThrow();
        assertThat(job.stationId()).isEqualTo(grill.id());
        assertThat(job.printerAddress()).isEqualTo("tcp://192.168.1.50:9100");
        assertThat(job.content()).contains("GRILL").contains("#" + String.format("%03d", t.ticketNumber()))
                .contains("1 x Waakye with Wele").contains("+ extra shito").contains("TAKEOUT");
        assertThat(job.content().lines()).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(32));
        assertThat(printing.claimNext(merchantId, "cashier-pos")).isEmpty();

        // Paper jam: the job goes back to the queue and is retried, by any device.
        assertThat(printing.report(job.id(), "kitchen-tablet", false, "Paper out").status()).isEqualTo("PENDING");
        var retry = printing.claimNext(merchantId, "cashier-pos").orElseThrow();
        assertThat(retry.id()).isEqualTo(job.id());
        // A late report from the first device must not override the second one's claim.
        assertThat(printing.report(job.id(), "kitchen-tablet", true, null).status()).isEqualTo("CLAIMED");
        assertThat(printing.report(job.id(), "cashier-pos", true, null).status()).isEqualTo("PRINTED");

        // Cancelling prints a VOID slip at the station that printed the order.
        kds.cancel(t.id(), null, null);
        var voidSlip = printing.claimNext(merchantId, "cashier-pos").orElseThrow();
        assertThat(voidSlip.kind()).isEqualTo("VOID");
        assertThat(voidSlip.content()).contains("VOID");
    }

    @Test
    @DisplayName("A failing printer stops retrying after five attempts and is reported to the screens")
    void failingPrinterIsReported() {
        stations.create(merchantId, new KitchenStationService.StationInput(
                "Kitchen", null, true, KitchenStationService.Output.PRINTER, "bt://AA:BB:CC:DD:EE:FF", null));
        ticket(order(), null);
        for (int i = 0; i < KdsPrintService.MAX_ATTEMPTS; i++) {
            var job = printing.claimNext(merchantId, "tablet").orElseThrow();
            printing.report(job.id(), "tablet", false, "Bluetooth printer not found");
        }
        assertThat(printing.claimNext(merchantId, "tablet")).isEmpty();
        assertThat(kds.sync(merchantId, 0, null).printerFailures()).singleElement()
                .satisfies(f -> assertThat(f.lastError()).isEqualTo("Bluetooth printer not found"));
    }

    @Test
    @DisplayName("When a station's screen is off (power cut, dead tablet), its tickets print instead")
    void deadScreenFallsBackToPrinter() {
        var kitchen = stations.create(merchantId, new KitchenStationService.StationInput(
                "Kitchen", null, true, KitchenStationService.Output.SCREEN, "tcp://10.0.0.20", null));
        ticket(order(), null);   // screen has never synced: treated as down
        var fallback = printing.claimNext(merchantId, "pos").orElseThrow();
        assertThat(fallback.kind()).isEqualTo("FALLBACK");
        assertThat(fallback.content()).contains("SCREEN OFFLINE");
        printing.report(fallback.id(), "pos", true, null);

        kds.sync(merchantId, 0, kitchen.id());   // screen is back and syncing
        ticket(order(), null);
        assertThat(printing.claimNext(merchantId, "pos")).isEmpty();
    }

    @Test
    @DisplayName("Long dish names wrap to narrow 58mm paper")
    void ticketTextWraps() {
        String text = KitchenTicketText.render(new KitchenTicketText.Ticket("NEW", "Kitchen", 7, "DINE_IN", "T-05",
                "Kwame", null, "Allergic to groundnuts, please use separate pan",
                List.of(new KitchenTicketText.Line(new BigDecimal("2.000"),
                        "Assorted Fried Rice with Grilled Chicken and Shito", "no pepper, extra salad", "MAIN"))), 32);
        assertThat(text.lines()).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(32));
        assertThat(text).contains("#007").contains("TABLE T-05").contains("2 x Assorted").contains("groundnuts");
    }

    @Test
    @DisplayName("Phase 0 Bug #2: Voiding an order cancels all open KDS tickets and enqueues VOID print slips")
    void voidOrderCancelsAllOpenKdsTicketsAndEnqueuesVoidSlips() {
        stations.create(merchantId, new KitchenStationService.StationInput(
                "Grill", null, true, KitchenStationService.Output.PRINTER, "tcp://192.168.1.51:9100", 42));

        Order o = order();
        var tNew = ticket(o, "void-test-new:" + o.orderId());
        var tPrep = ticket(o, "void-test-prep:" + o.orderId());
        var tServed = ticket(o, "void-test-served:" + o.orderId());

        kds.advanceTicket(tPrep.id(), "NEW", "prep-" + tPrep.id());
        kds.advanceTicket(tServed.id(), "NEW", "s1-" + tServed.id());
        kds.advanceTicket(tServed.id(), "PREPARING", "s2-" + tServed.id());
        kds.advanceTicket(tServed.id(), "READY", "s3-" + tServed.id());

        // Claim and mark all 3 initial NEW print jobs as printed
        for (int i = 0; i < 3; i++) {
            var job = printing.claimNext(merchantId, "pos-1").orElseThrow();
            assertThat(job.kind()).isEqualTo("NEW");
            printing.report(job.id(), "pos-1", true, null);
        }
        assertThat(printing.claimNext(merchantId, "pos-1")).isEmpty();

        // Void the pending order
        var voided = orders.voidOrder(o.orderId(), "cashier-1");
        assertThat(voided.status()).isEqualTo("VOIDED");

        // Open tickets (NEW and PREPARING) must be CANCELLED with "Order voided" and cancelled items
        var afterNew = kds.getTicket(tNew.id()).orElseThrow();
        var afterPrep = kds.getTicket(tPrep.id()).orElseThrow();
        var afterServed = kds.getTicket(tServed.id()).orElseThrow();
        assertThat(afterNew.status()).isEqualTo("CANCELLED");
        assertThat(afterNew.notes()).contains("Order voided");
        assertThat(kds.listTicketItems(tNew.id())).allSatisfy(it -> assertThat(it.itemStatus()).isEqualTo("CANCELLED"));

        assertThat(afterPrep.status()).isEqualTo("CANCELLED");
        assertThat(afterPrep.notes()).contains("Order voided");
        assertThat(kds.listTicketItems(tPrep.id())).allSatisfy(it -> assertThat(it.itemStatus()).isEqualTo("CANCELLED"));

        // Already-SERVED ticket stays SERVED
        assertThat(afterServed.status()).isEqualTo("SERVED");

        // Exactly two VOID slips must be enqueued for the two cancelled tickets
        var voidSlip1 = printing.claimNext(merchantId, "pos-1").orElseThrow();
        assertThat(voidSlip1.kind()).isEqualTo("VOID");
        assertThat(voidSlip1.content()).contains("VOID");
        printing.report(voidSlip1.id(), "pos-1", true, null);

        var voidSlip2 = printing.claimNext(merchantId, "pos-1").orElseThrow();
        assertThat(voidSlip2.kind()).isEqualTo("VOID");
        assertThat(voidSlip2.content()).contains("VOID");
        printing.report(voidSlip2.id(), "pos-1", true, null);

        assertThat(printing.claimNext(merchantId, "pos-1")).isEmpty();

        // Repeated voidOrder is idempotent and enqueues no extra VOID slips
        var voidedAgain = orders.voidOrder(o.orderId(), "cashier-1");
        assertThat(voidedAgain.status()).isEqualTo("VOIDED");
        assertThat(printing.claimNext(merchantId, "pos-1")).isEmpty();

        // Voiding an order with zero KDS tickets succeeds cleanly
        Order noTicketOrder = order();
        assertThat(orders.voidOrder(noTicketOrder.orderId(), "cashier-1").status()).isEqualTo("VOIDED");
    }

    private <T> List<T> race(int n, java.util.function.IntFunction<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int id = i;
            futures.add(pool.submit((Callable<T>) () -> { go.await(); return action.apply(id); }));
        }
        go.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) out.add(f.get());
        pool.shutdown();
        return out;
    }
}
