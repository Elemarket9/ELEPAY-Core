package com.elepay.developer;

import com.elepay.identity.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Developer platform endpoints for merchant self-service API credentials,
 * webhook configuration, real-time delivery logs, replay, SDK snippets,
 * and partner integration certification.
 */
@RestController
@RequestMapping("/v1/developer")
public class DeveloperController {

    private final DeveloperService service;

    public DeveloperController(DeveloperService service) {
        this.service = service;
    }

    // --- Request DTOs ---

    public record KeyRequest(
            @Size(max = 100) String label,
            @Pattern(regexp = "live|test") String environment,
            List<@Pattern(regexp = "view|collect|refund|withdraw|payouts") String> scopes
    ) {}

    public record RotateKeyRequest(@Size(max = 100) String label) {}

    public record WebhookRegisterRequest(
            @NotBlank @Pattern(regexp = "https?://.+") @Size(max = 500) String url,
            @Pattern(regexp = "live|test") String environment,
            @Size(max = 255) String description,
            List<String> eventTypes
    ) {}

    public record WebhookUpdateRequest(
            @Pattern(regexp = "https?://.+") @Size(max = 500) String url,
            @Size(max = 255) String description,
            Boolean active,
            List<String> eventTypes
    ) {}

    public record PartnerCertRequest(
            @NotBlank @Size(max = 128) String partnerName,
            @NotBlank @Size(max = 64) String partnerType,
            Map<String, Object> checklist
    ) {}

    // --- API Key Endpoints ---

    @PostMapping("/merchants/{merchantId}/keys")
    public DeveloperService.ApiKeyCreated createKey(
            @PathVariable UUID merchantId,
            @Valid @RequestBody KeyRequest request
    ) {
        Actor actor = Actor.current();
        actor.requireUser();
        return service.createApiKey(merchantId, request.label(), request.environment(), request.scopes(), actor.subject());
    }

