package com.elepay.merchanttools.restaurant;

import com.elepay.common.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Kitchen stations (Kitchen, Grill, Drinks...) and which products each one prepares. */
@Service
public class KitchenStationService {
    /** A screen that has not synced for this long is treated as down, and its tickets are printed instead. */
    static final int SCREEN_STALE_SECONDS = 120;

    private final JdbcClient db;

    public KitchenStationService(JdbcClient db) {
        this.db = db;
    }

    public enum Output { SCREEN, PRINTER, BOTH }

    public record Station(
            UUID id, UUID merchantId, UUID branchId, String name, boolean isDefault, String output,
            String printerAddress, int paperWidth, boolean active, OffsetDateTime lastSeenAt, OffsetDateTime createdAt
    ) {}

    public record StationInput(String name, UUID branchId, boolean isDefault, Output output,
                               String printerAddress, Integer paperWidth) {}

    public record ResolvedRoute(UUID stationId, boolean autoReady) {}

    private static final String COLS = """
            id, merchant_id, branch_id, name, is_default, output, printer_address, paper_width,
            active, last_seen_at, created_at""";

    @Transactional
    public Station create(UUID merchantId, StationInput in) {
        if (in.name() == null || in.name().isBlank())
            throw ApiException.badRequest("station_name_required", "Station name is required");
        Output output = in.output() == null ? Output.SCREEN : in.output();
        String printer = in.printerAddress() == null || in.printerAddress().isBlank() ? null : in.printerAddress().strip();
        if (output != Output.SCREEN && printer == null)
            throw ApiException.badRequest("printer_required", "A printer address is required when the station prints");
        if (printer != null && !printer.matches("(tcp://[A-Za-z0-9.\\-]+(:\\d{1,5})?|bt://[0-9A-Fa-f:]{17}|usb://[\\w\\-./]+)"))
            throw ApiException.badRequest("printer_address_invalid",
                    "Printer address must look like tcp://192.168.1.50:9100, bt://AA:BB:CC:DD:EE:FF or usb://name");
        int width = in.paperWidth() == null ? 42 : in.paperWidth();
        if (width != 32 && width != 42 && width != 48)
            throw ApiException.badRequest("paper_width_invalid", "Paper width must be 32, 42 or 48 characters");
        if (in.isDefault()) {
            db.sql("""
                UPDATE restaurant_kitchen_station
                SET is_default = false
                WHERE merchant_id = :m AND is_default
                  AND branch_id IS NOT DISTINCT FROM CAST(:branch AS uuid)
                """).param("m", merchantId).param("branch", in.branchId()).update();
        }
        UUID id = UUID.randomUUID();
        try {
            db.sql("""
                INSERT INTO restaurant_kitchen_station
                    (id, merchant_id, branch_id, name, is_default, output, printer_address, paper_width)
                VALUES (:id, :m, :branch, :name, :def, :output, :printer, :width)
                """)
                    .param("id", id).param("m", merchantId).param("branch", in.branchId())
                    .param("name", in.name().strip()).param("def", in.isDefault()).param("output", output.name())
                    .param("printer", printer).param("width", width)
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("station_exists", "A station with that name already exists");
        }
        return get(id).orElseThrow();
    }

    public Optional<Station> get(UUID id) {
        return db.sql("SELECT " + COLS + " FROM restaurant_kitchen_station WHERE id = :id")
                .param("id", id).query(Station.class).optional();
    }

    public List<Station> list(UUID merchantId) {
        return list(merchantId, null);
    }

    /** Active stations for a merchant; when {@code branchId} is set, returns that branch's stations plus global ones. */
    public List<Station> list(UUID merchantId, UUID branchId) {
        if (branchId == null) {
            return db.sql("SELECT " + COLS + " FROM restaurant_kitchen_station WHERE merchant_id = :m AND active ORDER BY name")
                    .param("m", merchantId).query(Station.class).list();
        }
        return db.sql("SELECT " + COLS + """
             FROM restaurant_kitchen_station
            WHERE merchant_id = :m AND active AND (branch_id = :branch OR branch_id IS NULL)
            ORDER BY name
            """).param("m", merchantId).param("branch", branchId).query(Station.class).list();
    }

