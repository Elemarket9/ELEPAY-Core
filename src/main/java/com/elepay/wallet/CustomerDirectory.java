package com.elepay.wallet;

import com.elepay.common.ApiException;
import com.elepay.identity.Actor;
import com.elepay.ledger.Ledger;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Read access to customer wallets for other modules. Never exposes profile data. */
@Service
public class CustomerDirectory {
    private final JdbcClient db;

    CustomerDirectory(JdbcClient db) {
        this.db = db;
    }

    /** The signed-in caller's active customer id. */
    public UUID currentCustomer() {
        Actor actor = Actor.current();
        actor.requireUser();
        return db.sql("SELECT id FROM customer WHERE subject = :s AND status = 'active'").param("s", actor.subject())
                .query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Active customer wallet not found"));
    }

    public Optional<UUID> activeCustomerByPhone(String msisdn) {
        return db.sql("SELECT id FROM customer WHERE phone = :p AND status = 'active'").param("p", msisdn)
                .query(UUID.class).optional();
    }

    public boolean isActive(UUID customer) {
        return db.sql("SELECT count(*) FROM customer WHERE id = :id AND status = 'active'").param("id", customer)
                .query(Long.class).single() > 0;
    }

    public static String available(UUID customer) {
        return Ledger.code("customer", customer, "available", Ledger.GHS);
    }

    public static String reserved(UUID customer) {
        return Ledger.code("customer", customer, "reserved", Ledger.GHS);
    }
}
