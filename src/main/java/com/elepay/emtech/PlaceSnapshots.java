package com.elepay.emtech;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** City, region and country derived for a checkout. The IP used to derive a city is not stored. */
@Service
public class PlaceSnapshots {
    private final JdbcClient db;

    public PlaceSnapshots(JdbcClient db) {
        this.db = db;
    }

    public record Snapshot(ServerSidePlaces.Place origin, ServerSidePlaces.Place destination) {}

    @Transactional
    public void save(UUID checkoutId, ServerSidePlaces.Place origin, ServerSidePlaces.Place destination) {
        db.sql("""
                INSERT INTO emtech_place_snapshot
                    (checkout_id, origin_city, origin_region, origin_country, origin_source,
                     dest_city, dest_region, dest_country, dest_source)
                VALUES (:id, :oc, :oreg, :ocountry, :osrc, :dc, :dreg, :dcountry, :dsrc)
                ON CONFLICT (checkout_id) DO UPDATE SET
                    origin_city = EXCLUDED.origin_city, origin_region = EXCLUDED.origin_region,
                    origin_country = EXCLUDED.origin_country, origin_source = EXCLUDED.origin_source,
                    dest_city = EXCLUDED.dest_city, dest_region = EXCLUDED.dest_region,
                    dest_country = EXCLUDED.dest_country, dest_source = EXCLUDED.dest_source""")
                .param("id", checkoutId)
                .param("oc", origin.city()).param("oreg", origin.region()).param("ocountry", origin.country())
                .param("osrc", origin.source())
                .param("dc", destination.city()).param("dreg", destination.region())
                .param("dcountry", destination.country()).param("dsrc", destination.source())
                .update();
    }

    public Optional<Snapshot> find(UUID checkoutId) {
        return db.sql("""
                SELECT origin_city, origin_region, origin_country, origin_source,
                       dest_city, dest_region, dest_country, dest_source
                FROM emtech_place_snapshot WHERE checkout_id = :id""")
                .param("id", checkoutId)
                .query((rs, i) -> new Snapshot(
                        new ServerSidePlaces.Place(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                        new ServerSidePlaces.Place(rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8))))
                .optional();
    }
}
