package com.elepay.restaurant;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModifierService {
    private final JdbcClient db;

    public ModifierService(JdbcClient db) {
        this.db = db;
    }

    public record ModifierGroup(
            UUID id,
            UUID merchantId,
            String name,
            int minSelection,
            int maxSelection,
            boolean isRequired,
            OffsetDateTime createdAt
    ) {}

    public record ModifierOption(
            UUID id,
            UUID groupId,
            String name,
            long additionalPricePesewas
    ) {}

    @Transactional
    public ModifierGroup createModifierGroup(
            UUID merchantId,
            String name,
            int minSelection,
            int maxSelection,
            boolean isRequired
    ) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO restaurant_modifier_group (
                id, merchant_id, name, min_selection, max_selection, is_required, created_at
            ) VALUES (
                :id, :merchantId, :name, :min, :max, :req, now()
            )
            """)
            .param("id", id)
            .param("merchantId", merchantId)
            .param("name", name)
            .param("min", minSelection)
            .param("max", maxSelection)
            .param("req", isRequired)
            .update();

        return getModifierGroup(id).orElseThrow();
    }

    public Optional<ModifierGroup> getModifierGroup(UUID id) {
        return db.sql("""
            SELECT id, merchant_id, name, min_selection, max_selection, is_required, created_at
            FROM restaurant_modifier_group WHERE id = :id
            """)
            .param("id", id)
            .query(ModifierGroup.class)
            .optional();
    }

    public List<ModifierGroup> listModifierGroups(UUID merchantId) {
        return db.sql("""
            SELECT id, merchant_id, name, min_selection, max_selection, is_required, created_at
            FROM restaurant_modifier_group WHERE merchant_id = :merchantId
            ORDER BY name ASC
            """)
            .param("merchantId", merchantId)
            .query(ModifierGroup.class)
            .list();
    }

    @Transactional
    public ModifierOption addModifierOption(UUID groupId, String name, long additionalPricePesewas) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO restaurant_modifier_option (id, group_id, name, additional_price_pesewas)
            VALUES (:id, :groupId, :name, :price)
            """)
            .param("id", id)
            .param("groupId", groupId)
            .param("name", name)
            .param("price", additionalPricePesewas)
            .update();

        return new ModifierOption(id, groupId, name, additionalPricePesewas);
    }

    public List<ModifierOption> listOptions(UUID groupId) {
        return db.sql("""
            SELECT id, group_id, name, additional_price_pesewas
            FROM restaurant_modifier_option WHERE group_id = :groupId
            ORDER BY additional_price_pesewas ASC
            """)
            .param("groupId", groupId)
            .query(ModifierOption.class)
            .list();
    }
}
