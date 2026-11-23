package com.elepay.merchanttools.restaurant;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Public bearer-by-QR menu and untrusted order request only. No money moves and
 * nothing reaches the KDS until an authenticated merchant approves the request.
 */
@RestController
@RequestMapping("/v1/public/restaurant/table-qr")
public class PublicRestaurantQrController {
    private final RestaurantTableQrService qr;
    public PublicRestaurantQrController(RestaurantTableQrService qr) { this.qr = qr; }

    @GetMapping("/{token}")
    public RestaurantTableQrService.TableOffer menu(@PathVariable String token) {
        return qr.offer(token);
    }

    public record SubmitRequest(String clientKey, List<RestaurantTableQrService.Selection> items) {}
    @PostMapping("/{token}/orders")
    public RestaurantTableQrService.CustomerRequest submit(@PathVariable String token, @RequestBody SubmitRequest req) {
        return qr.submit(token, req.clientKey(), req.items());
    }

    @GetMapping("/requests/{requestId}/status")
    public RestaurantTableQrService.CustomerRequest status(@PathVariable UUID requestId) {
        return qr.status(requestId);
    }
}
