package com.elepay.emtech;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One virtual device per merchant and channel, plus one platform device per channel.
 * Coordinates are the registered shop or a public city reference point, never a customer GPS fix.
 */
@Service
public class VirtualDevices {
    private final JdbcClient db;
    private final EmtechReporter reporter;

    public VirtualDevices(JdbcClient db, EmtechReporter reporter) {
        this.db = db;
        this.reporter = reporter;
    }

    @Transactional
    public String ensureMerchant(UUID merchantId, String channel, String city, String region, String country,
                                 String latitude, String longitude, String connectionType) {
        String deviceId = EmtechDeviceIds.merchantChannel(merchantId, channel);
        save("merchant", merchantId, deviceId, channel, city, region, country, latitude, longitude, connectionType,
                "ELEPAY " + EmtechDeviceIds.slug(channel) + " channel");
        return deviceId;
    }

    @Transactional
    public String ensurePlatform(String channel, String city, String region, String country,
                                 String latitude, String longitude) {
        String deviceId = EmtechDeviceIds.platform(channel);
        save("platform", null, deviceId, channel, city, region, country, latitude, longitude,
                EmtechDeviceIds.connectionFor(channel), "ELEPAY platform " + EmtechDeviceIds.slug(channel) + " channel");
        return deviceId;
    }

    private void save(String scope, UUID merchantId, String deviceId, String channel, String city, String region,
                      String country, String latitude, String longitude, String connectionType, String name) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO regulatory_virtual_device
                    (id, device_id, scope_type, merchant_id, channel, city, region, country,
                     latitude, longitude, connection_type, location_description)
                VALUES (:id, :device, :scope, :merchant, :channel, :city, :region, :country,
                        :lat, :lon, :connection, :location)
                ON CONFLICT (device_id) DO NOTHING""")
                .param("id", id).param("device", deviceId).param("scope", scope).param("merchant", merchantId)
                .param("channel", channel).param("city", city).param("region", region).param("country", country)
                .param("lat", new java.math.BigDecimal(latitude)).param("lon", new java.math.BigDecimal(longitude))
                .param("connection", connectionType).param("location", name).update();
        var created = db.sql("SELECT created_at FROM regulatory_virtual_device WHERE device_id = :id")
                .param("id", deviceId).query((rs, i) -> rs.getObject(1, java.time.OffsetDateTime.class)).single();
        reporter.device(new RegulatoryFacts.Device(
                deviceId, "OTHER", name, city, region, country, null, name,
                latitude, longitude, "ELEPAY-VIRTUAL", "ELEPAY",
                "Virtual channel device. Coordinates are the registered shop or a city reference point.",
                EmtechReporter.instant(created), connectionType, null));
    }
}
