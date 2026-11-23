package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.identity.Actor;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/**
 * Public and internal endpoints for partner rails:
 * - Card BIN lookups (Algorithm 017)
 * - Card tokenization (Algorithm 019)
 * - Smart routing resolution (Algorithm 014)
 * - Network health indicators (Algorithm 016)
 */
@RestController
public class RailEndpoints {
    private final CardBinLookupService binService;
    private final CardTokenVault vault;
    private final SmartPaymentRouter router;
    private final NetworkHealthDetector health;
    private final CustodianBankBackstopService custodianBackstop;

    public RailEndpoints(
            CardBinLookupService binService,
            CardTokenVault vault,
            SmartPaymentRouter router,
            NetworkHealthDetector health,
            @org.springframework.beans.factory.annotation.Autowired(required = false) CustodianBankBackstopService custodianBackstop
    ) {
        this.binService = binService;
        this.vault = vault;
        this.router = router;
        this.health = health;
        this.custodianBackstop = custodianBackstop;
    }

    // --- Card BIN Lookup (017) ---
    @GetMapping("/v1/rails/card/bins/{bin}")
    public CardBinLookupService.CardBin lookupBin(@PathVariable String bin) {
        return binService.lookup(bin)
                .orElseThrow(() -> ApiException.notFound("bin_not_found", "Card BIN not recognized in versioned database"));
    }

    // --- Card Tokenization (019) ---
    public record TokenizeRequest(
            String pan,
            int expiryMonth,
            int expiryYear,
            String cardholderName,
            String cvv
    ) {}

    @PostMapping("/v1/rails/card/tokens")
    public CardTokenVault.TokenizedCard tokenizeCard(@RequestBody TokenizeRequest req) {
        Actor actor = Actor.optional().orElse(null);
        UUID customerId = null;
        if (actor != null && actor.isCustomer()) {
            try {
                customerId = UUID.fromString(actor.subject());
            } catch (Exception ignored) {}
        }
        if (actor == null) throw ApiException.forbidden("Sign in to save a card");
        return vault.tokenize(new CardTokenVault.CardInput(
                req.pan(), req.expiryMonth(), req.expiryYear(), req.cardholderName(), req.cvv(), customerId,
                ownerKey(actor)
        ));
    }

    /** Who owns a card token: the signed-in user, or the merchant behind an API key. */
    static String ownerKey(Actor actor) {
        return actor.isApiKey() ? "merchant:" + actor.apiKeyMerchant() : actor.subject();
    }

    /** Only the owner (or operations) can see or revoke a card token; others get 404, not a hint it exists. */
    private void requireOwner(String token) {
        Actor actor = Actor.current();
        if (actor.isOps()) return;
        String owner = vault.ownerOf(token).orElse(null);
        if (owner == null || !owner.equals(ownerKey(actor))) {
            throw ApiException.notFound("token_not_found", "Card token not found");
        }
    }

    @GetMapping("/v1/rails/card/tokens/{token}")
    public CardTokenVault.TokenizedCard getToken(@PathVariable String token) {
        requireOwner(token);
        return vault.findByToken(token)
                .orElseThrow(() -> ApiException.notFound("token_not_found", "Card token not found"));
    }

    @DeleteMapping("/v1/rails/card/tokens/{token}")
    public Map<String, String> revokeToken(@PathVariable String token) {
        requireOwner(token);
        vault.revoke(token);
        return Map.of("status", "revoked", "token", token);
    }

    // --- Smart Routing (014) ---
    @GetMapping("/v1/rails/routes/select")
    public SmartPaymentRouter.SelectedRoute selectRoute(
            @RequestParam(defaultValue = "momo") String channel,
            @RequestParam(defaultValue = "mtn") String network,
            @RequestParam(defaultValue = "1000") long amount,
            @RequestParam(defaultValue = "GHS") String currency
    ) {
        return router.selectRoute(channel, network, amount, currency);
    }

    // --- Network Health & Circuit Breaker (016) ---
    @GetMapping("/v1/ops/rails/health/{rail}")
    public NetworkHealthDetector.RailHealth getHealth(@PathVariable String rail) {
        Actor.current().requireOps();
        return health.getHealth(rail);
    }

    // --- Custodian Bank Trust Safeguarding & Outflow Cap Backstop ---
    public record CustodianConfigRequest(
            Long dailyOutflowCapPesewas,
            Long perPayoutCapPesewas,
            Long minReserveFloorPesewas,
            Long lastReconciledBankBalancePesewas,
            Boolean outflowFrozen
    ) {}

    @GetMapping("/v1/ops/custodian/safeguard-report")
    public CustodianBankBackstopService.CustodianSafeguardReport safeguardReport(
            @RequestParam(defaultValue = "GHS") String currency
    ) {
        Actor.current().requireOps();
        return custodianBackstop.verifyTrustSafeguarding(currency);
    }

    @GetMapping("/v1/ops/custodian/config")
    public CustodianBankBackstopService.TrustAccountConfig getCustodianConfig() {
        Actor.current().requireOps();
        return custodianBackstop.getPrimaryConfig();
    }

    @PutMapping("/v1/ops/custodian/config")
    public CustodianBankBackstopService.TrustAccountConfig updateCustodianConfig(
            @RequestBody CustodianConfigRequest request
    ) {
        Actor actor = Actor.current();
        actor.requireOps();
        return custodianBackstop.updateConfig(
                request.dailyOutflowCapPesewas(),
                request.perPayoutCapPesewas(),
                request.minReserveFloorPesewas(),
                request.lastReconciledBankBalancePesewas(),
                request.outflowFrozen(),
                actor.subject()
        );
    }
}
