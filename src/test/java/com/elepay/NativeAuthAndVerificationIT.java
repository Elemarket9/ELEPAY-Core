package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.common.VerificationDelivery;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

class NativeAuthAndVerificationIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired VerificationDelivery verificationDelivery;

    @Test
    void registerVerifyBothEmailAndSmsLoginAndResetPasswordWorkEndToEnd() {
        var http = RestClient.create("http://127.0.0.1:" + port);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String email = "founder-" + unique + "@elepay.gh";
        String phone = "+23324" + String.format("%07d", Math.abs(unique.hashCode()) % 10_000_000);

        // 1. Register -> must dispatch BOTH email and SMS verification codes
        JsonNode reg = http.post().uri("/v1/auth/register")
                .body(Map.of(
                        "fullName", "Ama Mensah",
                        "email", email,
                        "phone", phone,
                        "password", "StrongPass123!"))
                .retrieve().body(JsonNode.class);

        assertThat(reg).isNotNull();
        String challengeId = reg.get("challengeId").asString();
        assertThat(reg.get("purpose").asString()).isEqualTo("register");
        assertThat(reg.get("channels").toString()).contains("email", "sms");
        assertThat(reg.toString()).doesNotContain("code"); // never leak raw codes on API

        var emailMsg = verificationDelivery.lastDispatchedForTest("email", email);
        var smsMsg = verificationDelivery.lastDispatchedForTest("sms", phone);
        assertThat(emailMsg).isNotNull();
        assertThat(smsMsg).isNotNull();
        assertThat(emailMsg.code()).matches("\\d{6}");
        assertThat(smsMsg.code()).matches("\\d{6}");

        // 2. Wrong SMS code must fail with 403
        int wrongStatus = http.post().uri("/v1/auth/verify")
                .body(Map.of("challengeId", challengeId, "emailCode", emailMsg.code(), "smsCode", "000000"))
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(wrongStatus).isEqualTo(403);

        // 3. Providing ONLY email code verifies email but does NOT issue a token yet
        JsonNode partial = http.post().uri("/v1/auth/verify")
                .body(Map.of("challengeId", challengeId, "emailCode", emailMsg.code(), "smsCode", ""))
                .retrieve().body(JsonNode.class);
        assertThat(partial.get("verified").asBoolean()).isFalse();
        assertThat(partial.get("emailVerified").asBoolean()).isTrue();
        assertThat(partial.get("smsVerified").asBoolean()).isFalse();
        assertThat(partial.get("accessToken").isNull()).isTrue();

        // 4. Completing with SMS code verifies both channels and issues a valid Bearer JWT
        JsonNode verified = http.post().uri("/v1/auth/verify")
                .body(Map.of("challengeId", challengeId, "emailCode", "", "smsCode", smsMsg.code()))
                .retrieve().body(JsonNode.class);
        assertThat(verified.get("verified").asBoolean()).isTrue();
        assertThat(verified.get("emailVerified").asBoolean()).isTrue();
        assertThat(verified.get("smsVerified").asBoolean()).isTrue();
        String token = verified.get("accessToken").asString();
        assertThat(token).isNotBlank();

        // 5. Issued JWT authenticates against protected API (/v1/merchants returns 200 [])
        int merchantsStatus = http.get().uri("/v1/merchants")
                .headers(h -> h.setBearerAuth(token))
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(merchantsStatus).isEqualTo(200);

        // 6. Login by phone number (Ghana 024... format) works and dispatches fresh Email + SMS codes
        String localPhone = "0" + phone.substring(4);
        JsonNode login = http.post().uri("/v1/auth/login")
                .body(Map.of("identifier", localPhone, "password", "StrongPass123!"))
                .retrieve().body(JsonNode.class);
        String loginChallengeId = login.get("challengeId").asString();
        var loginEmail = verificationDelivery.lastDispatchedForTest("email", email);
        var loginSms = verificationDelivery.lastDispatchedForTest("sms", phone);
        JsonNode loginVerified = http.post().uri("/v1/auth/verify")
                .body(Map.of("challengeId", loginChallengeId, "emailCode", loginEmail.code(), "smsCode", loginSms.code()))
                .retrieve().body(JsonNode.class);
        assertThat(loginVerified.get("verified").asBoolean()).isTrue();
        assertThat(loginVerified.get("accessToken").asString()).isNotBlank();

        // 7. Password reset requires both Email + SMS codes, and reset challenge CANNOT be used on /v1/auth/verify (CRIT-01)
        JsonNode forgot = http.post().uri("/v1/auth/forgot-password")
                .body(Map.of("identifier", email))
                .retrieve().body(JsonNode.class);
        String resetChallengeId = forgot.get("challengeId").asString();
        var resetEmail = verificationDelivery.lastDispatchedForTest("email", email);
        var resetSms = verificationDelivery.lastDispatchedForTest("sms", phone);
        int hijackStatus = http.post().uri("/v1/auth/verify")
                .body(Map.of("challengeId", resetChallengeId, "emailCode", resetEmail.code(), "smsCode", resetSms.code()))
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(hijackStatus).isEqualTo(403);
        JsonNode resetRes = http.post().uri("/v1/auth/reset-password")
                .body(Map.of(
                        "challengeId", resetChallengeId,
                        "emailCode", resetEmail.code(),
                        "smsCode", resetSms.code(),
                        "newPassword", "NewStrongPass456!"))
                .retrieve().body(JsonNode.class);
        assertThat(resetRes.get("reset").asBoolean()).isTrue();

        // Old password fails, new password succeeds
        int oldPwdStatus = http.post().uri("/v1/auth/login")
                .body(Map.of("identifier", email, "password", "StrongPass123!"))
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(oldPwdStatus).isEqualTo(401);

        int newPwdStatus = http.post().uri("/v1/auth/login")
                .body(Map.of("identifier", email, "password", "NewStrongPass456!"))
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(newPwdStatus).isEqualTo(200);

        // 8. /v1/auth/me returns authoritative caller profile & non-admin is blocked from /v1/auth/admin/accounts
        JsonNode me = http.get().uri("/v1/auth/me")
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(JsonNode.class);
        assertThat(me.get("subject").asString()).isEqualTo(email);
        assertThat(me.get("isOps").asBoolean()).isFalse();

        int nonAdminStatus = http.get().uri("/v1/auth/admin/accounts")
                .headers(h -> h.setBearerAuth(token))
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(nonAdminStatus).isEqualTo(403);

        // 9. ops_admin can promote account to finance_ops and /v1/auth/me reflects it on next sign-in
        String opsAdminToken = http.post().uri("/dev/token")
                .body(Map.of("subject", "root-admin@elepay.gh", "roles", java.util.List.of("ops_admin")))
                .retrieve().body(JsonNode.class).get("accessToken").asString();
        JsonNode accounts = http.get().uri("/v1/auth/admin/accounts")
                .headers(h -> h.setBearerAuth(opsAdminToken))
                .retrieve().body(JsonNode.class);
        String accountId = null;
        for (JsonNode row : accounts) {
            if (email.equals(row.get("email").asString())) {
                accountId = row.get("id").asString();
                break;
            }
        }
        assertThat(accountId).isNotNull();

        JsonNode updated = http.patch().uri("/v1/auth/admin/accounts/" + accountId + "/roles")
                .headers(h -> h.setBearerAuth(opsAdminToken))
                .body(Map.of("roles", java.util.List.of("finance_ops"), "status", "active"))
                .retrieve().body(JsonNode.class);
        assertThat(updated.get("roles").toString()).contains("finance_ops");

        JsonNode relogin = http.post().uri("/v1/auth/login")
                .body(Map.of("identifier", email, "password", "NewStrongPass456!"))
                .retrieve().body(JsonNode.class);
        var reloginEmail = verificationDelivery.lastDispatchedForTest("email", email);
        var reloginSms = verificationDelivery.lastDispatchedForTest("sms", phone);
        JsonNode reloginVerified = http.post().uri("/v1/auth/verify")
                .body(Map.of("challengeId", relogin.get("challengeId").asString(),
                        "emailCode", reloginEmail.code(), "smsCode", reloginSms.code()))
                .retrieve().body(JsonNode.class);
        JsonNode promotedMe = http.get().uri("/v1/auth/me")
                .headers(h -> h.setBearerAuth(reloginVerified.get("accessToken").asString()))
                .retrieve().body(JsonNode.class);
        assertThat(promotedMe.get("isOps").asBoolean()).isTrue();
        assertThat(promotedMe.get("primaryRole").asString()).isEqualTo("finance_ops");
    }
}
