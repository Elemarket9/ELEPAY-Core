package com.elepay.payout;

import com.elepay.identity.Actor;
import com.elepay.security.DualControl;
import java.util.Map;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Changing payout velocity ceilings is a dual-control operation. */
@RestController
class VelocityCeilingController {
    private final PayoutVelocityService velocity;
    private final DualControl dualControl;

    VelocityCeilingController(PayoutVelocityService velocity, DualControl dualControl) {
        this.velocity = velocity;
        this.dualControl = dualControl;
    }

    public record CeilingChange(long hourlyCeilingPesewas, long dailyCeilingPesewas, long maxSinglePayoutPesewas,
                                boolean autoFreezeOnBreach) {}

    @PutMapping("/v1/ops/payouts/velocity-ceilings")
    public Map<String, String> update(@RequestBody CeilingChange request) {
        Actor actor = Actor.current();
        actor.requireOps();
        dualControl.hold("VELOCITY_CEILING_CHANGE", "payout_safety", "global", null, Map.of(
                "hourly", request.hourlyCeilingPesewas(),
                "daily", request.dailyCeilingPesewas(),
                "single", request.maxSinglePayoutPesewas(),
                "autoFreeze", request.autoFreezeOnBreach()
        ), "velocity ceiling change", actor);
        velocity.updateCeilings(request.hourlyCeilingPesewas(), request.dailyCeilingPesewas(),
                request.maxSinglePayoutPesewas(), request.autoFreezeOnBreach(), actor.subject());
        return Map.of("status", "updated");
    }
}
