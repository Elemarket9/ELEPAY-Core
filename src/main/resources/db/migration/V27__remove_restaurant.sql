-- ELEPAY is a payment service provider, not restaurant software. Remove the
-- restaurant and kitchen-display tables added in V22 (POS and inventory stay).
DROP TABLE IF EXISTS restaurant_kds_item;
DROP TABLE IF EXISTS restaurant_kds_ticket;
DROP TABLE IF EXISTS restaurant_recipe_ingredient;
DROP TABLE IF EXISTS restaurant_recipe;
DROP TABLE IF EXISTS restaurant_modifier_option;
DROP TABLE IF EXISTS restaurant_modifier_group;
DROP TABLE IF EXISTS restaurant_table;
DROP TABLE IF EXISTS restaurant_floor_plan;