    @Transactional
    public void deactivate(UUID stationId) {
        db.sql("UPDATE restaurant_kitchen_station SET active = false, is_default = false WHERE id = :id")
                .param("id", stationId).update();
    }

    /** Route one product variant, or a whole category, to a station. Re-routing replaces the old route. */
    @Transactional
    public void route(UUID merchantId, UUID stationId, UUID categoryId, UUID variantId) {
        route(merchantId, stationId, categoryId, variantId, false);
    }

    /** Route one product variant, or a whole category, to a station (scoped to the station's branch), with optional auto-ready flag. */
    @Transactional
    public void route(UUID merchantId, UUID stationId, UUID categoryId, UUID variantId, boolean autoReady) {
        if ((categoryId == null) == (variantId == null))
            throw ApiException.badRequest("route_target_invalid", "Route either a category or a product variant");
        Station station = get(stationId)
                .filter(s -> merchantId.equals(s.merchantId()))
                .orElseThrow(() -> ApiException.notFound("Station not found"));
        UUID branchId = station.branchId();
        String column = variantId != null ? "variant_id" : "category_id";
        UUID target = variantId != null ? variantId : categoryId;
        db.sql("DELETE FROM restaurant_station_route WHERE merchant_id = :m AND branch_id IS NOT DISTINCT FROM CAST(:branch AS uuid) AND " + column + " = :t")
                .param("m", merchantId).param("branch", branchId).param("t", target).update();
        db.sql("INSERT INTO restaurant_station_route (id, merchant_id, branch_id, station_id, " + column + ", auto_ready) VALUES (:id, :m, :branch, :s, :t, :autoReady)")
                .param("id", UUID.randomUUID()).param("m", merchantId).param("branch", branchId)
                .param("s", stationId).param("t", target).param("autoReady", autoReady)
                .update();
    }

    /** Station for an ordered product: its own route, then its category's, then the default station. */
    public UUID resolve(UUID merchantId, UUID variantId) {
        return resolveRoute(merchantId, variantId, null).stationId();
    }

    /** Branch-aware station resolution for an ordered product. */
    public UUID resolve(UUID merchantId, UUID variantId, UUID branchId) {
        return resolveRoute(merchantId, variantId, branchId).stationId();
    }

    /**
     * Resolves station and {@code autoReady} flag for an ordered product in a branch.
     * Priority: branch variant -> global variant -> branch category -> global category -> branch default -> global default.
     */
    public ResolvedRoute resolveRoute(UUID merchantId, UUID variantId, UUID branchId) {
        if (variantId != null) {
            Optional<ResolvedRoute> routed = db.sql("""
                SELECT r.station_id, r.auto_ready
                FROM restaurant_station_route r
                JOIN restaurant_kitchen_station s ON s.id = r.station_id AND s.active
                WHERE r.merchant_id = :m
                  AND (r.branch_id = CAST(:branch AS uuid) OR r.branch_id IS NULL)
                  AND (r.variant_id = :v OR r.category_id = (
                        SELECT p.category_id FROM inventory_variant v
                        JOIN inventory_product p ON p.id = v.product_id WHERE v.id = :v))
                ORDER BY (r.variant_id IS NOT NULL) DESC, (r.branch_id IS NOT NULL) DESC
                LIMIT 1
                """).param("m", merchantId).param("branch", branchId).param("v", variantId)
                    .query(ResolvedRoute.class).optional();
            if (routed.isPresent()) return routed.get();
        }
        UUID defaultStation = db.sql("""
            SELECT id
            FROM restaurant_kitchen_station
            WHERE merchant_id = :m AND is_default AND active
              AND (branch_id = CAST(:branch AS uuid) OR branch_id IS NULL)
            ORDER BY (branch_id IS NOT NULL) DESC
            LIMIT 1
            """).param("m", merchantId).param("branch", branchId)
                .query(UUID.class).optional().orElse(null);
        return new ResolvedRoute(defaultStation, false);
    }

    /** Called whenever a station's screen syncs, so the system knows the screen is alive. */
    public void heartbeat(UUID stationId) {
        db.sql("UPDATE restaurant_kitchen_station SET last_seen_at = now() WHERE id = :id")
                .param("id", stationId).update();
    }
}
