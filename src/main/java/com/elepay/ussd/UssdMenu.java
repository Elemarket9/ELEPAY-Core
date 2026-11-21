package com.elepay.ussd;

import com.elepay.checkout.CheckoutChannel;
import com.elepay.common.ApiException;
import com.elepay.common.References;
import com.elepay.wallet.WalletChannel;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Gateway-neutral USSD menu for basic phones. The caller is identified by the
 * MSISDN the gateway supplies (trusted only after gateway verification) and
 * every money action requires the customer's USSD PIN. Sending uses the same
 * recipient name confirmation as the apps: the registered name is shown
 * before the PIN is requested. Idempotency keys derive from the gateway
 * session id, so a repeated gateway request cannot pay twice.
 */
@Service
class UssdMenu {
    static final String ROOT = "ELEPAY\n1. Balance\n2. Send money\n3. Pay merchant\n0. Exit";
    private static final long SESSION_IDLE_SECONDS = 300;

    private final JdbcClient db;
    private final WalletChannel wallet;
    private final CheckoutChannel checkout;
    private final ObjectMapper json;

    UssdMenu(JdbcClient db, WalletChannel wallet, CheckoutChannel checkout, ObjectMapper json) {
        this.db = db;
        this.wallet = wallet;
        this.checkout = checkout;
        this.json = json;
    }

    record Request(String gateway, String sessionId, String msisdn, String input, boolean start) {}

    record Response(String message, boolean end) {
        static Response more(String m) { return new Response(m, false); }
        static Response done(String m) { return new Response(m, true); }
    }

    /**
     * Not transactional as a whole: lookups, PIN checks, transfers and
     * payments each commit or roll back on their own, so a declined step
     * never poisons the session update.
     */
    Response handle(Request request) {
        String msisdn = References.msisdn(request.msisdn());
        Map<String, String> state = load(request, msisdn);
        Optional<WalletChannel.Customer> customer = wallet.activeCustomerByPhone(msisdn);
        if (customer.isEmpty()) return finish(request, Response.done("This number has no active ELEPAY wallet. Register in the ELEPAY app."));
        if (!customer.get().hasPin()) return finish(request, Response.done("Set your USSD PIN in the ELEPAY app first."));
        String input = request.input() == null ? "" : request.input().trim();
        Response response;
        try {
            response = step(customer.get(), state, request.start() || state.isEmpty() ? null : input, request.sessionId());
        } catch (ApiException e) {
            state.clear();
            response = Response.done(e.getMessage());
        }
        if (response.end()) return finish(request, response);
        save(request, msisdn, state);
        return response;
    }

