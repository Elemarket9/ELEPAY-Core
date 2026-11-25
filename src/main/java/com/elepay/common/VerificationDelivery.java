package com.elepay.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Dual-channel verification code delivery over Email and SMS.
 *
 * <p>Supports real provider integrations for Ghana and international deployments:
 * <ul>
 *   <li>SMS: {@code arkesel}, {@code hubtel}, {@code twilio}, {@code webhook}, or {@code log} (non-prod only).</li>
 *   <li>Email: {@code resend}, {@code sendgrid}, {@code webhook}, or {@code log} (non-prod only).</li>
 * </ul>
 *
 * <p>In production profiles, {@code log} mode fails closed so verification codes are never silently dropped.
 * Raw verification codes are never written to production logs.
 */
@Component
public class VerificationDelivery {
    private static final Logger log = LoggerFactory.getLogger(VerificationDelivery.class);

    public record DeliveryReceipt(
            boolean emailSent,
            boolean smsSent,
            String emailChannel,
            String smsChannel,
            String maskedEmail,
            String maskedPhone
    ) {}

    public record DispatchedMessage(String channel, String destination, String subject, String code) {}

    private final RestClient http;
    private final Environment env;

    private final String smsProvider;
    private final String smsApiUrl;
    private final String smsApiKey;
    private final String smsApiSecret;
    private final String smsSenderId;

    private final String emailProvider;
    private final String emailApiUrl;
    private final String emailApiKey;
    private final String emailFrom;

    // Non-production capture store so automated tests can assert exact dispatched codes without logging them.
    private final Map<String, DispatchedMessage> lastDispatchedByKey = new ConcurrentHashMap<>();

    public VerificationDelivery(
            RestClient.Builder restClientBuilder,
            Environment env,
            @Value("${elepay.notifications.sms.provider:log}") String smsProvider,
            @Value("${elepay.notifications.sms.api-url:}") String smsApiUrl,
            @Value("${elepay.notifications.sms.api-key:}") String smsApiKey,
            @Value("${elepay.notifications.sms.api-secret:}") String smsApiSecret,
            @Value("${elepay.notifications.sms.sender-id:ELEPAY}") String smsSenderId,
            @Value("${elepay.notifications.email.provider:log}") String emailProvider,
            @Value("${elepay.notifications.email.api-url:}") String emailApiUrl,
            @Value("${elepay.notifications.email.api-key:}") String emailApiKey,
            @Value("${elepay.notifications.email.from:no-reply@elepay.gh}") String emailFrom) {
        this.http = restClientBuilder.build();
        this.env = env;
        this.smsProvider = normalize(smsProvider, "log");
        this.smsApiUrl = smsApiUrl == null ? "" : smsApiUrl.trim();
        this.smsApiKey = smsApiKey == null ? "" : smsApiKey.trim();
        this.smsApiSecret = smsApiSecret == null ? "" : smsApiSecret.trim();
        this.smsSenderId = smsSenderId == null || smsSenderId.isBlank() ? "ELEPAY" : smsSenderId.trim();
        this.emailProvider = normalize(emailProvider, "log");
        this.emailApiUrl = emailApiUrl == null ? "" : emailApiUrl.trim();
        this.emailApiKey = emailApiKey == null ? "" : emailApiKey.trim();
        this.emailFrom = emailFrom == null || emailFrom.isBlank() ? "no-reply@elepay.gh" : emailFrom.trim();
    }

    /**
     * Dispatches verification codes to both Email and SMS when provided.
     * Supports separate codes per channel (for dual-channel login/registration verification)
     * or the same code across both channels (for settlement destination OOB confirmation).
     */
    public DeliveryReceipt sendBoth(
            String email, String emailCode,
            String phone, String smsCode,
            String subject, String purposeLabel) {
        boolean emailSent = false;
        boolean smsSent = false;

        if (email != null && !email.isBlank() && emailCode != null && !emailCode.isBlank()) {
            sendEmail(email.trim(), subject, purposeLabel, emailCode.trim());
            emailSent = true;
        }
        if (phone != null && !phone.isBlank() && smsCode != null && !smsCode.isBlank()) {
            sendSms(phone.trim(), purposeLabel, smsCode.trim());
            smsSent = true;
        }

        if (!emailSent && !smsSent) {
            throw ApiException.badRequest("verification_destination_required",
                    "At least one email address or phone number is required for verification");
        }

        return new DeliveryReceipt(
                emailSent,
                smsSent,
                emailSent ? emailProvider : "none",
                smsSent ? smsProvider : "none",
                maskEmail(email),
                maskPhone(phone)
        );
    }

