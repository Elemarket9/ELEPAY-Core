package com.elepay.emtech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class EmtechMappingTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EmtechSchemaValidator schemas = new EmtechSchemaValidator(json);

    @Test
    void pesewasConvertToExactMajorUnits() {
        assertThat(EmtechMoney.ghs(1)).isEqualByComparingTo("0.01");
        assertThat(EmtechMoney.ghs(1).scale()).isEqualTo(2);
        assertThat(EmtechMoney.ghs(15050)).isEqualByComparingTo("150.50");
        assertThat(EmtechMoney.ghs(0)).isEqualByComparingTo("0.00");
        assertThat(EmtechMoney.ghs(123456789)).isEqualByComparingTo("1234567.89");
        assertThatThrownBy(() -> EmtechMoney.ghs(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountTypesMapOntoTheEmtechEnum() {
        assertThat(EmtechAccountTypeMapper.map("momo", "MTN", null, null).emtechType()).isEqualTo("MOBILE_MONEY");
        assertThat(EmtechAccountTypeMapper.map("bank", "TST", null, null).emtechType()).isEqualTo("BANK");
        assertThat(EmtechAccountTypeMapper.map("card", null, null, null).emtechType()).isEqualTo("CREDIT");
        assertThat(EmtechAccountTypeMapper.map("debit", "VISA", null, null).provider()).isEqualTo("VISA");
        assertThat(EmtechAccountTypeMapper.map("cash", null, null, null).emtechType()).isEqualTo("CASH");
        assertThat(EmtechAccountTypeMapper.map("airtime", "MTN", null, null).emtechType()).isEqualTo("AIRTIME");
        assertThat(EmtechAccountTypeMapper.map("crypto", null, null, null).emtechType()).isEqualTo("CRYPTOCURRENCY");
    }

    @Test
    void eMoneyWalletIsNotReportedAsBankOrMobileMoneyUnlessComplianceSaysSo() {
        assertThatThrownBy(() -> EmtechAccountTypeMapper.map("wallet", "ELEPAY", null, null))
                .isInstanceOf(EmtechValidationException.class)
                .hasMessageContaining("will not report a wallet as BANK or MOBILE_MONEY");
        var supplied = EmtechAccountTypeMapper.map("wallet", null, "BANK", "MOBILE_MONEY");
        assertThat(supplied.emtechType()).isEqualTo("BANK");
        assertThat(supplied.emoneyWallet()).isTrue();
        assertThat(supplied.provider()).isEqualTo("ELEPAY");
        var platform = EmtechAccountTypeMapper.map("wallet", "ELEPAY", null, "CREDIT");
        assertThat(platform.emtechType()).isEqualTo("CREDIT");
    }

    @Test
    void kycTierAndGhanaCardPathMapToEmtech() {
        var minimum = EmtechKycMapper.map(new EmtechKycMapper.Input(1, null, false, null, null, null, null, null));
        assertThat(minimum.level()).isEqualTo("MINIMUM");
        assertThat(minimum.status()).isEqualTo("NOTCHECKED");
        assertThat(minimum.type()).isEqualTo("OTHER");
        assertThat(minimum.service()).isEqualTo("ELEPAY");

        var card = EmtechKycMapper.map(new EmtechKycMapper.Input(2, "VERIFIED", false, null, null, null, null, null));
        assertThat(card.level()).isEqualTo("MEDIUM");
        assertThat(card.status()).isEqualTo("VERIFIED");
        assertThat(card.type()).isEqualTo("NATIONAL_ID_TEXT");
        assertThat(card.service()).isEqualTo("NIA Ghana Card");
        assertThat(card.details()).isEqualTo("Ghana Card");

        var face = EmtechKycMapper.map(new EmtechKycMapper.Input(3, "VERIFIED", true, null, null, null, null, null));
        assertThat(face.level()).isEqualTo("ENHANCED");
        assertThat(face.type()).isEqualTo("MOBILE_PHOTO");
        assertThat(face.service()).isEqualTo("NIA Ghana Card");

        var mismatch = EmtechKycMapper.map(new EmtechKycMapper.Input(2, "MISMATCHED", false, null, null, null, null, null));
        assertThat(mismatch.status()).isEqualTo("UNVERIFIED");
        var down = EmtechKycMapper.map(new EmtechKycMapper.Input(1, "UNAVAILABLE", false, null, null, null, null, null));
        assertThat(down.status()).isEqualTo("NOTCHECKED");
        assertThat(down.reason()).contains("unavailable");

        var explicit = EmtechKycMapper.map(new EmtechKycMapper.Input(2, "VERIFIED", false,
                "NIA Ghana Card", "NATIONAL_ID_IMAGE", "VERIFIED", "Ghana Card", null));
        assertThat(explicit.type()).isEqualTo("NATIONAL_ID_IMAGE");
    }

    @Test
    void placesComeFromTheServerNotFromCustomerGps() {
        var terminal = ServerSidePlaces.Place.of("Accra", null, "GH", null);
        var profile = ServerSidePlaces.Place.of("Kumasi", "GH-AH", "GH", "GHANA_CARD");
        var ip = ServerSidePlaces.Place.of("Tema", "GH-AA", "GH", "COARSE_IP");
        var mno = ServerSidePlaces.Place.of("Tamale", null, null, "MNO");
        var branch = ServerSidePlaces.Place.of("Takoradi", null, "GH", "BRANCH");

        var pos = ServerSidePlaces.origin("POS", terminal, profile, ip, mno, branch);
        assertThat(pos.city()).isEqualTo("Accra");
        assertThat(pos.region()).isEqualTo("GH-AA");
        assertThat(pos.source()).isEqualTo("TERMINAL");

        var web = ServerSidePlaces.origin("WEB", terminal, profile, ip, mno, branch);
        assertThat(web.city()).isEqualTo("Kumasi");
        assertThat(web.source()).isEqualTo("GHANA_CARD");

        var fromIp = ServerSidePlaces.origin("MOBILE_APP", null, null, ip, mno, branch);
        assertThat(fromIp.city()).isEqualTo("Tema");
        assertThat(fromIp.source()).isEqualTo("COARSE_IP");

        var fromMno = ServerSidePlaces.origin("MOBILE_USSD", null, null, null, mno, branch);
        assertThat(fromMno.city()).isEqualTo("Tamale");
        assertThat(fromMno.region()).isEqualTo("GH-NP");
        assertThat(fromMno.source()).isEqualTo("MNO");

        var fromBranch = ServerSidePlaces.origin("WEB", null, null, null, null, branch);
        assertThat(fromBranch.city()).isEqualTo("Takoradi");
        assertThat(fromBranch.region()).isEqualTo("GH-WP");
        assertThat(fromBranch.source()).isEqualTo("BRANCH");

        var shopMissing = ServerSidePlaces.origin("POS", null, profile, ip, mno, branch);
        assertThat(shopMissing.usable()).isFalse();

        var dest = ServerSidePlaces.merchantDestination(branch, profile, null, terminal);
        assertThat(dest.city()).isEqualTo("Takoradi");
        var recipient = ServerSidePlaces.recipient(profile, mno);
        assertThat(recipient.city()).isEqualTo("Kumasi");
    }

    @Test
    void transactionMapperEmitsExactDecimalsAndNoCustomerCoordinates() {
        ObjectNode node = new EmtechTransactionMapper(json, "").toJson(samplePayment());
        schemas.validate("cryptoPSPTransactionV2", node);
        assertThat(node.get("originAmount").isNumber()).isTrue();
        assertThat(node.get("originAmount").decimalValue()).isEqualByComparingTo("150.50");
        assertThat(node.get("transactionFeeAmount").decimalValue()).isEqualByComparingTo("1.50");
        assertThat(node.get("destinationAmount").decimalValue()).isEqualByComparingTo("149.00");
        assertThat(node.get("originAmount").isTextual()).isFalse();
        assertThat(node.has("transactionLatitude")).isFalse();
        assertThat(node.has("transactionLongitude")).isFalse();
        assertThat(node.has("transactionClientIp")).isFalse();
        assertThat(node.get("originUserId").isTextual()).isTrue();
        assertThat(node.get("transactionType").asString()).isEqualTo("PAYMENT");
        assertThat(node.get("originAccountType").asString()).isEqualTo("MOBILE_MONEY");
        assertThat(node.get("originUserKYCLevel").asString()).isEqualTo("MEDIUM");
        assertThat(node.get("originUserKYCType").asString()).isEqualTo("NATIONAL_ID_TEXT");
        assertThat(node.get("originUserKYCService").asString()).isEqualTo("NIA Ghana Card");
        String raw = json.writeValueAsString(node);
        assertThat(raw).contains("\"originAmount\":150.50");
        assertThat(raw).doesNotContain("\"originAmount\":\"");
    }

    @Test
    void walletTransferIsOtherAndRefundIsOther() {
        assertThat(EmtechTransactionMapper.type("wallet_transfer")).isEqualTo("OTHER");
        assertThat(EmtechTransactionMapper.type("refund")).isEqualTo("OTHER");
        assertThat(EmtechTransactionMapper.type("topup")).isEqualTo("DEPOSIT");
        assertThat(EmtechTransactionMapper.type("payout")).isEqualTo("WITHDRAWAL");
        assertThat(EmtechTransactionMapper.type("exchange")).isEqualTo("EXCHANGE");
        assertThat(EmtechTransactionMapper.status("succeeded")).isEqualTo("SUCCESS");
        assertThat(EmtechTransactionMapper.status("insufficient funds")).isEqualTo("REJECT");
        assertThat(EmtechTransactionMapper.status("fraud hold")).isEqualTo("SUSPECT");
        assertThat(EmtechTransactionMapper.status("failed")).isEqualTo("FAILED");
        assertThat(EmtechTransactionMapper.channel("ussd")).isEqualTo("MOBILE_USSD");
    }

    @Test
    void deviceMapperKeepsCoordinatesAsStringsAndYearAsInteger() {
        ObjectNode node = new EmtechDeviceMapper(json).toJson(new RegulatoryFacts.Device(
                "terminal-accra", "POS", "Osu counter", "Accra", "GH-AA", "GH",
                "15 Oxford Street", "Counter", "5.603717", "-0.186964", "T1", "ELEPAY",
                "Shop terminal", "2026-01-15T09:00:00Z", "NETWORK", 2025));
        schemas.validate("deviceV2", node);
        assertThat(node.get("deviceLatitude").isTextual()).isTrue();
        assertThat(node.get("deviceLongitude").asString()).isEqualTo("-0.186964");
        assertThat(node.get("deviceManufactureYear").isIntegralNumber()).isTrue();
        assertThat(node.get("deviceManufactureYear").intValue()).isEqualTo(2025);
        assertThat(node.get("deviceConnectionType").asString()).isEqualTo("NETWORK");
    }

    @Test
    void deviceEventAndComplaintMappersMatchTheSchema() {
        ObjectNode event = new EmtechDeviceEventMapper(json).toJson(new RegulatoryFacts.DeviceEvent(
                "terminal-accra", "IMPAIRED", "NETWORK_FAILURE", "2026-11-23T12:00:00Z", "Link down"));
        schemas.validate("deviceEvent", event);
        assertThat(event.get("deviceStatusDatetime").isTextual()).isTrue();

        ObjectNode complaint = new EmtechComplaintMapper(json).toJson(new RegulatoryFacts.Complaint(
                "customer-1", "case-1", "MISCONDUCT OTHER", "Staff refused a receipt",
                "REOPENED", "2026-11-23T15:00:00Z", "Customer asked for another review"));
        schemas.validate("consumerComplaints", complaint);
        assertThat(complaint.get("userId").isTextual()).isTrue();
        assertThat(complaint.get("complaintType").asString()).isEqualTo("MISCONDUCT OTHER");
        assertThat(complaint.get("complaintStatus").asString()).isEqualTo("REOPENED");
    }

    @Test
    void schemaRejectsMissingFieldsWrongTypesAndBadEnums() {
        ObjectNode payment = new EmtechTransactionMapper(json, "BANK").toJson(walletPayment());
        schemas.validate("cryptoPSPTransactionV2", payment);
        payment.remove("transactionId");
        assertThatThrownBy(() -> schemas.validate("cryptoPSPTransactionV2", payment))
                .isInstanceOf(EmtechValidationException.class)
                .hasMessageContaining("transactionId");

        ObjectNode typed = new EmtechTransactionMapper(json, "").toJson(samplePayment());
        typed.put("originAmount", "150.50");
        assertThatThrownBy(() -> schemas.validate("cryptoPSPTransactionV2", typed))
                .isInstanceOf(EmtechValidationException.class)
                .hasMessageContaining("originAmount");

        ObjectNode badEnum = new EmtechTransactionMapper(json, "").toJson(samplePayment());
        badEnum.put("transactionType", "TRANSFER");
        assertThatThrownBy(() -> schemas.validate("cryptoPSPTransactionV2", badEnum))
                .isInstanceOf(EmtechValidationException.class)
                .hasMessageContaining("TRANSFER");

        ObjectNode device = new EmtechDeviceMapper(json).toJson(new RegulatoryFacts.Device(
                "terminal-accra", "POS", "Osu", "Accra", "GH-AA", "GH", null, null,
                "5.60", "-0.18", null, null, null, "2026-01-15T09:00:00Z", "NETWORK", 2025));
        device.put("deviceLatitude", 5.60);
        device.put("deviceManufactureYear", "2025");
        assertThatThrownBy(() -> schemas.validate("deviceV2", device))
                .isInstanceOf(EmtechValidationException.class)
                .hasMessageContaining("deviceLatitude")
                .hasMessageContaining("deviceManufactureYear");
    }

    private RegulatoryFacts.Transaction samplePayment() {
        return new RegulatoryFacts.Transaction(
                "EP-ACCRA-1", "payment", "succeeded", "POS", "2026-11-23T10:15:00Z", "terminal-accra",
                150, "GHS", "customer-ama", "233241111111", "momo", "MTN", null,
                "Accra", "GH-AA", "GH", "TERMINAL", 15050, "GHS",
                2, null, null, null, null, null, "VERIFIED", false,
                "merchant-osu", "wallet", "ELEPAY", "BANK",
                "Accra", "GH-AA", "GH", "BRANCH", 14900, "GHS",
                "mtn-ref-1", "MTN", "succeeded", null, null);
    }

    private RegulatoryFacts.Transaction walletPayment() {
        return new RegulatoryFacts.Transaction(
                "EP-WALLET-1", "payment", "succeeded", "WEB", "2026-11-23T11:00:00Z", "elepay-web-merchant",
                0, "GHS", "customer-ama", "customer-ama", "wallet", "ELEPAY", "BANK",
                "Accra", "GH-AA", "GH", "PROFILE", 2000, "GHS",
                2, null, null, null, null, null, "VERIFIED", true,
                "merchant-osu", "wallet", "ELEPAY", "BANK",
                "Accra", "GH-AA", "GH", "BRANCH", 2000, "GHS",
                null, null, null, null, null);
    }
}
