package com.elepay.rails;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Simulated MoMo and bank name enquiry for sandbox and tests only.
 * Account numbers ending in 0 are not found, 9 simulates the network being
 * unavailable; anything else returns "SIMULATED ACCOUNT HOLDER &lt;last 4&gt;".
 */
@Configuration
@ConditionalOnProperty(name = "elepay.rails.sandbox.enabled", havingValue = "true")
class SimulatedNameVerification {

    @Bean
    NameVerification simulatedMomoVerification() {
        return verifier("momo");
    }

    @Bean
    NameVerification simulatedBankVerification() {
        return verifier("bank");
    }

    private static NameVerification verifier(String rail) {
        return new NameVerification() {
            @Override public String rail() { return rail; }

            @Override
            public Result verify(String network, String account) {
                char last = account.charAt(account.length() - 1);
                if (last == '0') return new Result(Outcome.NOT_FOUND, null, "sandbox");
                if (last == '9') return Result.unavailable("sandbox");
                return new Result(Outcome.VERIFIED,
                        "SIMULATED ACCOUNT HOLDER " + account.substring(Math.max(0, account.length() - 4)), "sandbox");
            }
        };
    }
}
