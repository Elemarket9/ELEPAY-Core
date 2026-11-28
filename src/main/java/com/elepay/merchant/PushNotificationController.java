package com.elepay.merchant;

import com.elepay.common.ApiException;
import com.elepay.common.FirebasePushService;
import com.elepay.identity.Actor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated Firebase Cloud Messaging (FCM) device token registration endpoints.
 * Mobile and web clients register their FCM token on sign-in and unregister on sign-out.
 */
@RestController
@RequestMapping("/v1/notifications/devices")
class PushNotificationController {

    private final FirebasePushService pushService;
    private final MerchantAccess merchantAccess;

    PushNotificationController(FirebasePushService pushService, MerchantAccess merchantAccess) {
        this.pushService = pushService;
        this.merchantAccess = merchantAccess;
    }

    record RegisterDeviceRequest(String token, String platform, String appId, UUID merchantId) {}

    record UnregisterDeviceRequest(String token) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    FirebasePushService.RegisteredDevice register(@RequestBody RegisterDeviceRequest request) {
        if (request == null) {
            throw ApiException.badRequest("invalid_request", "Request body is required");
        }
        Actor actor = Actor.current();
        UUID merchantId = request.merchantId();
        if (merchantId != null) {
            merchantAccess.requireMember(merchantId);
        }
        return pushService.registerDevice(
                actor.subject(),
                merchantId,
                request.platform(),
                request.token(),
                request.appId()
        );
    }

    @GetMapping
    Map<String, Object> list() {
        Actor actor = Actor.current();
        List<FirebasePushService.RegisteredDevice> devices = pushService.listDevices(actor.subject());
        return Map.of(
                "fcmEnabled", pushService.isEnabled(),
                "devices", devices
        );
    }

    @DeleteMapping("/{token}")
    Map<String, Object> unregisterByPath(@PathVariable String token) {
        Actor actor = Actor.current();
        boolean removed = pushService.unregisterDevice(actor.subject(), token);
        return Map.of("unregistered", removed);
    }

    @DeleteMapping
    Map<String, Object> unregister(
            @RequestParam(required = false) String token,
            @RequestBody(required = false) UnregisterDeviceRequest body) {
        String effectiveToken = (token != null && !token.isBlank())
                ? token
                : (body != null ? body.token() : null);
        if (effectiveToken == null || effectiveToken.isBlank()) {
            throw ApiException.badRequest("token_required", "FCM token is required");
        }
        Actor actor = Actor.current();
        boolean removed = pushService.unregisterDevice(actor.subject(), effectiveToken);
        return Map.of("unregistered", removed);
    }
}
