package com.elepay.pos;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosCartService {
    private final JdbcClient db;

    public PosCartService(JdbcClient db) {
        this.db = db;
    }

    public record HeldCart(
            UUID id,
            UUID terminalId,
            UUID cashierId,
            UUID merchantId,
            String customerName,
            String cartPayloadJson,
            long totalPesewas,
            OffsetDateTime heldAt
    ) {}

    @Transactional
    public HeldCart holdCart(
            UUID terminalId,
            UUID cashierId,
            UUID merchantId,
            String customerName,
            String cartPayloadJson,
            long totalPesewas
    ) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO pos_cart_held (
                id, terminal_id, cashier_id, merchant_id, customer_name,
                cart_payload_json, total_pesewas, held_at
            ) VALUES (
                :id, :terminalId, :cashierId, :merchantId, :customerName,
                :cartPayloadJson, :totalPesewas, now()
            )
            """)
            .param("id", id)
            .param("terminalId", terminalId)
            .param("cashierId", cashierId)
            .param("merchantId", merchantId)
            .param("customerName", customerName)
            .param("cartPayloadJson", cartPayloadJson)
            .param("totalPesewas", totalPesewas)
            .update();

        return getHeldCart(id).orElseThrow();
    }

    public Optional<HeldCart> getHeldCart(UUID id) {
        return db.sql("""
            SELECT id, terminal_id, cashier_id, merchant_id, customer_name,
                   cart_payload_json, total_pesewas, held_at
            FROM pos_cart_held WHERE id = :id
            """)
            .param("id", id)
            .query(HeldCart.class)
            .optional();
    }

    public List<HeldCart> listHeldCarts(UUID terminalId) {
        return db.sql("""
            SELECT id, terminal_id, cashier_id, merchant_id, customer_name,
                   cart_payload_json, total_pesewas, held_at
            FROM pos_cart_held WHERE terminal_id = :terminalId
            ORDER BY held_at DESC
            """)
            .param("terminalId", terminalId)
            .query(HeldCart.class)
            .list();
    }

    @Transactional
    public HeldCart recallHeldCart(UUID id) {
        HeldCart cart = getHeldCart(id).orElseThrow(() -> new IllegalArgumentException("Held cart not found: " + id));
        db.sql("DELETE FROM pos_cart_held WHERE id = :id").param("id", id).update();
        return cart;
    }

    @Transactional
    public void deleteHeldCart(UUID id) {
        db.sql("DELETE FROM pos_cart_held WHERE id = :id").param("id", id).update();
    }
}
