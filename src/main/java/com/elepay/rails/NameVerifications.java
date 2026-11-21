package com.elepay.rails;

import java.util.List;
import org.springframework.stereotype.Component;

/** Routes beneficiary verification to the connected network for a rail. */
@Component
public class NameVerifications {
    private final List<NameVerification> verifiers;

    NameVerifications(List<NameVerification> verifiers) {
        this.verifiers = verifiers;
    }

    /** Returns UNAVAILABLE when no network connection for the rail offers verification. */
    public NameVerification.Result verify(String rail, String network, String account) {
        for (NameVerification v : verifiers) {
            if (v.rail().equals(rail)) {
                try {
                    return v.verify(network, account);
                } catch (RuntimeException e) {
                    return NameVerification.Result.unavailable(v.getClass().getSimpleName());
                }
            }
        }
        return NameVerification.Result.unavailable("no_connection");
    }
}