    public void sendEmail(String email, String subject, String purposeLabel, String code) {
        String body = "Your ELEPAY " + purposeLabel + " verification code is: " + code
                + ". This code expires in 10 minutes. Never share this code with anyone.";
        if ("log".equals(emailProvider)) {
            assertNonProdOrFailClosed("email");
            lastDispatchedByKey.put("email:" + email.toLowerCase(), new DispatchedMessage("email", email, subject, code));
            log.info("Verification email dispatched (non-prod log mode) to {} for {}", maskEmail(email), purposeLabel);
            return;
        }
        switch (emailProvider) {
            case "resend" -> {
                String url = emailApiUrl.isBlank() ? "https://api.resend.com/emails" : emailApiUrl;
                requireConfigured("elepay.notifications.email.api-key", emailApiKey);
                http.post().uri(url)
                        .header("Authorization", "Bearer " + emailApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "from", emailFrom,
                                "to", new String[]{email},
                                "subject", subject,
                                "text", body
                        ))
                        .retrieve().toBodilessEntity();
            }
            case "sendgrid" -> {
                String url = emailApiUrl.isBlank() ? "https://api.sendgrid.com/v3/mail/send" : emailApiUrl;
                requireConfigured("elepay.notifications.email.api-key", emailApiKey);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("personalizations", new Object[]{Map.of("to", new Object[]{Map.of("email", email)})});
                payload.put("from", Map.of("email", emailFrom));
                payload.put("subject", subject);
                payload.put("content", new Object[]{Map.of("type", "text/plain", "value", body)});
                http.post().uri(url)
                        .header("Authorization", "Bearer " + emailApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve().toBodilessEntity();
            }
            case "webhook" -> {
                requireConfigured("elepay.notifications.email.api-url", emailApiUrl);
                var req = http.post().uri(emailApiUrl).contentType(MediaType.APPLICATION_JSON);
                if (!emailApiKey.isBlank()) {
                    req = req.header("Authorization", "Bearer " + emailApiKey);
                }
                req.body(Map.of(
                        "channel", "email",
                        "from", emailFrom,
                        "to", email,
                        "subject", subject,
                        "text", body
                )).retrieve().toBodilessEntity();
            }
            default -> throw new IllegalStateException("Unsupported email verification provider: " + emailProvider);
        }
        log.info("Verification email dispatched via {} to {} for {}", emailProvider, maskEmail(email), purposeLabel);
    }

    public void sendSms(String phone, String purposeLabel, String code) {
        String text = "ELEPAY " + purposeLabel + " code: " + code + ". Valid for 10 mins. Do not share.";
        if ("log".equals(smsProvider)) {
            assertNonProdOrFailClosed("sms");
            lastDispatchedByKey.put("sms:" + phone, new DispatchedMessage("sms", phone, purposeLabel, code));
            log.info("Verification SMS dispatched (non-prod log mode) to {} for {}", maskPhone(phone), purposeLabel);
            return;
        }
        switch (smsProvider) {
            case "arkesel" -> {
                String url = smsApiUrl.isBlank() ? "https://sms.arkesel.com/api/v2/sms/send" : smsApiUrl;
                requireConfigured("elepay.notifications.sms.api-key", smsApiKey);
                http.post().uri(url)
                        .header("api-key", smsApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "sender", smsSenderId,
                                "message", text,
                                "recipients", new String[]{phone}
                        ))
                        .retrieve().toBodilessEntity();
            }
            case "hubtel" -> {
                String url = smsApiUrl.isBlank() ? "https://smsc.hubtel.com/v1/messages/send" : smsApiUrl;
                requireConfigured("elepay.notifications.sms.api-key", smsApiKey);
                requireConfigured("elepay.notifications.sms.api-secret", smsApiSecret);
                String basic = Base64.getEncoder().encodeToString((smsApiKey + ":" + smsApiSecret).getBytes(StandardCharsets.UTF_8));
                http.post().uri(url)
                        .header("Authorization", "Basic " + basic)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "From", smsSenderId,
                                "To", phone,
                                "Content", text
                        ))
                        .retrieve().toBodilessEntity();
            }
            case "twilio" -> {
                requireConfigured("elepay.notifications.sms.api-key", smsApiKey);
                requireConfigured("elepay.notifications.sms.api-secret", smsApiSecret);
                String url = smsApiUrl.isBlank()
                        ? "https://api.twilio.com/2010-04-01/Accounts/" + smsApiKey + "/Messages.json"
                        : smsApiUrl;
                String basic = Base64.getEncoder().encodeToString((smsApiKey + ":" + smsApiSecret).getBytes(StandardCharsets.UTF_8));
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("From", smsSenderId);
                form.add("To", phone);
                form.add("Body", text);
                http.post().uri(url)
                        .header("Authorization", "Basic " + basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve().toBodilessEntity();
            }
            case "webhook" -> {
                requireConfigured("elepay.notifications.sms.api-url", smsApiUrl);
                var req = http.post().uri(smsApiUrl).contentType(MediaType.APPLICATION_JSON);
                if (!smsApiKey.isBlank()) {
                    req = req.header("Authorization", "Bearer " + smsApiKey);
                }
                req.body(Map.of(
                        "channel", "sms",
                        "from", smsSenderId,
                        "to", phone,
                        "message", text
                )).retrieve().toBodilessEntity();
            }
            default -> throw new IllegalStateException("Unsupported SMS verification provider: " + smsProvider);
        }
        log.info("Verification SMS dispatched via {} to {} for {}", smsProvider, maskPhone(phone), purposeLabel);
    }

    /** Test/dev-only helper to inspect the last dispatched code for a channel and destination. */
    public DispatchedMessage lastDispatchedForTest(String channel, String destination) {
        if (isProduction()) return null;
        if (destination == null) return null;
        String key = "email".equalsIgnoreCase(channel)
                ? "email:" + destination.trim().toLowerCase()
                : "sms:" + destination.trim();
        return lastDispatchedByKey.get(key);
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return null;
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String digits = phone.replaceAll("\\s+", "");
        if (digits.length() <= 4) return "****";
        return digits.substring(0, Math.min(3, digits.length() - 4))
                + "****" + digits.substring(digits.length() - 3);
    }

    private void assertNonProdOrFailClosed(String channel) {
        if (isProduction()) {
            throw new IllegalStateException(
                    "Production " + channel + " verification provider must be configured (log mode is disabled in prod)");
        }
    }

    private boolean isProduction() {
        return env != null && env.acceptsProfiles(Profiles.of("prod", "production"))
                && !env.acceptsProfiles(Profiles.of("test", "dev", "sandbox"));
    }

    private static void requireConfigured(String prop, String val) {
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Missing required notification property: " + prop);
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase();
    }
}
