package com.elepay.rails;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * {@link PaymentRail} adapter bridging {@link RailTransactions} bank disbursements
 * (merchant bank payouts and V87 platform earned-revenue sweeps) to the contracted
 * Custodian Bank via {@link CustodianBankGateway}.
 *
 * <p>Activated automatically whenever a {@link CustodianBankGateway} bean is active
 * (e.g. {@code elepay.custodian.h2h.enabled=true}).
 */
@Component
@ConditionalOnBean(CustodianBankGateway.class)
public class CustodianBankPaymentRail implements PaymentRail {

    public static final String RAIL_NAME = "custodian";
    private static final Pattern BANK_CODE = Pattern.compile("^[A-Z0-9]{2,20}$");

    private final CustodianBankGateway gateway;

    public CustodianBankPaymentRail(CustodianBankGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway is required");
    }

    @Override
    public String name() {
        return RAIL_NAME;
    }

    @Override
    public boolean supports(String direction, String channel, String network) {
        if (!"payout".equalsIgnoreCase(direction) || !"bank".equalsIgnoreCase(channel)) {
            return false;
        }
        return network == null
                || network.isBlank()
                || "bank".equalsIgnoreCase(network)
                || "custodian".equalsIgnoreCase(network)
                || BANK_CODE.matcher(network.trim().toUpperCase(java.util.Locale.ROOT)).matches();
    }

    @Override
    public Result charge(Charge charge) {
        return Result.unavailable("Custodian Bank H2H rail supports outbound bank disbursements only");
    }

    @Override
    public Result payout(Payout payout) {
        String bankCode = (payout.network() == null || payout.network().isBlank())
                ? "BANK"
                : payout.network().trim().toUpperCase(java.util.Locale.ROOT);
        var instruction = new CustodianBankGateway.DisbursementInstruction(
                payout.reference(),
                CustodianBankBackstopService.PRIMARY_TRUST_CODE,
                payout.amount(),
                payout.currency(),
                "bank",
                bankCode,
                payout.accountNumber(),
                payout.accountName() != null ? payout.accountName() : "Beneficiary",
                "payout",
                payout.description() != null ? payout.description() : ("ELEPAY bank payout " + payout.reference())
        );
        CustodianBankGateway.DisbursementResponse response = gateway.initiateDisbursement(instruction);
        return toRailResult(response, payout.amount(), payout.currency());
    }

    @Override
    public Result refund(Refund refund) {
        return Result.unavailable("Custodian Bank H2H rail supports outbound bank disbursements only");
    }

    @Override
    public Result query(String direction, String reference, String railReference) {
        CustodianBankGateway.DisbursementResponse response = gateway.queryDisbursement(reference, railReference);
        return toRailResult(response, null, null);
    }

    @Override
    public Callback verifyCallback(Map<String, String> headers, String body) {
        throw new InvalidCallbackException("Inbound callbacks are not enabled on Custodian Bank H2H rail; use query polling");
    }

    @Override
    public boolean callbacksAreAuthoritative() {
        return false;
    }

    private static Result toRailResult(
            CustodianBankGateway.DisbursementResponse response,
            Long expectedAmount,
            String expectedCurrency
    ) {
        if (response == null || response.status() == null) {
            return Result.unknown("Empty Custodian Bank disbursement response");
        }
        String bankRef = response.bankReference();
        String msg = response.message() != null ? response.message() : response.status().name();
        return switch (response.status()) {
            case SETTLED -> {
                if (bankRef == null || bankRef.isBlank()) {
                    yield Result.unknown("Custodian Bank reported SETTLED without bankReference");
                }
                Long reportedAmount = response.amountPesewas();
                String reportedCurrency = (response.currency() != null && !response.currency().isBlank())
                        ? response.currency().trim().toUpperCase(java.util.Locale.ROOT)
                        : null;
                if (expectedAmount != null && reportedAmount != null && !expectedAmount.equals(reportedAmount)) {
                    yield Result.unknown("Custodian Bank reported SETTLED amount " + reportedAmount
                            + " != expected " + expectedAmount);
                }
                if (expectedCurrency != null && reportedCurrency != null
                        && !expectedCurrency.trim().equalsIgnoreCase(reportedCurrency)) {
                    yield Result.unknown("Custodian Bank reported SETTLED currency " + reportedCurrency
                            + " != expected " + expectedCurrency);
                }
                Long settledAmount = reportedAmount != null ? reportedAmount : expectedAmount;
                String settledCurrency = reportedCurrency != null
                        ? reportedCurrency
                        : (expectedCurrency != null && !expectedCurrency.isBlank()
                                ? expectedCurrency.trim().toUpperCase(java.util.Locale.ROOT)
                                : null);
                if (settledAmount == null || settledAmount <= 0 || settledCurrency == null) {
                    // Fail closed during query() if bank omitted amount/currency evidence.
                    yield new Result(Status.PENDING, bankRef, null, null, null, null, msg);
                }
                yield new Result(Status.SUCCEEDED, bankRef, settledAmount, settledCurrency, 0L, null, msg);
            }
            case ACCEPTED -> new Result(Status.PENDING, bankRef, null, null, null, null, msg);
            case REJECTED -> new Result(Status.FAILED, bankRef, null, null, null, null, msg);
            case UNKNOWN -> Result.unknown(msg);
        };
    }
}
