package com.elepay.merchanttools.restaurant;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated by design: the one-time pairing code is the credential. Rate limited with other public endpoints. */
@RestController
@RequestMapping("/v1/public/waiter")
public class WaiterPairingController {
    private final WaiterDeviceService devices;

    public WaiterPairingController(WaiterDeviceService devices) {
        this.devices = devices;
    }

    public record PairRequest(String merchantCode, String code, String deviceName) {}

    @PostMapping("/pair")
    public WaiterDeviceService.Paired pair(@RequestBody PairRequest req) {
        if (req == null) return devices.pair(null, null, null);
        return devices.pair(req.merchantCode(), req.code(), req.deviceName());
    }
}