    @GetMapping("/merchants/{merchantId}/keys")
    public List<DeveloperService.ApiKeyView> listKeys(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) String environment
    ) {
        return service.listApiKeys(merchantId, environment);
    }

    @DeleteMapping("/merchants/{merchantId}/keys/{keyId}")
    public Map<String, String> revokeKey(
            @PathVariable UUID merchantId,
            @PathVariable UUID keyId
    ) {
        Actor actor = Actor.current();
        actor.requireUser();
        boolean revoked = service.revokeApiKey(merchantId, keyId, actor.subject());
        return Map.of("status", revoked ? "revoked" : "not_found");
    }

    @PostMapping("/merchants/{merchantId}/keys/{keyId}/rotate")
    public DeveloperService.ApiKeyCreated rotateKey(
            @PathVariable UUID merchantId,
            @PathVariable UUID keyId,
            @RequestBody(required = false) RotateKeyRequest request
    ) {
        Actor actor = Actor.current();
        actor.requireUser();
        String label = (request != null) ? request.label() : null;
        return service.rotateApiKey(merchantId, keyId, label, actor.subject());
    }

    // --- Webhook Endpoints ---

    @PostMapping("/merchants/{merchantId}/webhooks")
    public DeveloperService.WebhookEndpointCreated registerWebhook(
            @PathVariable UUID merchantId,
            @Valid @RequestBody WebhookRegisterRequest request
    ) {
        Actor actor = Actor.current();
        actor.requireUser();
        return service.registerWebhook(merchantId, request.url(), request.environment(),
                request.eventTypes(), request.description(), actor.subject());
    }

    @GetMapping("/merchants/{merchantId}/webhooks")
    public List<DeveloperService.WebhookEndpointView> listWebhooks(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) String environment
    ) {
        return service.listWebhooks(merchantId, environment);
    }

    @PutMapping("/merchants/{merchantId}/webhooks/{endpointId}")
    public Map<String, Object> updateWebhook(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            @Valid @RequestBody WebhookUpdateRequest request
    ) {
        boolean ok = service.updateWebhook(merchantId, endpointId, request.url(),
                request.eventTypes(), request.active(), request.description());
        return Map.of("updated", ok);
    }

    @PostMapping("/merchants/{merchantId}/webhooks/{endpointId}/secret/rotate")
    public Map<String, String> rotateWebhookSecret(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId
    ) {
        Actor actor = Actor.current();
        actor.requireUser();
        String newSecret = service.rotateWebhookSecret(merchantId, endpointId, actor.subject());
        return Map.of("secret", newSecret);
    }

    @PostMapping("/merchants/{merchantId}/webhooks/{endpointId}/ping")
    public DeveloperService.ReplayResult pingWebhook(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId
    ) {
        return service.pingWebhook(merchantId, endpointId);
    }

    // --- Webhook Delivery Logs & Replay ---

    @GetMapping("/merchants/{merchantId}/webhooks/deliveries")
    public List<DeveloperService.WebhookDeliveryLogView> listDeliveryLogs(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return service.listDeliveryLogs(merchantId, endpointId, status, limit);
    }

    @GetMapping("/merchants/{merchantId}/webhooks/deliveries/{logId}")
    public DeveloperService.WebhookDeliveryLogView getDeliveryLog(
            @PathVariable UUID merchantId,
            @PathVariable UUID logId
    ) {
        return service.getDeliveryLog(merchantId, logId);
    }

    @PostMapping("/merchants/{merchantId}/webhooks/deliveries/{logId}/replay")
    public DeveloperService.ReplayResult replayDelivery(
            @PathVariable UUID merchantId,
            @PathVariable UUID logId
    ) {
        Actor actor = Actor.current();
        return service.replayDelivery(merchantId, logId, actor.subject());
    }

    // --- SDK Code Snippet Generators ---

    @GetMapping("/snippets")
    public DeveloperService.SdkSnippet getSnippet(
            @RequestParam(defaultValue = "create_checkout") String action,
            @RequestParam(defaultValue = "curl") String language,
            @RequestParam(required = false) String apiKey,
            @RequestParam(required = false) String baseUrl
    ) {
        return service.generateSnippet(action, language, apiKey, baseUrl);
    }

    // --- OpenAPI Explorer Summary ---

    @GetMapping("/openapi-summary")
    public Map<String, Object> openApiSummary() {
        return Map.of(
                "platform", "ELEPAY Developer Platform",
                "version", "1.0.0",
                "openApiDocsUrl", "/v3/api-docs",
                "authentication", Map.of(
                        "header", "X-Elepay-Api-Key",
                        "formats", List.of("ek_live_...", "ek_test_...")
                ),
                "supportedEvents", List.of(
                        "checkout.paid",
                        "payment.failed",
                        "payout.completed",
                        "refund.created",
                        "mandate.active"
                ),
                "supportedLanguages", List.of("curl", "nodejs", "python", "php", "java")
        );
    }

    // --- Partner Certification ---

    @PostMapping("/merchants/{merchantId}/certifications")
    public DeveloperService.PartnerCertificationView submitCertification(
            @PathVariable UUID merchantId,
            @Valid @RequestBody PartnerCertRequest request
    ) {
        Actor actor = Actor.current();
        actor.requireUser();
        return service.submitCertification(merchantId, request.partnerName(), request.partnerType(),
                request.checklist(), actor.subject());
    }

    @GetMapping("/merchants/{merchantId}/certifications")
    public List<DeveloperService.PartnerCertificationView> listCertifications(@PathVariable UUID merchantId) {
        return service.listCertifications(merchantId);
    }

    @PostMapping("/merchants/{merchantId}/certifications/{certId}/test")
    public DeveloperService.PartnerCertificationView runCertificationTests(
            @PathVariable UUID merchantId,
            @PathVariable UUID certId
    ) {
        Actor actor = Actor.current();
        return service.runCertificationTests(merchantId, certId, actor.subject());
    }
}