    private Response step(WalletChannel.Customer c, Map<String, String> s, String input, String sessionId) {
        String step = s.getOrDefault("step", "root");
        if (input == null) {
            s.clear();
            s.put("step", "root");
            return Response.more(ROOT);
        }
        switch (step) {
            case "root" -> {
                return switch (input) {
                    case "1" -> { s.put("step", "balance_pin"); yield Response.more("Enter PIN"); }
                    case "2" -> { s.put("step", "send_phone"); yield Response.more("Enter recipient number"); }
                    case "3" -> { s.put("step", "pay_code"); yield Response.more("Enter merchant code"); }
                    case "0" -> Response.done("Thank you for using ELEPAY");
                    default -> Response.more("Invalid choice\n" + ROOT);
                };
            }
            case "balance_pin" -> {
                Response denied = pin(c, input);
                if (denied != null) return denied;
                return Response.done("Available balance: " + money(wallet.availableBalance(c.id())));
            }
            case "send_phone" -> {
                WalletChannel.Recipient r = wallet.lookupWallet(c, input);
                if (!"verified".equals(r.status())) return Response.done(r.message());
                s.put("confirmation", r.confirmationId().toString());
                s.put("name", r.verifiedName());
                s.put("destination", r.destination());
                s.put("step", "send_amount");
                return Response.more("Send to " + r.verifiedName() + " (" + r.destination() + ")\nEnter amount in GHS");
            }
            case "send_amount" -> {
                long amount = amount(input);
                s.put("amount", Long.toString(amount));
                s.put("step", "send_pin");
                return Response.more("Send " + money(amount) + " to " + s.get("name") + " " + s.get("destination")
                        + "\nFee " + money(0) + ", total " + money(amount) + "\nEnter PIN to confirm");
            }
            case "send_pin" -> {
                Response denied = pin(c, input);
                if (denied != null) return denied;
                var sent = wallet.send(c, UUID.fromString(s.get("confirmation")), Long.parseLong(s.get("amount")),
                        "ussd:" + sessionId + ":send");
                return Response.done("Sent " + money(sent.amount()) + " to " + s.get("name") + ". Ref " + sent.reference());
            }
            case "pay_code" -> {
                if (!input.matches("[0-9]{6}")) return Response.more("Enter the 6-digit merchant code");
                String name = checkout.activeMerchantName(input).orElse(null);
                if (name == null) return Response.done("No active merchant uses this code");
                s.put("code", input);
                s.put("name", name);
                s.put("step", "pay_amount");
                return Response.more("Pay " + name + "\nEnter amount in GHS");
            }
            case "pay_amount" -> {
                long amount = amount(input);
                long fee = checkout.walletFee(amount);
                s.put("amount", Long.toString(amount));
                s.put("step", "pay_pin");
                return Response.more("Pay " + s.get("name") + " " + money(amount) + "\nFee " + money(fee) + ", total "
                        + money(amount + fee) + "\nEnter PIN to confirm");
            }
            case "pay_pin" -> {
                Response denied = pin(c, input);
                if (denied != null) return denied;
                var paid = checkout.payMerchantCode(c.id(), "ussd:" + c.subject(), s.get("code"),
                        Long.parseLong(s.get("amount")), "ussd:" + sessionId + ":pay");
                return Response.done("Paid " + money(paid.total()) + " to " + paid.merchant() + ". Ref " + paid.reference());
            }
            default -> {
                s.clear();
                return Response.more(ROOT);
            }
        }
    }

    private Response pin(WalletChannel.Customer c, String input) {
        return switch (wallet.checkPin(c.id(), input)) {
            case OK -> null;
            case WRONG -> Response.done("Wrong PIN. Dial again to retry.");
            case LOCKED -> Response.done("Too many wrong PINs. USSD is locked for 30 minutes.");
            case NOT_SET -> Response.done("Set your USSD PIN in the ELEPAY app first.");
        };
    }

    static long amount(String input) {
        if (!input.matches("[0-9]{1,7}(\\.[0-9]{1,2})?")) throw ApiException.badRequest("invalid_amount", "Invalid amount. Dial again.");
        String[] parts = input.split("\\.");
        long pesewas = Long.parseLong(parts[0]) * 100 + (parts.length > 1 ? Long.parseLong((parts[1] + "0").substring(0, 2)) : 0);
        if (pesewas <= 0) throw ApiException.badRequest("invalid_amount", "Invalid amount. Dial again.");
        return pesewas;
    }

    static String money(long pesewas) {
        return "GHS %d.%02d".formatted(pesewas / 100, pesewas % 100);
    }

    private Map<String, String> load(Request request, String msisdn) {
        return db.sql("SELECT msisdn, state::text, updated_at FROM ussd_session WHERE session_id = :id")
                .param("id", request.gateway() + ":" + request.sessionId())
                .query((rs, i) -> {
                    boolean fresh = rs.getObject(3, OffsetDateTime.class).isAfter(OffsetDateTime.now().minusSeconds(SESSION_IDLE_SECONDS));
                    if (!rs.getString(1).equals(msisdn) || !fresh) return new HashMap<String, String>();
                    return new HashMap<>(json.readValue(rs.getString(2), new TypeReference<Map<String, String>>() {}));
                }).optional().orElseGet(HashMap::new);
    }

    private void save(Request request, String msisdn, Map<String, String> state) {
        db.sql("""
                INSERT INTO ussd_session (session_id, msisdn, state, gateway, updated_at)
                VALUES (:id, :m, CAST(:s AS jsonb), :g, now())
                ON CONFLICT (session_id) DO UPDATE SET state = EXCLUDED.state, updated_at = now()""")
                .param("id", request.gateway() + ":" + request.sessionId()).param("m", msisdn)
                .param("s", json.writeValueAsString(state)).param("g", request.gateway()).update();
    }

    private Response finish(Request request, Response response) {
        db.sql("DELETE FROM ussd_session WHERE session_id = :id").param("id", request.gateway() + ":" + request.sessionId()).update();
        return response;
    }
}
