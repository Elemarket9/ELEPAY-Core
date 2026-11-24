package com.elepay.merchanttools.pos;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PosNfcPaymentTest extends PostgresTestBase {

    @Autowired
    private EmvTlvParser emvTlvParser;

    @Autowired
    private PosTerminalService terminalService;

    @Autowired
    private PosOrderService orderService;

    @Autowired
    private PosNfcPaymentService nfcPaymentService;

    @Autowired
    private PosNfcController nfcController;

    @Autowired
    private JdbcClient db;

    private UUID merchantId;
    private String merchantCode;

    // Valid EMV TLV:
    // 9F26 (8B) = 0102030405060708
    // 9F27 (1B) = 80
    // 9F36 (2B) = 0001
    // 9F37 (4B) = 11223344
    // 9F02 (6B) = 000000003500 (35.00 GHS = 3500 pesewas)
    // 5F2A (2B) = 0936 (GHS)
    // 9F34 (3B) = 020000 (ONLINE_PIN)
    // 84   (7B) = A0000000031010 (VISA)
    private static final String VALID_EMV_TLV =
            "9F26080102030405060708"
            + "9F270180"
            + "9F36020001"
            + "9F370411223344"
            + "9F0206000000003500"
            + "5F2A020936"
            + "9F3403020000"
            + "8407A0000000031010";

    @BeforeEach
    void setup() {
        merchantId = UUID.randomUUID();
        merchantCode = String.format("%06d", (int) (Math.random() * 900_000 + 100_000));
        db.sql("""
            INSERT INTO merchant (
                id, legal_name, trading_name, kind, status, contact_phone,
                merchant_code, fee_bps, fee_fixed, settlement_delay_hours, created_by
            ) VALUES (
                :id, 'NFC Merchant Ltd', 'NFC Mart', 'merchant', 'active', '0241112233',
                :code, 0, 0, 24, 'system'
            ) ON CONFLICT DO NOTHING
            """)
                .param("id", merchantId)
                .param("code", merchantCode)
                .update();
    }

    @Test
    @DisplayName("EMV TLV parser decodes valid tags and enforces PCI redaction gate")
    void testEmvTlvParserAndPciGate() {
        var parsed = emvTlvParser.parseStrictlyRedacted(VALID_EMV_TLV);
        assertThat(parsed.amountMinorUnits()).isEqualTo(3_500L);
        assertThat(parsed.currencyNumeric()).isEqualTo("0936");
        assertThat(parsed.cvmMethod()).isEqualTo("ONLINE_PIN");
        assertThat(parsed.cardScheme()).isEqualTo("VISA");
        assertThat(parsed.arqc()).isEqualTo("0102030405060708");

        // Raw Track 2 (tag 57) must be rejected immediately
        String withTrack2 = VALID_EMV_TLV + "57084022341234567890";
        assertThatThrownBy(() -> emvTlvParser.parseStrictlyRedacted(withTrack2))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Sensitive raw card tag 57 is forbidden");

        // Raw PAN (tag 5A) must be rejected immediately
        String withPan = VALID_EMV_TLV + "5A084022341234567890";
        assertThatThrownBy(() -> emvTlvParser.parseStrictlyRedacted(withPan))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Sensitive raw card tag 5A is forbidden");

        // Indefinite BER-TLV length (0x80) must be rejected
        assertThatThrownBy(() -> emvTlvParser.parseStrictlyRedacted("9F26800102"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Indefinite");

        // Nested constructed BER-TLV (tag 70 wrapping raw PAN 5A) must be rejected
        assertThatThrownBy(() -> emvTlvParser.parseStrictlyRedacted(VALID_EMV_TLV + "700A5A084022341234567890"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Sensitive raw card tag 5A is forbidden");

        // Multi-level nested constructed BER-TLV (61 -> 70 -> Track 2 tag 57) must be rejected
        assertThatThrownBy(() -> emvTlvParser.parseStrictlyRedacted("610C700A57084022341234567890"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Sensitive raw card tag 57 is forbidden");
    }

    @Test
    @DisplayName("Terminal pairing enforces 5-attempt lockout, ECDSA P-256 attestation, and DUKPT provisioning")
    void testTerminalPairingLockoutAndAttestation() throws Exception {
        String serial = "SN-NFC-" + UUID.randomUUID().toString().substring(0, 8);
        var registered = terminalService.registerTerminal(merchantId, null, serial, "PAX A920 Pro");
        assertThat(registered.pairingCode()).hasSize(6);

        // Plaintext pairing_code must NOT be stored in DB
        String dbPlainCode = db.sql("SELECT pairing_code FROM pos_terminal WHERE id = :id")
                .param("id", registered.id()).query(String.class).optional().orElse(null);
        assertThat(dbPlainCode).isNull();

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        String pubBase64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

        // 5 wrong pairing attempts lock out the terminal
        for (int i = 0; i < 5; i++) {
            String wrongCode = registered.pairingCode().equals("000000") ? "111111" : "000000";
            String sigWrong = signEcdsa(kp, "pos-pair:" + merchantCode + ":" + serial + ":" + wrongCode);
            assertThatThrownBy(() -> terminalService.pairTerminal(
                    merchantCode, serial, wrongCode, "HW-FP-1", pubBase64, "ECDSA_P256", sigWrong))
                    .isInstanceOf(ApiException.class);
        }

        // Even the real code must now fail after 5 attempts
        String sigReal = signEcdsa(kp, "pos-pair:" + merchantCode + ":" + serial + ":" + registered.pairingCode());
        assertThatThrownBy(() -> terminalService.pairTerminal(
                merchantCode, serial, registered.pairingCode(), "HW-FP-1", pubBase64, "ECDSA_P256", sigReal))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("locked");

        // Fresh terminal with valid ECDSA P-256 attestation pairs and provisions DUKPT + ApiCredential
        String serial2 = "SN-NFC-" + UUID.randomUUID().toString().substring(0, 8);
        var reg2 = terminalService.registerTerminal(merchantId, null, serial2, "PAX A920 Pro");
        String sig2 = signEcdsa(kp, "pos-pair:" + merchantCode + ":" + serial2 + ":" + reg2.pairingCode());
        var paired = terminalService.pairTerminal(
                merchantCode, serial2, reg2.pairingCode(), "HW-FP-2", pubBase64, "ECDSA_P256", sig2);

        assertThat(paired.status()).isEqualTo("ACTIVE");
        assertThat(paired.credentialId()).isNotNull();
        assertThat(paired.apiKey()).isNotBlank();
        assertThat(paired.signingSecret()).startsWith("sec_");
        assertThat(paired.initialKsn()).hasSize(20);
    }

    @Test
    @DisplayName("End-to-end NFC tap-to-pay persists redacted authorization, routes through PosCheckoutGateway, and blocks KSN replay")
    void testNfcTapToPayAndReplayProtection() throws Exception {
        String serial = "SN-TAP-" + UUID.randomUUID().toString().substring(0, 8);
        var reg = terminalService.registerTerminal(merchantId, null, serial, "PAX A920 Pro");

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        String pubBase64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        String sig = signEcdsa(kp, "pos-pair:" + merchantCode + ":" + serial + ":" + reg.pairingCode());
        var paired = terminalService.pairTerminal(
                merchantCode, serial, reg.pairingCode(), "HW-FP-TAP", pubBase64, "ECDSA_P256", sig);

        var order = orderService.createOrder(
                merchantId, null, paired.terminalId(), null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Latte", new java.math.BigDecimal("1"), 3_500L, 0L, 0L, null, null, null, null)),
                0L, 0L, 0L, "TAKEAWAY");

        String ksn1 = paired.initialKsn().substring(0, 15) + "00001";
        var tapReq = new PosNfcPaymentService.NfcTapRequest(
                paired.terminalId(), ksn1, VALID_EMV_TLV, "402234******7890", "VISA", null, 3_500L, "GHS", null);

        var res = nfcPaymentService.processNfcTap(order.id(), "nfc-idem-1", tapReq, "cashier-1");
        assertThat(res.status()).isEqualTo("AWAITING_CARD_APPROVAL");
        assertThat(res.authCode()).isNull();
        assertThat(res.attemptId()).isNotNull();
        assertThat(res.checkoutRef()).isNotBlank();
        assertThat(res.ksn()).isEqualTo(ksn1);
        assertThat(res.ksnCounter()).isEqualTo(1L);
        assertThat(res.cvmMethod()).isEqualTo("ONLINE_PIN");

        // Replaying the same KSN on a new order with a different idempotency key must be rejected
        var order2 = orderService.createOrder(
                merchantId, null, paired.terminalId(), null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Latte", new java.math.BigDecimal("1"), 3_500L, 0L, 0L, null, null, null, null)),
                0L, 0L, 0L, "TAKEAWAY");
        assertThatThrownBy(() -> nfcPaymentService.processNfcTap(order2.id(), "nfc-idem-2", tapReq, "cashier-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("strictly greater");
    }

    @Test
    @DisplayName("Cross-branch NFC tap is rejected with 404 for both branch-scoped caller and mismatched terminal")
    void testCrossBranchNfcTapRejectedWith404() throws Exception {
        UUID branchA = UUID.randomUUID();
        UUID branchB = UUID.randomUUID();
        db.sql("""
            INSERT INTO merchant_branch (id, merchant_id, name, city, region, country)
            VALUES (:id, :m, :name, 'Accra', 'Greater Accra', 'GH')
            """)
                .param("id", branchA).param("m", merchantId).param("name", "Branch A")
                .update();
        db.sql("""
            INSERT INTO merchant_branch (id, merchant_id, name, city, region, country)
            VALUES (:id, :m, :name, 'Kumasi', 'Ashanti', 'GH')
            """)
                .param("id", branchB).param("m", merchantId).param("name", "Branch B")
                .update();

        String serial = "SN-BR-" + UUID.randomUUID().toString().substring(0, 8);
        var reg = terminalService.registerTerminal(merchantId, branchA, serial, "PAX A920 Pro");

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        String pubBase64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        String sig = signEcdsa(kp, "pos-pair:" + merchantCode + ":" + serial + ":" + reg.pairingCode());
        var paired = terminalService.pairTerminal(
                merchantCode, serial, reg.pairingCode(), "HW-FP-BR", pubBase64, "ECDSA_P256", sig);

        var orderInBranchB = orderService.createOrder(
                merchantId, branchB, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Espresso", new java.math.BigDecimal("1"), 3_500L, 0L, 0L, null, null, null, null)),
                0L, 0L, 0L, "TAKEAWAY");

        String ksn1 = paired.initialKsn().substring(0, 15) + "00001";
        var tapReq = new PosNfcPaymentService.NfcTapRequest(
                paired.terminalId(), ksn1, VALID_EMV_TLV, "402234******7890", "VISA", null, 3_500L, "GHS", null);

        // 1. Service-level: terminal in branchA cannot tap order in branchB -> 404
        assertThatThrownBy(() -> nfcPaymentService.processNfcTap(orderInBranchB.id(), "nfc-br-1", tapReq, "cashier-a"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));

        // 2. Controller-level: branch-scoped cashier in branchA cannot tap order in branchB -> 404
        String cashierSub = "cashier-a-" + UUID.randomUUID();
        db.sql("INSERT INTO merchant_member (merchant_id, subject, role, branch_id) VALUES (:m, :s, 'cashier', :b)")
                .param("m", merchantId).param("s", cashierSub).param("b", branchA)
                .update();

        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(cashierSub)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try {
            assertThatThrownBy(() -> nfcController.nfcTap(orderInBranchB.id(), "nfc-br-2", tapReq))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("NFC tap rejects when an open or active checkout link already exists on the order")
    void testOpenCheckoutLinkConflictHandling() throws Exception {
        String serial = "SN-LNK-" + UUID.randomUUID().toString().substring(0, 8);
        var reg = terminalService.registerTerminal(merchantId, null, serial, "PAX A920 Pro");

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        String pubBase64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        String sig = signEcdsa(kp, "pos-pair:" + merchantCode + ":" + serial + ":" + reg.pairingCode());
        var paired = terminalService.pairTerminal(
                merchantCode, serial, reg.pairingCode(), "HW-FP-LNK", pubBase64, "ECDSA_P256", sig);

        var order = orderService.createOrder(
                merchantId, null, paired.terminalId(), null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Mocha", new java.math.BigDecimal("1"), 3_500L, 0L, 0L, null, null, null, null)),
                0L, 0L, 0L, "TAKEAWAY");

        // Start a digital checkout link first (creates an open pos_checkout_link for 3500)
        orderService.startDigital(order.id(), "cashier-1");

        String ksn1 = paired.initialKsn().substring(0, 15) + "00001";
        var tapReq = new PosNfcPaymentService.NfcTapRequest(
                paired.terminalId(), ksn1, VALID_EMV_TLV, "402234******7890", "VISA", null, 3_500L, "GHS", null);

        // Must fail cleanly with ApiException 409 Conflict instead of pos_checkout_one_active DB constraint error
        assertThatThrownBy(() -> nfcPaymentService.processNfcTap(order.id(), "nfc-lnk-1", tapReq, "cashier-1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    private static String signEcdsa(KeyPair kp, String payload) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(kp.getPrivate());
        sig.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }
}
