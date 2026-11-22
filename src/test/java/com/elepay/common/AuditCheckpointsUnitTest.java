package com.elepay.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuditCheckpointsUnitTest {

    static String randomKey(int bytes) {
        byte[] k = new byte[bytes];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    static String pkcs8(String algorithm, java.security.spec.AlgorithmParameterSpec spec) {
        return Base64.getEncoder().encodeToString(IntegrityKeys.generate(algorithm, spec).getPrivate().getEncoded());
    }

    private final IntegrityKeys keys = new IntegrityKeys(
            randomKey(64),
            "",
            pkcs8("Ed25519", null),
            pkcs8("ML-DSA", MLDSAParameterSpec.ml_dsa_65),
            false
    );

    @Test
    @DisplayName("Audit checkpoint hybrid Ed25519 + ML-DSA-65 signature validates correctly and detects tampering")
    void testAuditCheckpointHybridVerification() {
        long lastEntryId = 1500L;
        String lastEntryHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        long entriesCount = 1500L;
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);

        byte[] canonicalMsg = AuditCheckpoints.message(lastEntryId, lastEntryHash, entriesCount, createdAt);
        var sig = keys.sign(canonicalMsg);

        AuditCheckpoints.Checkpoint checkpoint = new AuditCheckpoints.Checkpoint(
                UUID.randomUUID(),
                lastEntryId,
                lastEntryHash,
                entriesCount,
                createdAt,
                sig.keyId(),
                sig.algorithm(),
                sig.ed25519(),
                sig.mldsa65()
        );

        // 1. Valid checkpoint signature verification
        assertThat(AuditCheckpoints.verifyCheckpoint(keys.publicKeys(), checkpoint)).isTrue();

        // 2. Tampered last entry hash
        AuditCheckpoints.Checkpoint tamperedHash = new AuditCheckpoints.Checkpoint(
                checkpoint.id(),
                checkpoint.lastEntryId(),
                "f" + lastEntryHash.substring(1),
                checkpoint.entries(),
                checkpoint.createdAt(),
                checkpoint.keyId(),
                checkpoint.algorithm(),
                checkpoint.ed25519Signature(),
                checkpoint.mldsa65Signature()
        );
        assertThat(AuditCheckpoints.verifyCheckpoint(keys.publicKeys(), tamperedHash)).isFalse();

        // 3. Tampered entry count
        AuditCheckpoints.Checkpoint tamperedCount = new AuditCheckpoints.Checkpoint(
                checkpoint.id(),
                checkpoint.lastEntryId(),
                checkpoint.lastEntryHash(),
                checkpoint.entries() + 1,
                checkpoint.createdAt(),
                checkpoint.keyId(),
                checkpoint.algorithm(),
                checkpoint.ed25519Signature(),
                checkpoint.mldsa65Signature()
        );
        assertThat(AuditCheckpoints.verifyCheckpoint(keys.publicKeys(), tamperedCount)).isFalse();

        // 4. Tampered classical Ed25519 signature
        AuditCheckpoints.Checkpoint tamperedEd25519 = new AuditCheckpoints.Checkpoint(
                checkpoint.id(),
                checkpoint.lastEntryId(),
                checkpoint.lastEntryHash(),
                checkpoint.entries(),
                checkpoint.createdAt(),
                checkpoint.keyId(),
                checkpoint.algorithm(),
                checkpoint.ed25519Signature().substring(0, 10) + "AAAA" + checkpoint.ed25519Signature().substring(14),
                checkpoint.mldsa65Signature()
        );
        assertThat(AuditCheckpoints.verifyCheckpoint(keys.publicKeys(), tamperedEd25519)).isFalse();

        // 5. Tampered post-quantum ML-DSA-65 signature
        AuditCheckpoints.Checkpoint tamperedMldsa = new AuditCheckpoints.Checkpoint(
                checkpoint.id(),
                checkpoint.lastEntryId(),
                checkpoint.lastEntryHash(),
                checkpoint.entries(),
                checkpoint.createdAt(),
                checkpoint.keyId(),
                checkpoint.algorithm(),
                checkpoint.ed25519Signature(),
                checkpoint.mldsa65Signature().substring(0, 10) + "BBBB" + checkpoint.mldsa65Signature().substring(14)
        );
        assertThat(AuditCheckpoints.verifyCheckpoint(keys.publicKeys(), tamperedMldsa)).isFalse();
    }
}
