package com.elepay.push;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PushRegistrationController {
    private final PushRegistration registrations;
    public PushRegistrationController(PushRegistration registrations) { this.registrations = registrations; }

    @PostMapping("/v1/notifications/push/register")
    public ResponseEntity<Void> register(@RequestBody PushRegistration.Request req) {
        registrations.register(req, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/notifications/push/unregister")
    public ResponseEntity<Void> unregister(@RequestBody PushRegistration.Removal req) {
        registrations.unregister(req, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/restaurant/kds/push/register")
    public ResponseEntity<Void> registerKitchen(@RequestBody PushRegistration.Request req) {
        registrations.register(req, true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/restaurant/kds/push/unregister")
    public ResponseEntity<Void> unregisterKitchen(@RequestBody PushRegistration.Removal req) {
        registrations.unregister(req, true);
        return ResponseEntity.noContent().build();
    }
}
