package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider-neutral card-present acceptance boundary. An EMV-certified acquirer/terminal SDK
 * must implement CertifiedTerminalAdapter; this module NEVER reads PAN, PIN, NFC APDUs or
 * cryptograms. No adapter configured means RAIL_NOT_CONFIGURED, not a fake approval.
 */
@Service
public class PosTerminalAcceptanceService {
    public enum Mode { NFC_TAP, CHIP_INSERT, MAGSTRIPE }

    /** Implementations must be certified with the acquiring partner and verify their own device identity. */
    public interface CertifiedTerminalAdapter {
        boolean supports(UUID merchantId, UUID terminalId, Mode mode);
        PendingAttempt initiate(Request request);
    }

    public record Request(UUID orderId, UUID merchantId, UUID terminalId, Mode mode,
                          long amountPesewas, String checkoutReference) {}
    public record PendingAttempt(String providerReference, String status, String instructions) {}
    public record TerminalSession(UUID orderId, UUID terminalId, Mode mode, long amountPesewas,
                                  String checkoutReference, String providerReference, String status,
                                  String instructions) {}

    private final PosOrderService orders;
    private final PosTerminalService terminals;
    private final List<CertifiedTerminalAdapter> acquirers;

    public PosTerminalAcceptanceService(PosOrderService orders, PosTerminalService terminals,
                                        List<CertifiedTerminalAdapter> acquirers) {
        this.orders = orders;
        this.terminals = terminals;
        this.acquirers = List.copyOf(acquirers);
    }

    /**
     * Creates a pending payment request only. The acquirer's independently verified payment
     * must reach the existing checkout ledger before /digital-checkout/confirm can mark POS paid.
     */
    @Transactional
    public TerminalSession begin(UUID orderId, UUID terminalId, Mode mode, String actor) {
        if (mode == null) throw ApiException.badRequest("capture_mode_required", "Choose NFC tap, chip or swipe");
        var order = orders.getOrder(orderId).orElseThrow(() -> ApiException.notFound("POS order not found"));
        var terminal = terminals.getTerminal(terminalId)
                .orElseThrow(() -> ApiException.notFound("Payment terminal not found"));
        if (!order.merchantId().equals(terminal.merchantId())
                || !Objects.equals(order.branchId(), terminal.branchId()))
            throw ApiException.notFound("Payment terminal does not belong to this order's branch");
        if (!"ACTIVE".equals(terminal.status()))
            throw ApiException.conflict("terminal_inactive", "Payment terminal must be paired and active");
        if ("PAID".equals(order.status()) || order.totalPesewas() <= order.paidPesewas())
            throw ApiException.conflict("order_paid", "Order has no outstanding balance");
        CertifiedTerminalAdapter adapter = acquirers.stream()
                .filter(candidate -> candidate.supports(order.merchantId(), terminalId, mode))
                .findFirst().orElseThrow(() -> ApiException.unprocessable("RAIL_NOT_CONFIGURED",
                        "No certified card-present provider is configured for this terminal and capture mode"));
        var checkout = orders.startDigital(orderId, actor);
        if (!"open".equals(checkout.status()))
            throw ApiException.conflict("checkout_not_open", "Linked checkout cannot accept a new terminal payment");
        var pending = adapter.initiate(new Request(order.id(), order.merchantId(), terminalId,
                mode, checkout.amount(), checkout.reference()));
        if (pending == null || pending.providerReference() == null || pending.providerReference().isBlank()
                || !"PENDING".equals(pending.status()))
            throw ApiException.unprocessable("TERMINAL_RESPONSE_INVALID",
                    "The acquirer must return a pending attempt, never a fabricated approval");
        return new TerminalSession(orderId, terminalId, mode, checkout.amount(), checkout.reference(),
                pending.providerReference(), "PENDING", pending.instructions());
    }
}
