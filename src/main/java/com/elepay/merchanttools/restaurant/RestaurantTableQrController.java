package com.elepay.merchanttools.restaurant;

import com.elepay.merchanttools.MerchantToolAccess;
import static com.elepay.merchanttools.MerchantToolAccess.Resource.TABLE;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Restaurant owner decides which anonymous QR requests reach the actual kitchen. */
@RestController
@RequestMapping("/v1/restaurant/table-qr")
public class RestaurantTableQrController {
    private final MerchantToolAccess access;
    private final RestaurantTableQrService qr;
    public RestaurantTableQrController(MerchantToolAccess access, RestaurantTableQrService qr) {
        this.access = access; this.qr = qr;
    }

    @PostMapping("/tables/{tableId}/rotate")
    public RestaurantTableQrService.RotatedQr rotate(@PathVariable UUID tableId) {
        access.manage(TABLE, tableId);
        return qr.rotate(tableId);
    }

    @PostMapping("/tables/{tableId}/disable")
    public void disable(@PathVariable UUID tableId) {
        access.manage(TABLE, tableId);
        qr.disable(tableId);
    }

    @GetMapping("/merchant/{merchantId}/requests")
    public List<RestaurantTableQrService.PendingRequest> pending(@PathVariable UUID merchantId) {
        access.manage(merchantId);
        return qr.pending(merchantId);
    }

    @PostMapping("/requests/{requestId}/accept")
    public RestaurantTableQrService.CustomerRequest accept(@PathVariable UUID requestId) {
        access.manage(qr.requestMerchant(requestId));
        return qr.accept(requestId, com.elepay.identity.Actor.current().subject());
    }

    @PostMapping("/requests/{requestId}/reject")
    public RestaurantTableQrService.CustomerRequest reject(@PathVariable UUID requestId) {
        access.manage(qr.requestMerchant(requestId));
        return qr.reject(requestId);
    }
}
