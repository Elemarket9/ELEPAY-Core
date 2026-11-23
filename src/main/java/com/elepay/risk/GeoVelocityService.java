package com.elepay.risk;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Algorithm 027: Geo-velocity checks.
 * Calculates great-circle distance (Haversine formula), detects impossible travel velocities,
 * and accounts for location accuracy uncertainty and VPN/proxy anomalies.
 */
@Service
public class GeoVelocityService {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double MAX_COMMERCIAL_SPEED_KMH = 850.0;
    private static final double MAX_DOMESTIC_GROUND_SPEED_KMH = 150.0;

    private final JdbcClient db;

    public GeoVelocityService(JdbcClient db) {
        this.db = db;
    }

    public record GeoLocation(
            double latitude,
            double longitude,
            Double accuracyRadiusMeters,
            String city,
            String country,
            String ipAddress,
            boolean isVpn,
            OffsetDateTime recordedAt
    ) {}

    public record GeoVelocityResult(
            double distanceKm,
            double timeDeltaHours,
            double speedKmh,
            boolean impossibleTravel,
            boolean vpnDetected,
            RiskDecision decision,
            String explanation
    ) {}

    public static double calculateHaversineDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);

        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0) +
                   Math.cos(phi1) * Math.cos(phi2) *
                   Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);

        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return EARTH_RADIUS_KM * c;
    }

    @Transactional
    public GeoVelocityResult checkAndRecord(String actorId, String referenceId, GeoLocation currentLocation) {
        if (currentLocation.latitude() < -90.0 || currentLocation.latitude() > 90.0 ||
            currentLocation.longitude() < -180.0 || currentLocation.longitude() > 180.0) {
            throw new IllegalArgumentException("Invalid geographic coordinates: lat " +
                    currentLocation.latitude() + ", lon " + currentLocation.longitude());
        }

        OffsetDateTime now = currentLocation.recordedAt() != null ? currentLocation.recordedAt() : OffsetDateTime.now();

        // Retrieve latest previous location
        Optional<GeoLocation> previousOpt = db.sql("""
            SELECT latitude, longitude, accuracy_radius_meters, city, country, ip_address, is_vpn, recorded_at
            FROM risk_geo_locations
            WHERE actor_id = :actorId
            ORDER BY recorded_at DESC
            LIMIT 1
        """)
                .param("actorId", actorId)
                .query((rs, rowNum) -> new GeoLocation(
                        rs.getDouble("latitude"),
                        rs.getDouble("longitude"),
                        rs.getObject("accuracy_radius_meters") != null ? rs.getDouble("accuracy_radius_meters") : null,
                        rs.getString("city"),
                        rs.getString("country"),
                        rs.getString("ip_address"),
                        rs.getBoolean("is_vpn"),
                        rs.getObject("recorded_at", OffsetDateTime.class)
                ))
                .optional();

        // Record current location
        db.sql("""
            INSERT INTO risk_geo_locations (
                id, actor_id, reference_id, latitude, longitude,
                accuracy_radius_meters, city, country, ip_address, is_vpn, recorded_at
            ) VALUES (
                :id, :actorId, :refId, :lat, :lon,
                :acc, :city, :country, :ip, :isVpn, :now
            )
        """)
                .param("id", UUID.randomUUID())
                .param("actorId", actorId)
                .param("refId", referenceId)
                .param("lat", currentLocation.latitude())
                .param("lon", currentLocation.longitude())
                .param("acc", currentLocation.accuracyRadiusMeters())
                .param("city", currentLocation.city())
                .param("country", currentLocation.country())
                .param("ip", currentLocation.ipAddress())
                .param("isVpn", currentLocation.isVpn())
                .param("now", now)
                .update();

        if (previousOpt.isEmpty()) {
            return new GeoVelocityResult(
                    0.0,
                    0.0,
                    0.0,
                    false,
                    currentLocation.isVpn(),
                    RiskDecision.ALLOW,
                    "First recorded location for actor"
            );
        }

        GeoLocation prev = previousOpt.get();
        double distanceKm = calculateHaversineDistanceKm(
                prev.latitude(), prev.longitude(),
                currentLocation.latitude(), currentLocation.longitude()
        );

        // Adjust for accuracy radius (subtract conservative radius in km)
        double acc1 = prev.accuracyRadiusMeters() != null ? prev.accuracyRadiusMeters() / 1000.0 : 0.0;
        double acc2 = currentLocation.accuracyRadiusMeters() != null ? currentLocation.accuracyRadiusMeters() / 1000.0 : 0.0;
        double effectiveDistanceKm = Math.max(0.0, distanceKm - (acc1 + acc2));

        long deltaMillis = Duration.between(prev.recordedAt(), now).toMillis();

        // 1. Handle negative time deltas (clock skew or out-of-order events)
        if (deltaMillis < 0) {
            if (effectiveDistanceKm > 30.0) {
                return new GeoVelocityResult(
                        distanceKm,
                        0.0,
                        0.0,
                        true,
                        currentLocation.isVpn(),
                        RiskDecision.REJECT,
                        String.format("Impossible out-of-order travel: location shift of %.1f km with negative time delta (%d ms)",
                                effectiveDistanceKm, deltaMillis)
                );
            }
            return new GeoVelocityResult(
                    distanceKm,
                    0.0,
                    0.0,
                    false,
                    currentLocation.isVpn(),
                    RiskDecision.ALLOW,
                    "Simultaneous or local reordered location event"
            );
        }

        // 2. Handle subsecond bursts (< 1000 ms) preventing division by zero and false velocity spikes
        double hours;
        double speedKmh;
        if (deltaMillis < 1000L) {
            if (effectiveDistanceKm > 15.0) {
                return new GeoVelocityResult(
                        distanceKm,
                        Math.max(0.0001, deltaMillis / 3_600_000.0),
                        9999.0,
                        true,
                        currentLocation.isVpn(),
                        RiskDecision.REJECT,
                        String.format("Impossible instantaneous travel: %.1f km in %d ms", effectiveDistanceKm, deltaMillis)
                );
            }
            // Cellular jitter / local burst: suppress artificial velocity spike
            hours = Math.max(0.0001, deltaMillis / 3_600_000.0);
            speedKmh = 0.0;
        } else {
            hours = deltaMillis / 3_600_000.0;
            speedKmh = effectiveDistanceKm / hours;
        }

        boolean impossible = false;
        RiskDecision decision = RiskDecision.ALLOW;
        String explanation;

        if (speedKmh == 0.0 && deltaMillis < 1000L) {
            explanation = String.format("Subsecond location update (cellular jitter / burst: %.1f km in %d ms)", effectiveDistanceKm, deltaMillis);
        } else if (currentLocation.isVpn() || prev.isVpn()) {
            if (speedKmh > MAX_COMMERCIAL_SPEED_KMH) {
                decision = RiskDecision.STEP_UP;
                explanation = String.format("Geo-velocity anomaly (%.1f km/h across %.1f km), likely VPN IP shift", speedKmh, distanceKm);
            } else {
                explanation = "Normal geo-velocity with VPN flag noted";
            }
        } else if (speedKmh > MAX_COMMERCIAL_SPEED_KMH && effectiveDistanceKm > 50.0) {
            impossible = true;
            decision = RiskDecision.REJECT;
            explanation = String.format("Impossible travel detected: %.1f km in %.2f hours (%.1f km/h exceeds commercial flight threshold)",
                    effectiveDistanceKm, hours, speedKmh);
        } else if (hours < 2.0 && speedKmh > MAX_DOMESTIC_GROUND_SPEED_KMH && effectiveDistanceKm > 30.0) {
            impossible = true;
            decision = RiskDecision.STEP_UP;
            explanation = String.format("Suspicious local velocity: %.1f km in %.2f hours (%.1f km/h exceeds ground travel threshold)",
                    effectiveDistanceKm, hours, speedKmh);
        } else {
            explanation = String.format("Plausible travel: %.1f km in %.2f hours (%.1f km/h)", distanceKm, hours, speedKmh);
        }

        return new GeoVelocityResult(
                distanceKm,
                hours,
                speedKmh,
                impossible,
                currentLocation.isVpn(),
                decision,
                explanation
        );
    }

    @Transactional
    public void recordCheckpoint(String entityId, double latitude, double longitude, String ipAddress, String city, String countryCode, OffsetDateTime recordedAt) {
        db.sql("""
            INSERT INTO geo_velocity_checkpoint (
                id, entity_id, latitude, longitude, ip_address, city, country_code, recorded_at, created_at
            ) VALUES (
                :id, :entityId, :lat, :lon, :ip, :city, :country, :recordedAt, now()
            )
        """)
                .param("id", UUID.randomUUID())
                .param("entityId", entityId)
                .param("lat", latitude)
                .param("lon", longitude)
                .param("ip", ipAddress)
                .param("city", city)
                .param("country", countryCode)
                .param("recordedAt", recordedAt != null ? recordedAt : OffsetDateTime.now())
                .update();
    }
}
