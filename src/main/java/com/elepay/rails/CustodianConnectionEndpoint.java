package com.elepay.rails;

import com.elepay.identity.Actor;
import com.elepay.security.DualControl;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CustodianConnectionEndpoint {
    private final CustodianConnection connection;
    private final DualControl dualControl;

    CustodianConnectionEndpoint(CustodianConnection connection, DualControl dualControl) {
        this.connection = connection;
        this.dualControl = dualControl;
    }

    @PostMapping("/v1/ops/custodian/connect")
    public Map<String, Boolean> connect() {
        Actor actor = Actor.current();
        actor.requireFinance();
        var account = connection.discover();
        String fingerprint = CustodianConnection.fingerprint(account);
        dualControl.hold("CUSTODIAN_CONFIG", "custodian", "primary", null,
                Map.of("operation", "connect", "accountFingerprint", fingerprint, "bankCode", account.bankCode(),
                        "ending", account.accountNumber().substring(account.accountNumber().length() - 4)),
                "Connect provider-verified GHS trust account", actor);
        connection.activate(account, actor.subject());
        return Map.of("connected", true);
    }
}
