package com.elepay.push;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PushRegistrationController {
    private final PushRegistration registrations;
    public PushRegistrationController(PushRegistration registrations) { this.registrations = registrations; }

    @PostMapping("/v1/notifications/push/register")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registerPushDevice(@RequestBody PushRegistration.Request req) {
        registrations.register(req, false);
    }

    @PostMapping("/v1/notifications/push/unregister")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregisterPushDevice(@RequestBody PushRegistration.Removal req) {
        registrations.unregister(req, false);
    }

    @PostMapping("/v1/restaurant/kds/push/register")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registerKitchenPushDevice(@RequestBody PushRegistration.Request req) {
        registrations.register(req, true);
    }

    @PostMapping("/v1/restaurant/kds/push/unregister")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregisterKitchenPushDevice(@RequestBody PushRegistration.Removal req) {
        registrations.unregister(req, true);
    }
}
