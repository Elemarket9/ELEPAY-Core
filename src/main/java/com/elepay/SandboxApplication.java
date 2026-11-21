package com.elepay;

import tools.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@SpringBootApplication
public class SandboxApplication {
    public static void main(String[] args) {
        SpringApplication.run(SandboxApplication.class, args);
    }
}

@Profile("sandbox")
@RestController
@RequestMapping("/sandbox")
class SandboxLedger {
    private static final String CLEARING = "system:external";
    private final JdbcClient db;
    private final ObjectMapper json;

    SandboxLedger(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    record Allocation(String recipient, long pesewas) {}
    record Command(String type, String account, long pesewas, List<Allocation> allocations,
                   UUID checkout, Integer line) {}

    @PutMapping("/operations/{id}")
    @Transactional
    public Map<String, UUID> post(@PathVariable UUID id, @RequestBody Command command) {
        // Serialize sandbox writers so balance and refund checks see committed history.
        db.sql("LOCK TABLE ledger_operation IN SHARE ROW EXCLUSIVE MODE").update();
        String body = json.writeValueAsString(command);
        var existing = db.sql("SELECT request = CAST(:body AS jsonb) FROM ledger_operation WHERE id = :id")
                .param("body", body).param("id", id).query(Boolean.class).optional();
        if (existing.isPresent()) {
            if (!existing.get()) throw conflict("This reference already belongs to a different request");
            return Map.of("operationId", id);
        }

        if (command.type() == null) throw invalid("Operation type is required");
        db.sql("INSERT INTO ledger_operation VALUES (:id, CAST(:body AS jsonb))")
                .param("id", id).param("body", body).update();
        switch (command.type()) {
            case "topup" -> move(id, 0, CLEARING, account(command.account(), "customer"), command.pesewas());
            case "checkout" -> {
                String customer = account(command.account(), "customer");
                List<Allocation> allocations = command.allocations();
                if (allocations == null || allocations.isEmpty() || allocations.size() > 100) {
                    throw invalid("A checkout requires 1 to 100 allocations");
                }
                for (int line = 0; line < allocations.size(); line++) {
                    Allocation allocation = allocations.get(line);
                    if (allocation == null) throw invalid("Allocation is required");
                    move(id, line, customer, recipient(allocation.recipient()), allocation.pesewas());
                }
            }
            case "refund" -> refund(id, command);
            case "withdraw" -> move(id, 0, recipient(command.account()), CLEARING, command.pesewas());
            case "cashout" -> move(id, 0, account(command.account(), "customer"), CLEARING, command.pesewas());
            default -> throw invalid("Unknown operation type");
        }
        return Map.of("operationId", id);
    }

    private void refund(UUID id, Command command) {
        if (command.checkout() == null || command.line() == null || command.pesewas() <= 0) {
            throw invalid("Checkout, line and positive refund amount are required");
        }
        var original = db.sql("""
                SELECT source, destination, pesewas
                FROM ledger_move JOIN ledger_operation ON id = operation_id
                WHERE id = :checkout AND line = :line AND request->>'type' = 'checkout'
                """).param("checkout", command.checkout()).param("line", command.line())
                .query((row, index) -> new Original(row.getString(1), row.getString(2), row.getLong(3)))
                .optional().orElseThrow(() -> invalid("Unknown checkout allocation"));
        BigInteger refunded = db.sql("""
                SELECT coalesce(sum(m.pesewas), 0) FROM ledger_move m
                JOIN ledger_operation o ON o.id = m.operation_id
                WHERE o.request->>'type' = 'refund'
                  AND o.request->>'checkout' = :checkout AND o.request->>'line' = :line
                """).param("checkout", command.checkout().toString())
                .param("line", command.line().toString()).query(java.math.BigDecimal.class).single()
                .toBigIntegerExact();
        if (refunded.add(BigInteger.valueOf(command.pesewas())).compareTo(BigInteger.valueOf(original.pesewas())) > 0) {
            throw conflict("Refund exceeds the remaining allocation");
        }
        move(id, 0, original.destination(), original.source(), command.pesewas());
    }

    private record Original(String source, String destination, long pesewas) {}

    private void move(UUID id, int line, String source, String destination, long amount) {
        if (amount <= 0) throw invalid("Amount must be positive");
        if (!source.equals(CLEARING) && balance(source).compareTo(BigInteger.valueOf(amount)) < 0) {
            throw conflict("Insufficient balance");
        }
        db.sql("INSERT INTO ledger_move VALUES (:id, :line, :source, :destination, :amount)")
                .param("id", id).param("line", line).param("source", source)
                .param("destination", destination).param("amount", amount).update();
    }

    @GetMapping("/balances/{account}")
    public Map<String, BigInteger> readBalance(@PathVariable String account) {
        if (account.startsWith("customer:")) account(account, "customer");
        else recipient(account);
        return Map.of("pesewas", balance(account));
    }

    private BigInteger balance(String account) {
        return db.sql("SELECT pesewas FROM ledger_balance WHERE account = :account")
                .param("account", account).query(java.math.BigDecimal.class).optional()
                .map(java.math.BigDecimal::toBigIntegerExact).orElse(BigInteger.ZERO);
    }

    private static String recipient(String value) {
        return account(value, value != null && value.startsWith("delivery:") ? "delivery" : "merchant");
    }

    private static String account(String value, String kind) {
        if (value == null || !value.matches(kind + ":[A-Za-z0-9_-]{1,64}")) {
            throw invalid("Invalid " + kind + " account");
        }
        return value;
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
