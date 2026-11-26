package com.elepay.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.elepay.common.ApiException;
import com.elepay.security.MakerCheckerService;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EscrowReleaseApprovalHandlerTest {

    private static MakerCheckerService.Proposal proposal(
            UUID proposalId,
            UUID merchantId,
            Map<String, Object> payload,
            String reason,
            String proposedBy,
            String reviewedBy
    ) {
        return new MakerCheckerService.Proposal(
                proposalId,
                "ESCROW_RELEASE",
                "checkout_allocation",
                "CHK-123:0",
                merchantId,
                payload,
                reason,
                "APPROVED",
                proposedBy,
                OffsetDateTime.now(),
                reviewedBy,
                OffsetDateTime.now(),
                "Approved",
                OffsetDateTime.now().plusHours(24)
        );
    }

    @Test
    void supportsOnlyEscrowRelease() {
        var handler = new EscrowReleaseApprovalHandler(mock(CheckoutService.class));
        assertThat(handler.supports("ESCROW_RELEASE")).isTrue();
        assertThat(handler.supports("FORCE_SETTLEMENT")).isFalse();
        assertThat(handler.supports("LARGE_PAYOUT")).isFalse();
    }

    @Test
    void executeCallsReleaseReserveWithDeterministicProposalKey() {
        CheckoutService checkouts = mock(CheckoutService.class);
        var handler = new EscrowReleaseApprovalHandler(checkouts);

        UUID proposalId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        var proposal = proposal(
                proposalId,
                merchantId,
                Map.of("reference", "CHK-123", "line", 0, "amount", 1500L, "reason", "Delivery verified"),
                "Proposal reason",
                "ops-maker",
                "ops-checker"
        );

        handler.execute(proposal);

        verify(checkouts).releaseReserve(
                merchantId,
                "CHK-123",
                0,
                1500L,
                "Delivery verified",
                "ops-checker",
                "proposal:" + proposalId
        );
    }

    @Test
    void executeFallsBackToProposalReasonAndProposerAndAcceptsNumericStrings() {
        CheckoutService checkouts = mock(CheckoutService.class);
        var handler = new EscrowReleaseApprovalHandler(checkouts);

        UUID proposalId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Map<String, Object> payload = new HashMap<>();
        payload.put("reference", "CHK-456");
        payload.put("line", "0");
        payload.put("amount", "1500");
        payload.put("reason", null);

        handler.execute(proposal(proposalId, merchantId, payload, "Fallback reason", "ops-maker", null));

        verify(checkouts).releaseReserve(
                merchantId,
                "CHK-456",
                0,
                1500L,
                "Fallback reason",
                "ops-maker",
                "proposal:" + proposalId
        );

        UUID proposalId2 = UUID.randomUUID();
        Map<String, Object> nullStringReasonPayload = Map.of(
                "reference", "CHK-789",
                "line", 1,
                "amount", 2000L,
                "reason", "null"
        );
        handler.execute(proposal(proposalId2, merchantId, nullStringReasonPayload, "Proposal fallback", "ops-maker", "ops-checker"));

        verify(checkouts).releaseReserve(
                merchantId,
                "CHK-789",
                1,
                2000L,
                "Proposal fallback",
                "ops-checker",
                "proposal:" + proposalId2
        );
    }

    @Test
    void executeRejectsMalformedPayloadsWithBadRequestInvalidProposalPayload() {
        CheckoutService checkouts = mock(CheckoutService.class);
        var handler = new EscrowReleaseApprovalHandler(checkouts);
        UUID proposalId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        Map<String, Object> nullRefPayload = new HashMap<>();
        nullRefPayload.put("reference", null);
        nullRefPayload.put("line", 0);
        nullRefPayload.put("amount", 1000L);

        var invalidCases = java.util.List.of(
                proposal(proposalId, null, Map.of("reference", "CHK-1", "line", 0, "amount", 1000L), "r", "m", "c"),
                proposal(proposalId, merchantId, null, "r", "m", "c"),
                proposal(proposalId, merchantId, Map.of("line", 0, "amount", 1000L), "r", "m", "c"),
                proposal(proposalId, merchantId, nullRefPayload, "r", "m", "c"),
                proposal(proposalId, merchantId, Map.of("reference", "null", "line", 0, "amount", 1000L), "r", "m", "c"),
                proposal(proposalId, merchantId, Map.of("reference", "CHK-1", "amount", 1000L), "r", "m", "c"),
                proposal(proposalId, merchantId, Map.of("reference", "CHK-1", "line", -1, "amount", 1000L), "r", "m", "c"),
                proposal(proposalId, merchantId, Map.of("reference", "CHK-1", "line", 1.5, "amount", 1000L), "r", "m", "c"),
                proposal(proposalId, merchantId, Map.of("reference", "CHK-1", "line", "bad", "amount", 1000L), "r", "m", "c"),
                proposal(proposalId, merchantId, Map.of("reference", "CHK-1", "line", 0), "r", "m", "c"),
                proposal(proposalId, merchantId, Map.of("reference", "CHK-1", "line", 0, "amount", 0L), "r", "m", "c"),
                proposal(proposalId, merchantId, Map.of("reference", "CHK-1", "line", 0, "amount", -50L), "r", "m", "c"),
                proposal(proposalId, merchantId, Map.of("reference", "CHK-1", "line", 0, "amount", 12.34), "r", "m", "c"),
                proposal(proposalId, merchantId, Map.of("reference", "CHK-1", "line", 0, "amount", "abc"), "r", "m", "c")
        );

        for (var invalid : invalidCases) {
            assertThatThrownBy(() -> handler.execute(invalid))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(apiEx.code()).isEqualTo("invalid_proposal_payload");
                    });
        }

        verifyNoInteractions(checkouts);
    }
}
