package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/** Verifies clean installation and upgrade from the original sandbox schema (V1-V3) with data. */
class MigrationIT {

    private static String createDatabase(String name) throws Exception {
        name += "_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var pg = PostgresTestBase.DATABASE;
        try (Connection c = DriverManager.getConnection(pg.url(), pg.username(), pg.password());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        return pg.url().substring(0, pg.url().lastIndexOf('/') + 1) + name;
    }

    private static Flyway flyway(String url, String target) {
        var pg = PostgresTestBase.DATABASE;
        var config = Flyway.configure().dataSource(url, pg.username(), pg.password())
                .locations("classpath:db/migration");
        if (target != null) config.target(MigrationVersion.fromVersion(target));
        return config.load();
    }

    @Test
    void cleanInstallAppliesEveryMigration() throws Exception {
        String url = createDatabase("migration_clean");
        var result = flyway(url, null).migrate();
        assertThat(result.success).isTrue();
        assertThat(flyway(url, null).validateWithResult().validationSuccessful).isTrue();
        try (Connection c = DriverManager.getConnection(url, PostgresTestBase.DATABASE.username(),
                PostgresTestBase.DATABASE.password()); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT code FROM ledger_account WHERE owner_type = 'system'")) {
            var codes = new java.util.ArrayList<String>();
            while (rs.next()) codes.add(rs.getString(1));
            assertThat(codes).containsExactlyInAnyOrder(
                    "system:revenue:GHS", "system:suspense:GHS", "system:refunds_in_flight:GHS",
                    "system:clearing:sandbox:GHS", "system:rail_fees:GHS", "system:revenue:USD",
                    "system:fx_clearing:GHS", "system:fx_clearing:USD", "system:fx_in_flight:USD",
                    "system:fx_in_flight:GHS", "system:deferred_revenue:GHS", "system:revenue:earned:GHS");
        }
    }

    @Test
    void upgradeFromSandboxSchemaPreservesExistingData() throws Exception {
        String url = createDatabase("migration_upgrade");
        flyway(url, "3").migrate();
        var pg = PostgresTestBase.DATABASE;
        try (Connection c = DriverManager.getConnection(url, pg.username(), pg.password());
             Statement s = c.createStatement()) {
            s.execute("""
                    INSERT INTO ledger_operation VALUES ('00000000-0000-0000-0000-000000000001', '{"type":"topup"}');
                    INSERT INTO ledger_move (operation_id, line, source, destination, pesewas)
                        VALUES ('00000000-0000-0000-0000-000000000001', 0, 'system:external', 'customer:legacy', 15000);
                    INSERT INTO payment_intent (id, checkout) VALUES ('00000000-0000-0000-0000-000000000002', '{}');
                    INSERT INTO payment_outbox (payment_id, event_type) VALUES ('00000000-0000-0000-0000-000000000002', 'cancelled');
                    """);
        }
        var result = flyway(url, null).migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThan(0);
        try (Connection c = DriverManager.getConnection(url, pg.username(), pg.password());
             Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT pesewas FROM ledger_balance WHERE account = 'customer:legacy'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(15000);
            }
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM payment_outbox")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
        assertThat(flyway(url, null).validateWithResult().validationSuccessful).isTrue();
    }
}
