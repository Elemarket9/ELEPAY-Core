package com.elepay.security;

import com.elepay.common.AuditCheckpoints;
import com.elepay.common.IntegrityKeys;
import com.elepay.identity.Actor;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Audit integrity: published verification keys, signed checkpoints and a full verification report. */
@RestController
@RequestMapping("/v1")
class IntegrityApi {
    private final IntegrityKeys keys;
    private final AuditCheckpoints checkpoints;
    IntegrityApi(IntegrityKeys keys, AuditCheckpoints checkpoints) {
        this.keys = keys;
        this.checkpoints = checkpoints;
    }

    /** Every public key ELEPAY has signed audit checkpoints with (Ed25519 and ML-DSA-65), for auditors to pin. */
    @GetMapping("/public/integrity/keys")
    public Map<String, Object> publicKeys() {
        return Map.of("current", keys.signingKeyId(), "keys", checkpoints.publicKeyRecords());
    }

    @PostMapping("/ops/audit/checkpoints")
    public AuditCheckpoints.Checkpoint checkpoint() {
        Actor.current().requireOps();
        return checkpoints.create().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "No new audit entries since the last checkpoint"));
    }

    @GetMapping("/ops/audit/verify")
    public AuditCheckpoints.Report verify() {
        Actor.current().requireOps();
        return checkpoints.verify();
    }
}
