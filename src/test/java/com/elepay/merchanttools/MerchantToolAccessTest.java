package com.elepay.merchanttools;

import com.elepay.common.ApiException;
import com.elepay.merchant.MerchantAccess;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MerchantToolAccessTest {
    private final MerchantAccess merchants = mock(MerchantAccess.class);
    private final JdbcClient db = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    private final MerchantToolAccess access = new MerchantToolAccess(merchants, db);

    @Test
    void resourceOwnershipCannotBeOverriddenByCaller() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), warehouse = UUID.randomUUID();
        when(db.sql("SELECT merchant_id FROM inventory_warehouse WHERE id = :id").param("id", warehouse)
                .query(UUID.class).optional()).thenReturn(Optional.of(b));
        assertThatThrownBy(() -> access.belongs(a, MerchantToolAccess.Resource.WAREHOUSE, warehouse))
                .isInstanceOf(ApiException.class);
        access.belongs(b, MerchantToolAccess.Resource.WAREHOUSE, warehouse);
        when(merchants.require(b, MerchantAccess.Permission.MANAGE)).thenThrow(ApiException.notFound("Merchant not found"));
        assertThatThrownBy(() -> access.manage(MerchantToolAccess.Resource.WAREHOUSE, warehouse))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void branchScopedUserCannotPerformMerchantWideAdministration() {
        UUID merchant = UUID.randomUUID();
        when(merchants.require(merchant, MerchantAccess.Permission.MANAGE))
                .thenReturn(new MerchantAccess.Access(merchant, "admin", UUID.randomUUID(), null));
        assertThatThrownBy(() -> access.manage(merchant)).isInstanceOf(ApiException.class);
    }
}
