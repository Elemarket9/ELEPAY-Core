package com.elepay.merchanttools.restaurant;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated by design: the pairing code is the credential. Rate limited with other public endpoints. */
@RestController
@RequestMapping("/v1/public/kitchen")
public class KitchenPairingController {
    private final KitchenDeviceService devices;

    public KitchenPairingController(KitchenDeviceService devices) {
        this.devices = devices;
    }

    public record PairRequest(String merchantCode, String code, String deviceName) {}

    @PostMapping("/pair")
    public KitchenDeviceService.Paired pair(@RequestBody PairRequest req) {
        return devices.pair(req.merchantCode(), req.code(), req.deviceName());
    }
}
