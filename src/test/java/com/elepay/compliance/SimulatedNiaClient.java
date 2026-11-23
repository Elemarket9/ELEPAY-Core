package com.elepay.compliance;

import com.elepay.compliance.GhanaCardVerificationService.NiaClient;
import com.elepay.compliance.GhanaCardVerificationService.NiaResponse;
import com.elepay.compliance.GhanaCardVerificationService.Status;
import java.time.LocalDate;

final class SimulatedNiaClient implements NiaClient {
    @Override
    public NiaResponse queryNia(String idNumber, String fullName, LocalDate dateOfBirth) {
        if (idNumber.startsWith("GHA-999")) {
            // Spec requirement: distinguish unavailable from verified or mismatched
            return new NiaResponse(Status.UNAVAILABLE, 0.0, null, null, "NIA gateway timeout or service unavailable");
        }
        if (idNumber.startsWith("GHA-000")) {
            return new NiaResponse(Status.MISMATCHED, 35.0, "Different Person", LocalDate.of(1980, 1, 1), "Name and DOB do not match NIA database");
        }
        return new NiaResponse(Status.VERIFIED, 99.5, fullName, dateOfBirth, "Simulated successful NIA registry match");
    }

    @Override
    public boolean isSimulated() {
        return true;
    }

    @Override
    public String getProviderName() {
        return "SIMULATED_NIA_SANDBOX";
    }
}
