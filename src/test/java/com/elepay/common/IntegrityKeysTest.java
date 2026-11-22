package com.elepay.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec;
import org.junit.jupiter.api.Test;

class IntegrityKeysTest {
    static String randomKey(int bytes) {
        byte[] k = new byte[bytes];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    static String pkcs8(String algorithm, java.security.spec.AlgorithmParameterSpec spec) {
        return Base64.getEncoder().encodeToString(IntegrityKeys.generate(algorithm, spec).getPrivate().getEncoded());
    }

    final IntegrityKeys keys = new IntegrityKeys(randomKey(64), "", pkcs8("Ed25519", null),
            pkcs8("ML-DSA", MLDSAParameterSpec.ml_dsa_65), false);
    final byte[] message = "elepay-audit-checkpoint:v1\n42\nabc\n42\n2026-11-22T12:00:00Z".getBytes(StandardCharsets.UTF_8);

    @Test
    void hybridSignatureVerifiesOnlyWhenBothHalvesHold() {
        var sig = keys.sign(message);
        var pub = keys.publicKeys();
        assertThat(sig.algorithm()).isEqualTo("Ed25519+ML-DSA-65");
        assertThat(IntegrityKeys.verify(pub, message, sig.ed25519(), sig.mldsa65())).isTrue();

        byte[] altered = message.clone();
        altered[altered.length - 1] ^= 1;
        assertThat(IntegrityKeys.verify(pub, altered, sig.ed25519(), sig.mldsa65())).isFalse();

        // A valid classical half with a post-quantum half from another message is rejected, and vice versa.
        var other = keys.sign(altered);
        assertThat(IntegrityKeys.verify(pub, message, sig.ed25519(), other.mldsa65())).isFalse();
        assertThat(IntegrityKeys.verify(pub, message, other.ed25519(), sig.mldsa65())).isFalse();
    }

    @Test
    void keysLoadedFromConfigurationKeepTheirIdentity() {
        String ed = pkcs8("Ed25519", null), ml = pkcs8("ML-DSA", MLDSAParameterSpec.ml_dsa_65), mac = randomKey(64);
        var a = new IntegrityKeys(mac, "", ed, ml, false);
        var b = new IntegrityKeys(mac, "", ed, ml, false);
        assertThat(a.signingKeyId()).isEqualTo(b.signingKeyId());
        var sig = a.sign(message);
        assertThat(IntegrityKeys.verify(b.publicKeys(), message, sig.ed25519(), sig.mldsa65())).isTrue();
    }

    @Test
    void macKeysRotateWithoutBreakingOldRecords() {
        String oldKey = randomKey(64), newKey = randomKey(64);
        var before = new IntegrityKeys(oldKey, "", "", "", true);
        String oldMac = before.mac("entry");
        var after = new IntegrityKeys(newKey, oldKey, "", "", true);
        assertThat(after.macKeyId()).isNotEqualTo(before.macKeyId());
        assertThat(after.verifyMac(before.macKeyId(), "entry", oldMac)).isTrue();
        assertThat(after.verifyMac(before.macKeyId(), "entry!", oldMac)).isFalse();
        assertThat(after.verifyMac("unknown", "entry", oldMac)).isFalse();
        assertThat(after.mac("x")).hasSize(128); // HMAC-SHA-512, hex
    }

    @Test
    void productionKeysAreRequired() {
        assertThatThrownBy(() -> new IntegrityKeys("", "", "", "", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new IntegrityKeys(randomKey(16), "", "", "", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new IntegrityKeys(randomKey(64), "", "", "", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("required");
    }
}
