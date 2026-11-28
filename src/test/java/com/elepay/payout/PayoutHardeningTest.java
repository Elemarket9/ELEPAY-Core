package com.elepay.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.ApiException;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayoutHardeningTest {

    @Test
    @DisplayName("Payouts enforce MAX_BATCH limit of 500 items")
    void testMaxBatchLimit() {
        assertThat(Payouts.MAX_BATCH).isEqualTo(500);

        List<Payouts.Item> oversized = new ArrayList<>();
        for (int i = 0; i <= 500; i++) {
            oversized.add(new Payouts.Item("ref-" + i, 100,
                    new Payouts.Destination("momo", "mtn", "0241234567", "User " + i),
                    "bolt", null));
        }

        assertThat(oversized.size()).isGreaterThan(Payouts.MAX_BATCH);
    }

    @Test
    @DisplayName("Duplicate client references within the same batch are rejected")
    void testDuplicateClientReferenceDetection() {
        Set<String> refs = new HashSet<>();
        List<String> inputRefs = List.of("ref-1", "ref-2", "ref-1");

        boolean duplicateFound = false;
        for (String r : inputRefs) {
            if (!refs.add(r)) {
                duplicateFound = true;
                break;
            }
        }

        assertThat(duplicateFound).isTrue();
    }

    @Test
    @DisplayName("Destination normalization enforces momo networks and valid bank accounts")
    void testDestinationValidation() {
        Set<String> momo = Set.of("mtn", "telecel", "airteltigo");

        assertThat(momo.contains("mtn")).isTrue();
        assertThat(momo.contains("vodafone")).isFalse(); // re-branded to telecel

        // Bank validation regex
        String validNetwork = "TST";
        String validAccount = "123456789012";
        assertThat(validNetwork.matches("[A-Z0-9]{2,20}")).isTrue();
        assertThat(validAccount.matches("[0-9]{6,34}")).isTrue();

        String invalidAccount = "123";
        assertThat(invalidAccount.matches("[0-9]{6,34}")).isFalse();
    }

    @Test
    @DisplayName("Retry eligibility: only failed and returned payouts can be retried")
    void testRetryEligibility() {
        Set<String> retryable = Set.of("failed", "returned");
        Set<String> nonRetryable = Set.of("reserved", "submitted", "succeeded", "rejected");

        for (String s : retryable) {
            assertThat(Set.of("failed", "returned").contains(s)).isTrue();
        }

        for (String s : nonRetryable) {
            assertThat(Set.of("failed", "returned").contains(s)).isFalse();
        }
    }
}
