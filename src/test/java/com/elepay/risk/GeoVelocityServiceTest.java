package com.elepay.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.UUID;

@DisplayName("Algorithm 027: Geo-Velocity Checks and Subsecond Clamping")
class GeoVelocityServiceTest extends PostgresTestBase {

    @Autowired GeoVelocityService geoVelocityService;

    @Test
    @DisplayName("Algorithm 027: Normal travel is allowed with valid speed")
    void testNormalTravelAllowed() {
        String actor = "actor-normal-" + UUID.randomUUID();
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(2);

        // Location 1: Accra (5.6037, -0.1870)
        var res1 = geoVelocityService.checkAndRecord(actor, "ref-1",
                new GeoVelocityService.GeoLocation(5.6037, -0.1870, 50.0, "Accra", "GH", "102.176.0.1", false, t0));
        assertThat(res1.decision()).isEqualTo(RiskDecision.ALLOW);

        // Location 2: Tema 2 hours later (~25 km away)
        OffsetDateTime t1 = t0.plusHours(2);
        var res2 = geoVelocityService.checkAndRecord(actor, "ref-2",
                new GeoVelocityService.GeoLocation(5.6698, -0.0166, 50.0, "Tema", "GH", "102.176.0.2", false, t1));
        assertThat(res2.decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(res2.impossibleTravel()).isFalse();
        assertThat(res2.speedKmh()).isGreaterThan(0.0).isLessThan(50.0);
    }

    @Test
    @DisplayName("Algorithm 027: Subsecond burst with cellular jitter (< 15 km in < 1s) does not divide by zero or cause false alarm")
    void testSubsecondCellularJitterClamped() {
        String actor = "actor-subsecond-" + UUID.randomUUID();
        OffsetDateTime t0 = OffsetDateTime.now();

        // Location 1: Accra
        geoVelocityService.checkAndRecord(actor, "ref-1",
                new GeoVelocityService.GeoLocation(5.6037, -0.1870, 50.0, "Accra", "GH", "102.176.0.1", false, t0));

        // Location 2: 100 ms later, 2 km away (cellular tower jump)
        OffsetDateTime t1 = t0.plusNanos(100_000_000L); // 100ms
        var res2 = geoVelocityService.checkAndRecord(actor, "ref-2",
                new GeoVelocityService.GeoLocation(5.6150, -0.1750, 100.0, "Accra", "GH", "102.176.0.2", false, t1));

        assertThat(res2.impossibleTravel()).isFalse();
        assertThat(res2.decision()).isEqualTo(RiskDecision.ALLOW);
        // Speed must be safely clamped to 0.0 to prevent artificial divide-by-zero spike
        assertThat(res2.speedKmh()).isEqualTo(0.0);
        assertThat(Double.isNaN(res2.speedKmh())).isFalse();
        assertThat(Double.isInfinite(res2.speedKmh())).isFalse();
    }

    @Test
    @DisplayName("Algorithm 027: Subsecond teleportation (> 15 km in < 1s) is rejected as impossible travel")
    void testSubsecondTeleportationRejected() {
        String actor = "actor-teleport-" + UUID.randomUUID();
        OffsetDateTime t0 = OffsetDateTime.now();

        // Location 1: Accra
        geoVelocityService.checkAndRecord(actor, "ref-1",
                new GeoVelocityService.GeoLocation(5.6037, -0.1870, 50.0, "Accra", "GH", "102.176.0.1", false, t0));

        // Location 2: London (51.5074, -0.1278) 200 ms later! (> 5,000 km)
        OffsetDateTime t1 = t0.plusNanos(200_000_000L);
        var res2 = geoVelocityService.checkAndRecord(actor, "ref-2",
                new GeoVelocityService.GeoLocation(51.5074, -0.1278, 50.0, "London", "GB", "82.165.0.1", false, t1));

        assertThat(res2.impossibleTravel()).isTrue();
        assertThat(res2.decision()).isEqualTo(RiskDecision.REJECT);
    }

    @Test
    @DisplayName("Algorithm 027: Negative time delta from clock skew is safely handled without NaN")
    void testNegativeTimeDeltaHandled() {
        String actor = "actor-skew-" + UUID.randomUUID();
        OffsetDateTime t0 = OffsetDateTime.now();

        // Location 1: Accra
        geoVelocityService.checkAndRecord(actor, "ref-1",
                new GeoVelocityService.GeoLocation(5.6037, -0.1870, 50.0, "Accra", "GH", "102.176.0.1", false, t0));

        // Location 2: 1 second in the past (out of order event / clock skew) at same city (< 30km)
        OffsetDateTime tPast = t0.minusSeconds(1);
        var res2 = geoVelocityService.checkAndRecord(actor, "ref-2",
                new GeoVelocityService.GeoLocation(5.6040, -0.1872, 50.0, "Accra", "GH", "102.176.0.1", false, tPast));

        assertThat(res2.decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(Double.isNaN(res2.speedKmh())).isFalse();

        // Location 3: 5 minutes in the past at distant city (> 30km shift)
        OffsetDateTime tDistantPast = t0.minusMinutes(5);
        var res3 = geoVelocityService.checkAndRecord(actor, "ref-3",
                new GeoVelocityService.GeoLocation(6.6885, -1.6244, 50.0, "Kumasi", "GH", "102.176.0.3", false, tDistantPast));

        assertThat(res3.impossibleTravel()).isTrue();
        assertThat(res3.decision()).isEqualTo(RiskDecision.REJECT);
    }

    @Test
    @DisplayName("Algorithm 027: Invalid coordinates are rejected with IllegalArgumentException")
    void testInvalidCoordinatesRejected() {
        String actor = "actor-coords-" + UUID.randomUUID();
        assertThatThrownBy(() -> geoVelocityService.checkAndRecord(actor, "ref-bad",
                new GeoVelocityService.GeoLocation(95.0, 0.0, 50.0, "Invalid", "GH", "102.176.0.1", false, OffsetDateTime.now())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Algorithm 027: recordCheckpoint and checkAndRecord synchronize consistently via geo_velocity_checkpoint")
    void testRecordCheckpointSynchronizesWithCheckAndRecord() {
        String actor = "actor-sync-" + UUID.randomUUID();
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(2);

        // 1. Record checkpoint directly via recordCheckpoint (e.g. background audit or login hook)
        geoVelocityService.recordCheckpoint(actor, 5.6037, -0.1870, "102.176.0.1", "Accra", "GH", t0);

        // 2. Perform transaction checkAndRecord 2 hours later at Tema (~25 km away)
        OffsetDateTime t1 = t0.plusHours(2);
        var result = geoVelocityService.checkAndRecord(
                actor,
                "ref-sync-tx",
                new GeoVelocityService.GeoLocation(5.6698, -0.0166, 50.0, "Tema", "GH", "102.176.0.2", false, t1)
        );

        assertThat(result.decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(result.impossibleTravel()).isFalse();
        assertThat(result.distanceKm()).isGreaterThan(20.0).isLessThan(30.0);
        assertThat(result.speedKmh()).isGreaterThan(0.0).isLessThan(25.0);
    }
}
