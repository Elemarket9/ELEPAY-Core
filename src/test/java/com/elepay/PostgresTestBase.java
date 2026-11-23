package com.elepay;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Shared PostgreSQL 18 container for all integration tests in the JVM. */
@SpringBootTest(classes = ElepayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class PostgresTestBase {

    record Database(String url, String username, String password) {}

    static final Database DATABASE = database();

    private static Database database() {
        String url = System.getenv("ELEPAY_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            return new Database(url, System.getenv("ELEPAY_TEST_DB_USER"), System.getenv("ELEPAY_TEST_DB_PASSWORD"));
        }
        try {
            var postgres = new PostgreSQLContainer("postgres:18")
                    .withDatabaseName("elepay_test").withUsername("elepay").withPassword("elepay");
            postgres.start();
            return new Database(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        } catch (Exception e) {
            return new Database("jdbc:postgresql://localhost:5432/elepay_test", "elepay", "elepay");
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::url);
        properties.add("spring.datasource.username", DATABASE::username);
        properties.add("spring.datasource.password", DATABASE::password);
    }
}
