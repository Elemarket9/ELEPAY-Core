package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.common.Idempotency;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Card tokens belong to whoever saved them; numbers are encrypted under the configured key with keyed fingerprints. */
class CardVaultSecurityIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired CardTokenVault vault;
    @Autowired JdbcClient db;

    Map<String, Object> card() {
        return Map.of("pan", "4111111111111111", "expiryMonth", 12, "expiryYear", 2030,
                "cardholderName", "Ama Mensah", "cvv", "123");
    }

    @Test
    void onlyTheOwnerOrOperationsCanSeeOrRevokeACard() {
        TestApi api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        String ama = api.token("ama-" + s), kofi = api.token("kofi-" + s), ops = api.token("ops-" + s, "ops");
        var saved = api.call("POST", "/v1/rails/card/tokens", ama, null, card());
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
        String token = saved.body().get("token").asString();

        assertThat(api.call("GET", "/v1/rails/card/tokens/" + token, kofi, null, null).status()).isEqualTo(404);
        assertThat(api.call("DELETE", "/v1/rails/card/tokens/" + token, kofi, null, null).status()).isEqualTo(404);
        assertThat(api.call("GET", "/v1/rails/card/tokens/" + token, ama, null, null).status()).isEqualTo(200);
        assertThat(api.call("GET", "/v1/rails/card/tokens/" + token, ops, null, null).status()).isEqualTo(200);

        record Row(String encrypted, String fingerprint, int version) {}
        Row row = db.sql("SELECT encrypted_pan, fingerprint, fingerprint_version FROM card_token WHERE token = :t")
                .param("t", token).query((rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getInt(3))).single();
        assertThat(row.encrypted()).startsWith("v2:").doesNotContain("4111111111111111");
        assertThat(row.version()).isEqualTo(2);
        // The old fingerprint was an unkeyed hash anyone could recompute from a guessed card number.
        assertThat(row.fingerprint()).isNotEqualTo(Idempotency.sha256("elepay:card:fingerprint:4111111111111111"));
        assertThat(vault.decryptPanForRail(token)).isEqualTo("4111111111111111");

        assertThat(api.call("DELETE", "/v1/rails/card/tokens/" + token, ama, null, null).status()).isEqualTo(200);
    }

    @Test
    void cardsSavedByEarlierReleasesAreUpgraded() throws Exception {
        // A row as the old code wrote it: the published default key, no AAD, unkeyed fingerprint.
        String pan = "5123456789012346";
        byte[] key = Arrays.copyOf(CardTokenVault.LEGACY_DEFAULT_KEY.getBytes(StandardCharsets.UTF_8), 32);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(pan.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ct, 0, combined, iv.length, ct.length);
        String token = "tok_card_legacy" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        db.sql("""
                INSERT INTO card_token (id, token, masked_pan, fingerprint, scheme, card_type, issuer_bank, expiry_month,
                    expiry_year, cardholder_name, encrypted_pan, status)
                VALUES (:id, :t, '512345******2346', :fp, 'mastercard', 'debit', 'Bank', 12, 2030, 'Kofi', :enc, 'active')""")
                .param("id", UUID.randomUUID()).param("t", token)
                .param("fp", Idempotency.sha256("elepay:card:fingerprint:" + pan))
                .param("enc", Base64.getEncoder().encodeToString(combined)).update();

        assertThat(vault.upgradeLegacyRows()).isGreaterThanOrEqualTo(1);
        String stored = db.sql("SELECT encrypted_pan FROM card_token WHERE token = :t").param("t", token)
                .query(String.class).single();
        assertThat(stored).startsWith("v2:");
        assertThat(vault.decryptPanForRail(token)).isEqualTo(pan);
        assertThat(vault.upgradeLegacyRows()).isZero();
    }
}
