package com.elepay.merchanttools.restaurant;

import com.elepay.common.ApiException;
import com.elepay.merchant.MerchantAccess;
import com.elepay.merchanttools.MerchantToolAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** One merchant's staff must never see or change another merchant's floor, kitchen or recipes. */
class RestaurantAccessControlTest {
    private final MerchantAccess merchants = mock(MerchantAccess.class);
    private final JdbcClient db = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    private final MerchantToolAccess access = new MerchantToolAccess(merchants, db);
    private final KdsService kds = mock(KdsService.class);
    private final RestaurantTableService tables = mock(RestaurantTableService.class);
    private final RestaurantController controller = new RestaurantController(
            tables, mock(ModifierService.class), kds, mock(RecipeCostingService.class),
            mock(RestaurantSplitBillService.class), mock(KitchenStationService.class),
            mock(KdsPrintService.class), access);

    private void owns(String sql, UUID id, UUID merchant) {
        when(db.sql(sql).param("id", id).query(UUID.class).optional()).thenReturn(Optional.of(merchant));
    }

    @Test
    void kitchenStaffOfAnotherMerchantCannotBumpTicket() {
        UUID mine = UUID.randomUUID(), theirs = UUID.randomUUID(), ticket = UUID.randomUUID();
        owns("SELECT merchant_id FROM restaurant_kds_ticket WHERE id = :id", ticket, theirs);
        when(merchants.require(theirs, MerchantAccess.Permission.COLLECT))
                .thenThrow(ApiException.notFound("Merchant not found"));
        assertThatThrownBy(() -> controller.bumpTicket(ticket)).isInstanceOf(ApiException.class);
        verifyNoInteractions(kds);
    }

    @Test
    void ticketCannotReferenceAnotherMerchantsOrder() {
        UUID mine = UUID.randomUUID(), theirs = UUID.randomUUID(), order = UUID.randomUUID();
        owns("SELECT merchant_id FROM pos_order WHERE id = :id", order, theirs);
        var req = new RestaurantController.CreateTicketRequest(mine, order, null, DiningMode.DINE_IN, "Ama", null,
                List.of(new KdsService.KdsItemInput(UUID.randomUUID(), "Waakye", java.math.BigDecimal.ONE, null, null)), null, null);
        assertThatThrownBy(() -> controller.createTicket(req)).isInstanceOf(ApiException.class);
        verifyNoInteractions(kds);
    }

    @Test
    void tableCannotBeSeatedWithAnotherMerchantsOrder() {
        UUID mine = UUID.randomUUID(), theirs = UUID.randomUUID(), table = UUID.randomUUID(), order = UUID.randomUUID();
        owns("SELECT merchant_id FROM restaurant_table WHERE id = :id", table, mine);
        owns("SELECT merchant_id FROM pos_order WHERE id = :id", order, theirs);
        assertThatThrownBy(() -> controller.seatTable(table, order)).isInstanceOf(ApiException.class);
        verifyNoInteractions(tables);
    }

    @Test
    void readingAnotherMerchantsKitchenIsRefused() {
        UUID theirs = UUID.randomUUID();
        when(merchants.require(theirs, MerchantAccess.Permission.VIEW)).thenThrow(ApiException.notFound("Merchant not found"));
        assertThatThrownBy(() -> controller.getActiveTickets(theirs)).isInstanceOf(ApiException.class);
        verifyNoInteractions(kds);
    }

    @Test
    void everyEndpointChecksAccessBeforeDoingAnything() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/elepay/merchanttools/restaurant/RestaurantController.java"));
        Matcher m = Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping[^\\n]*\\n\\s*public[^{]+\\{([^}]*)").matcher(src);
        List<String> unguarded = new ArrayList<>();
        int endpoints = 0;
        while (m.find()) {
            endpoints++;
            String firstLine = m.group(2).strip().split("\\n")[0];
            if (!firstLine.contains("access.")) unguarded.add(firstLine);
        }
        assertThat(endpoints).isGreaterThanOrEqualTo(29);
        assertThat(unguarded).as("Endpoints whose first statement is not an access check").isEmpty();
    }
}
