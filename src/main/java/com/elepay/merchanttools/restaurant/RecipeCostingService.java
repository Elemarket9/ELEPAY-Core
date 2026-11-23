package com.elepay.merchanttools.restaurant;

import com.elepay.merchanttools.inventory.MovementType;
import com.elepay.merchanttools.inventory.StockService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecipeCostingService {
    private final JdbcClient db;
    private final StockService stockService;

    public RecipeCostingService(JdbcClient db, StockService stockService) {
        this.db = db;
        this.stockService = stockService;
    }

    public record Recipe(
            UUID id,
            UUID merchantId,
            UUID menuItemVariantId,
            String recipeName,
            BigDecimal yieldQuantity,
            String preparationInstructions,
            OffsetDateTime createdAt
    ) {}

    public record RecipeIngredient(
            UUID id,
            UUID recipeId,
            UUID ingredientVariantId,
            BigDecimal quantityRequired,
            String unitOfMeasure
    ) {}

    public record IngredientCostDetail(
            UUID ingredientVariantId,
            String ingredientName,
            BigDecimal quantityRequired,
            String unitOfMeasure,
            long unitCostPesewas,
            long lineCostPesewas
    ) {}

    public record RecipeCostSummary(
            UUID recipeId,
            String recipeName,
            BigDecimal yieldQuantity,
            long totalCostPesewas,
            long costPerPortionPesewas,
            List<IngredientCostDetail> ingredients
    ) {}

    public record IngredientInput(
            UUID ingredientVariantId,
            BigDecimal quantityRequired,
            String unitOfMeasure
    ) {}

    @Transactional
    public Recipe createRecipe(
            UUID merchantId,
            UUID menuItemVariantId,
            String recipeName,
            BigDecimal yieldQuantity,
            String preparationInstructions,
            List<IngredientInput> ingredients
    ) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO restaurant_recipe (
                id, merchant_id, menu_item_variant_id, recipe_name,
                yield_quantity, preparation_instructions, created_at
            ) VALUES (
                :id, :merchantId, :variantId, :name, :yield, :instructions, now()
            )
            """)
            .param("id", id)
            .param("merchantId", merchantId)
            .param("variantId", menuItemVariantId)
            .param("name", recipeName)
            .param("yield", yieldQuantity == null ? BigDecimal.ONE : yieldQuantity)
            .param("instructions", preparationInstructions)
            .update();

        if (ingredients != null) {
            for (IngredientInput ing : ingredients) {
                UUID ingId = UUID.randomUUID();
                db.sql("""
                    INSERT INTO restaurant_recipe_ingredient (
                        id, recipe_id, ingredient_variant_id, quantity_required, unit_of_measure
                    ) VALUES (
                        :id, :recipeId, :ingVariantId, :qty, :uom
                    )
                    """)
                    .param("id", ingId)
                    .param("recipeId", id)
                    .param("ingVariantId", ing.ingredientVariantId())
                    .param("qty", ing.quantityRequired())
                    .param("uom", ing.unitOfMeasure())
                    .update();
            }
        }

        return getRecipe(id).orElseThrow();
    }

    public Optional<Recipe> getRecipe(UUID id) {
        return db.sql("""
            SELECT id, merchant_id, menu_item_variant_id, recipe_name,
                   yield_quantity, preparation_instructions, created_at
            FROM restaurant_recipe WHERE id = :id
            """)
            .param("id", id)
            .query(Recipe.class)
            .optional();
    }

    public Optional<Recipe> findByMenuItemVariant(UUID variantId) {
        return db.sql("""
            SELECT id, merchant_id, menu_item_variant_id, recipe_name,
                   yield_quantity, preparation_instructions, created_at
            FROM restaurant_recipe WHERE menu_item_variant_id = :variantId
            LIMIT 1
            """)
            .param("variantId", variantId)
            .query(Recipe.class)
            .optional();
    }

    public List<RecipeIngredient> listRecipeIngredients(UUID recipeId) {
        return db.sql("""
            SELECT id, recipe_id, ingredient_variant_id, quantity_required, unit_of_measure
            FROM restaurant_recipe_ingredient WHERE recipe_id = :recipeId
            """)
            .param("recipeId", recipeId)
            .query(RecipeIngredient.class)
            .list();
    }

    // Calculates real food recipe costing based on current ingredient inventory unit costs
    public RecipeCostSummary calculateRecipeCost(UUID recipeId) {
        Recipe recipe = getRecipe(recipeId).orElseThrow();
        List<RecipeIngredient> ingredients = listRecipeIngredients(recipeId);

        long totalCostPesewas = 0;
        List<IngredientCostDetail> details = new java.util.ArrayList<>();

        for (RecipeIngredient ing : ingredients) {
            var variantOpt = db.sql("""
                SELECT variant_name, cost_price_pesewas
                FROM inventory_variant WHERE id = :id
                """)
                .param("id", ing.ingredientVariantId())
                .query((rs, rowNum) -> new Object() {
                    final String name = rs.getString("variant_name");
                    final long cost = rs.getLong("cost_price_pesewas");
                })
                .optional();

            String name = variantOpt.map(v -> v.name).orElse("Unknown Ingredient");
            long unitCost = variantOpt.map(v -> v.cost).orElse(0L);
            long lineCost = (long) (unitCost * ing.quantityRequired().doubleValue());

            totalCostPesewas += lineCost;
            details.add(new IngredientCostDetail(ing.ingredientVariantId(), name, ing.quantityRequired(), ing.unitOfMeasure(), unitCost, lineCost));
        }

        BigDecimal yield = recipe.yieldQuantity() != null && recipe.yieldQuantity().compareTo(BigDecimal.ZERO) > 0
                ? recipe.yieldQuantity()
                : BigDecimal.ONE;

        long costPerPortion = BigDecimal.valueOf(totalCostPesewas)
                .divide(yield, 0, RoundingMode.HALF_UP)
                .longValue();

        return new RecipeCostSummary(recipeId, recipe.recipeName(), yield, totalCostPesewas, costPerPortion, details);
    }

    // Atomically deduct recipe ingredients from warehouse stock when a dish is prepared/ordered
    @Transactional
    public void deductRecipeStock(UUID menuItemVariantId, BigDecimal dishQuantity, UUID warehouseId, UUID referenceId) {
        Optional<Recipe> recipeOpt = findByMenuItemVariant(menuItemVariantId);
        if (recipeOpt.isEmpty()) {
            return; // No recipe configured for this menu item, skip
        }

        Recipe recipe = recipeOpt.get();
        List<RecipeIngredient> ingredients = listRecipeIngredients(recipe.id());

        for (RecipeIngredient ing : ingredients) {
            BigDecimal totalIngredientQty = ing.quantityRequired().multiply(dishQuantity);
            stockService.deductStock(
                    warehouseId,
                    ing.ingredientVariantId(),
                    totalIngredientQty,
                    MovementType.RECIPE_USAGE,
                    referenceId,
                    "Recipe usage for " + dishQuantity + " portions of " + recipe.recipeName()
            );
        }
    }
}
