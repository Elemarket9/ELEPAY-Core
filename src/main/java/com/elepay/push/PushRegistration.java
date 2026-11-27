package com.elepay.push;

import com.elepay.common.ApiException;
import com.elepay.identity.Actor;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Installation-scoped FCM registrations. A change of owner, merchant, station or token invalidates queued deliveries. */
@Service
public class PushRegistration {
    public record Request(UUID merchantId, UUID installationId, String app, String token, UUID stationId) {
        @Override public String toString() { return "PushRegistration.Request[redacted]"; }
    }
    public record Removal(UUID merchantId, UUID installationId, String app) {}
    private record Membership(String role, UUID branchId) {}
    private final JdbcClient db;
    private final boolean enabled;
    private final int leaseHours;

    public PushRegistration(JdbcClient db,
            @Value("${elepay.push.enabled:false}") boolean enabled,
            @Value("${elepay.push.registration-hours:168}") int leaseHours) {
        this.db = db;
        this.enabled = enabled;
        this.leaseHours = Math.max(1, Math.min(168, leaseHours));
    }

    @Transactional
    public void register(Request req, boolean kitchenRoute) {
        if (!enabled) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "push_disabled", "Push notifications are disabled");
        if (req == null || req.merchantId() == null || req.installationId() == null || req.app() == null
                || req.token() == null || !req.token().matches("[A-Za-z0-9_:.-]{20,4096}"))
            throw ApiException.badRequest("invalid_push_registration", "Invalid notification registration");
        Actor actor = Actor.current();
        UUID credential = null;
        UUID branch = null;
        UUID station = req.stationId();
        if (kitchenRoute) {
            credential = actor.apiKeyCredentialId();
            if (!"KDS".equals(req.app()) || !"kitchen".equals(actor.keyType()) || credential == null
                    || !req.merchantId().equals(actor.apiKeyMerchant()))
                throw ApiException.forbidden("Paired kitchen credential required");
            boolean valid = db.sql("""
                    SELECT EXISTS (
                        SELECT 1 FROM api_credential k
                        JOIN merchant m ON m.id = k.merchant_id AND m.status = 'active'
                        JOIN restaurant_kitchen_pairing p ON p.credential_id = k.id
                        WHERE k.id = :id AND k.merchant_id = :m AND k.revoked_at IS NULL
                          AND k.key_type = 'kitchen' AND p.merchant_id = :m AND p.used_at IS NOT NULL
                          AND (p.station_id IS NULL OR p.station_id = CAST(:station AS uuid))
                          AND (CAST(:station AS uuid) IS NULL OR EXISTS (
                              SELECT 1 FROM restaurant_kitchen_station s
                              WHERE s.id = CAST(:station AS uuid) AND s.merchant_id = :m AND s.active
                          )))
                    """).param("id", credential).param("m", req.merchantId())
                    .param("station", station).query(Boolean.class).single();
            if (!valid) throw ApiException.forbidden("Kitchen station is unavailable");
        } else {
            actor.requireUser();
            if (station != null) throw ApiException.badRequest("invalid_push_registration", "Station is only valid for kitchen devices");
            Membership member = db.sql("""
                    SELECT mm.role, mm.branch_id
                    FROM merchant_member mm JOIN merchant m ON m.id = mm.merchant_id
                    WHERE mm.merchant_id = :m AND mm.subject = :s AND m.status = 'active'
                    """).param("m", req.merchantId()).param("s", actor.subject())
                    .query((rs,n) -> new Membership(rs.getString(1), rs.getObject(2, UUID.class))).optional()
                    .orElseThrow(() -> ApiException.forbidden("Active merchant membership required"));
            boolean allowed = switch (req.app()) {
                case "WAITER" -> "waiter".equals(member.role()) && member.branchId() != null;
                case "POS" -> java.util.Set.of("owner","admin","cashier").contains(member.role());
                case "MERCHANT" -> java.util.Set.of("owner","admin","finance").contains(member.role());
                default -> false;
            };
            if (!allowed) throw ApiException.forbidden("Your merchant role cannot register this app");
            branch = member.branchId();
        }

        // Serialize installation changes and token rotation. No token is ever returned or logged.
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "push-install:" + req.app() + ":" + req.installationId())
                .query((rs,n) -> 1).single();
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "push-token:" + com.elepay.common.Idempotency.sha256(req.token()))
                .query((rs,n) -> 1).single();
        int renewed = db.sql("""
                UPDATE push_subscription SET expires_at = now() + make_interval(hours => :hours)
                WHERE app = :app AND installation_id = :i AND token = :t
                  AND subject = :s AND merchant_id = :m
                  AND branch_id IS NOT DISTINCT FROM CAST(:b AS uuid)
                  AND station_id IS NOT DISTINCT FROM CAST(:st AS uuid)
                  AND kitchen_credential_id IS NOT DISTINCT FROM CAST(:k AS uuid)
                """).param("hours", leaseHours).param("app", req.app()).param("i", req.installationId())
                .param("t", req.token()).param("s", actor.subject()).param("m", req.merchantId())
                .param("b", branch).param("st", station).param("k", credential).update();
        if (renewed != 0) return;

        db.sql("DELETE FROM push_subscription WHERE (app = :app AND installation_id = :i) OR token = :t")
                .param("app", req.app()).param("i", req.installationId()).param("t", req.token()).update();
        long count = db.sql("""
                SELECT count(*) FROM push_subscription
                WHERE merchant_id = :m AND subject = :s AND app = :app AND expires_at > now()
                """).param("m", req.merchantId()).param("s", actor.subject()).param("app", req.app()).query(Long.class).single();
        if (count >= 20) throw ApiException.tooManyRequests("Too many active notification installations");
        db.sql("""
                INSERT INTO push_subscription(id, merchant_id, subject, app, installation_id, token,
                    branch_id, station_id, kitchen_credential_id, expires_at)
                VALUES (:id,:m,:s,:app,:i,:t,:b,:st,:k,now() + make_interval(hours => :hours))
                """).param("id", UUID.randomUUID()).param("m", req.merchantId()).param("s", actor.subject())
                .param("app", req.app()).param("i", req.installationId()).param("t", req.token())
                .param("b", branch).param("st", station).param("k", credential).param("hours", leaseHours).update();
    }

    @Transactional
    public void unregister(Removal req, boolean kitchenRoute) {
        if (req == null || req.merchantId() == null || req.installationId() == null || req.app() == null)
            throw ApiException.badRequest("invalid_push_registration", "Invalid notification registration");
        Actor actor = Actor.current();
        if (kitchenRoute) {
            if (!"KDS".equals(req.app()) || !"kitchen".equals(actor.keyType())
                    || !req.merchantId().equals(actor.apiKeyMerchant()))
                throw ApiException.forbidden("Paired kitchen credential required");
        } else {
            actor.requireUser();
            if (!java.util.Set.of("WAITER","POS","MERCHANT").contains(req.app()))
                throw ApiException.forbidden("Invalid application");
        }
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "push-install:" + req.app() + ":" + req.installationId())
                .query((rs,n) -> 1).single();
        db.sql("""
                DELETE FROM push_subscription WHERE merchant_id = :m AND subject = :s
                  AND app = :app AND installation_id = :i
                """).param("m", req.merchantId()).param("s", actor.subject())
                .param("app", req.app()).param("i", req.installationId()).update();
    }
}
