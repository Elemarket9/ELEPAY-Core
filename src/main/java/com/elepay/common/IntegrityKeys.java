package com.elepay.common;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.jcajce.interfaces.EdDSAPrivateKey;
import org.bouncycastle.jcajce.interfaces.MLDSAPrivateKey;
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Keys and primitives for tamper evidence, chosen to stay secure against quantum computers.
 *
 * <ul>
 *   <li><b>Keyed integrity</b>: HMAC-SHA-512 under a 256-bit or longer secret. Symmetric MACs keep their
 *       security against quantum attack (Grover's algorithm only halves the effective key length), and a
 *       keyed MAC means an insider with database access cannot recompute a valid chain.</li>
 *   <li><b>Hybrid signatures</b>: every signature is made twice, with Ed25519 and with ML-DSA-65
 *       (FIPS 204, NIST security category 3). A signature verifies only if both do, so it holds unless both
 *       the classical and the post-quantum scheme are broken.</li>
 *   <li><b>Crypto-agility</b>: every stored value names its algorithm and key id; previous MAC keys stay
 *       usable for verification and every signing key's public half is recorded, so keys can rotate.</li>
 * </ul>
 *
 * <p>In production the keys come from configuration backed by a secrets manager or HSM; development
 * profiles may let the application generate throwaway signing keys.
 */
@Component
public class IntegrityKeys {
    private static final Logger log = LoggerFactory.getLogger(IntegrityKeys.class);
    public static final String MAC_ALGORITHM = "HMAC-SHA-512";
    public static final String SIGNATURE_ALGORITHM = "Ed25519+ML-DSA-65";
    static final String BC = BouncyCastleProvider.PROVIDER_NAME;

    static {
        if (Security.getProvider(BC) == null) Security.addProvider(new BouncyCastleProvider());
    }

    private final String macKeyId;
    private final Map<String, byte[]> macKeys = new LinkedHashMap<>();
    private final KeyPair ed25519;
    private final KeyPair mldsa;
    private final String signingKeyId;

    public IntegrityKeys(@Value("${elepay.integrity.mac-key:}") String macKey,
                         @Value("${elepay.integrity.previous-mac-keys:}") String previousMacKeys,
                         @Value("${elepay.integrity.ed25519-private-key:}") String ed25519Key,
                         @Value("${elepay.integrity.mldsa65-private-key:}") String mldsaKey,
                         @Value("${elepay.integrity.allow-ephemeral-signing-keys:false}") boolean allowEphemeral) {
        byte[] current = macKey(macKey);
        this.macKeyId = keyId(current);
        macKeys.put(macKeyId, current);
        for (String previous : previousMacKeys.split(",")) {
            if (!previous.isBlank()) {
                byte[] k = macKey(previous.trim());
                macKeys.putIfAbsent(keyId(k), k);
            }
        }
        if (ed25519Key.isBlank() || mldsaKey.isBlank()) {
            if (!allowEphemeral) {
                throw new IllegalStateException("elepay.integrity.ed25519-private-key and mldsa65-private-key are required");
            }
            log.warn("Using throwaway integrity signing keys: signatures will not verify after a restart");
            this.ed25519 = generate("Ed25519", null);
            this.mldsa = generate("ML-DSA", MLDSAParameterSpec.ml_dsa_65);
        } else {
            this.ed25519 = load("Ed25519", ed25519Key);
            this.mldsa = load("ML-DSA", mldsaKey);
        }
        this.signingKeyId = keyId(concat(ed25519.getPublic().getEncoded(), mldsa.getPublic().getEncoded()));
    }

    // ---- keyed integrity (MAC) ---------------------------------------------------------------

    static byte[] macKey(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("elepay.integrity.mac-key is required (base64 of at least 32 random bytes)");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("elepay.integrity.mac-key must be base64", e);
        }
        if (key.length < 32) throw new IllegalStateException("elepay.integrity.mac-key must be at least 32 bytes");
        return key;
    }

    /** The id of the key new MACs use. */
    public String macKeyId() { return macKeyId; }

    /** HMAC-SHA-512 of {@code data} under the current key, as hex. */
    public String mac(String data) {
        return mac(macKeyId, data);
    }

    /** HMAC-SHA-512 under a specific key, for verifying older records. */
    public String mac(String keyId, String data) {
        byte[] key = macKeys.get(keyId);
        if (key == null) throw new IllegalStateException("Unknown integrity MAC key " + keyId);
        try {
            Mac m = Mac.getInstance("HmacSHA512");
            m.init(new SecretKeySpec(key, "HmacSHA512"));
            return HexFormat.of().formatHex(m.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time check of a stored MAC. False for an unknown key. */
    public boolean verifyMac(String keyId, String data, String expected) {
        if (expected == null || !macKeys.containsKey(keyId)) return false;
        return MessageDigest.isEqual(mac(keyId, data).getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    // ---- hybrid signatures -------------------------------------------------------------------

    public record HybridSignature(String keyId, String algorithm, String ed25519, String mldsa65) {}

    public record PublicKeys(String keyId, String algorithm, String ed25519, String mldsa65) {}

    public String signingKeyId() { return signingKeyId; }

    public PublicKeys publicKeys() {
        Base64.Encoder b64 = Base64.getEncoder();
        return new PublicKeys(signingKeyId, SIGNATURE_ALGORITHM, b64.encodeToString(ed25519.getPublic().getEncoded()),
                b64.encodeToString(mldsa.getPublic().getEncoded()));
    }

    public HybridSignature sign(byte[] data) {
        Base64.Encoder b64 = Base64.getEncoder();
        return new HybridSignature(signingKeyId, SIGNATURE_ALGORITHM,
                b64.encodeToString(signWith("Ed25519", ed25519.getPrivate(), data)),
                b64.encodeToString(signWith("ML-DSA", mldsa.getPrivate(), data)));
    }

    /** Verifies both halves against the given public keys. Both must hold. */
    public static boolean verify(PublicKeys keys, byte[] data, String ed25519Signature, String mldsaSignature) {
        try {
            Base64.Decoder d = Base64.getDecoder();
            PublicKey ed = KeyFactory.getInstance("Ed25519", BC).generatePublic(new X509EncodedKeySpec(d.decode(keys.ed25519())));
            PublicKey ml = KeyFactory.getInstance("ML-DSA", BC).generatePublic(new X509EncodedKeySpec(d.decode(keys.mldsa65())));
            return verifyWith("Ed25519", ed, data, d.decode(ed25519Signature))
                    && verifyWith("ML-DSA", ml, data, d.decode(mldsaSignature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Records the current public keys so signatures stay verifiable after the keys rotate. */
    public void register(JdbcClient db) {
        PublicKeys k = publicKeys();
        db.sql("""
                INSERT INTO integrity_key (key_id, algorithm, ed25519_public, mldsa65_public)
                VALUES (:id, :alg, :ed, :ml) ON CONFLICT (key_id) DO NOTHING""")
                .param("id", k.keyId()).param("alg", k.algorithm()).param("ed", k.ed25519()).param("ml", k.mldsa65())
                .update();
    }

    private static byte[] signWith(String algorithm, PrivateKey key, byte[] data) {
        try {
            Signature s = Signature.getInstance(algorithm, BC);
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(algorithm + " signing failed", e);
        }
    }

    private static boolean verifyWith(String algorithm, PublicKey key, byte[] data, byte[] signature)
            throws GeneralSecurityException {
        Signature s = Signature.getInstance(algorithm, BC);
        s.initVerify(key);
        s.update(data);
        return s.verify(signature);
    }

    // ---- key handling ------------------------------------------------------------------------

    /** Generates a key pair, printed as base64 PKCS#8 by {@link #main} for operators. */
    static KeyPair generate(String algorithm, java.security.spec.AlgorithmParameterSpec spec) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance(algorithm, BC);
            if (spec != null) g.initialize(spec);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static KeyPair load(String algorithm, String base64Pkcs8) {
        try {
            PrivateKey priv = KeyFactory.getInstance(algorithm, BC)
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64Pkcs8.trim())));
            PublicKey pub = priv instanceof MLDSAPrivateKey ml ? ml.getPublicKey()
                    : priv instanceof EdDSAPrivateKey ed ? ed.getPublicKey() : null;
            if (pub == null) throw new IllegalStateException("Cannot derive the " + algorithm + " public key");
            return new KeyPair(pub, priv);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid " + algorithm + " private key (expected base64 PKCS#8)", e);
        }
    }

    static String keyId(byte[] material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Prints fresh production keys: {@code java -cp app.jar ... com.elepay.common.IntegrityKeys}. */
    public static void main(String[] args) {
        Base64.Encoder b64 = Base64.getEncoder();
        byte[] mac = new byte[64];
        new SecureRandom().nextBytes(mac);
        System.out.println("ELEPAY_INTEGRITY_MAC_KEY=" + b64.encodeToString(mac));
        System.out.println("ELEPAY_INTEGRITY_ED25519_KEY=" + b64.encodeToString(generate("Ed25519", null).getPrivate().getEncoded()));
        System.out.println("ELEPAY_INTEGRITY_MLDSA65_KEY="
                + b64.encodeToString(generate("ML-DSA", MLDSAParameterSpec.ml_dsa_65).getPrivate().getEncoded()));
    }
}
