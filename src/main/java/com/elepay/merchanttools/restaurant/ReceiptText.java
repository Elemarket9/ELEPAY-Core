package com.elepay.merchanttools.restaurant;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-text customer receipt for thermal printers (32 columns on 58mm paper, 42/48 on 80mm).
 * Mirrors {@link KitchenTicketText}: every rendered line fits strictly within {@code width}.
 */
public final class ReceiptText {
    private static final ZoneId ACCRA = ZoneId.of("Africa/Accra");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public record Line(BigDecimal quantity, String name, long totalPesewas) {}

    public record Payment(String tenderType, long amountPesewas, String reference) {}

    public record Receipt(
            String merchantName,
            String receiptNumber,
            String orderNumber,
            String orderType,
            String tableNumber,
            OffsetDateTime createdAt,
            boolean reprint,
            List<Line> lines,
            long subtotalPesewas,
            long discountPesewas,
            long taxPesewas,
            long tipPesewas,
            long totalPesewas,
            long paidPesewas,
            long changePesewas,
            List<Payment> payments
    ) {}

    private ReceiptText() {}

    public static String render(Receipt r, int width) {
        List<String> out = new ArrayList<>();
        String rule = "=".repeat(width);
        String dash = "-".repeat(width);

        out.add(rule);
        if (r.reprint()) {
            out.add(center("*** REPRINT ***", width));
        }
        String shop = (r.merchantName() == null || r.merchantName().isBlank() ? "RECEIPT" : r.merchantName().strip())
                .toUpperCase();
        for (String line : KitchenTicketText.wrap(shop, width, "")) {
            out.add(center(line, width));
        }
        out.add(rule);

        if (r.receiptNumber() != null && !r.receiptNumber().isBlank()) {
            out.addAll(KitchenTicketText.wrap("Receipt: " + r.receiptNumber().strip(), width, "  "));
        }
        if (r.orderNumber() != null && !r.orderNumber().isBlank()) {
            out.addAll(KitchenTicketText.wrap("Order: " + r.orderNumber().strip(), width, "  "));
        }
        if (r.createdAt() != null) {
            out.addAll(KitchenTicketText.wrap("Date: " + DATE_TIME.format(r.createdAt().atZoneSameInstant(ACCRA)), width, "  "));
        }
        String mode = r.orderType() == null ? "" : r.orderType().replace('_', ' ').strip();
        String where = r.tableNumber() == null || r.tableNumber().isBlank()
                ? mode
                : (mode.isEmpty() ? "TABLE " + r.tableNumber().strip() : mode + "  TABLE " + r.tableNumber().strip());
        if (!where.isBlank()) {
            out.addAll(KitchenTicketText.wrap(where, width, ""));
        }

        out.add(dash);
        if (r.lines() != null) {
            for (Line line : r.lines()) {
                String left = qty(line.quantity()) + " x " + (line.name() == null ? "" : line.name().strip());
                String right = "GHS " + money(line.totalPesewas());
                addSpread(out, left, right, width);
            }
        }
        out.add(dash);

        addSpread(out, "Subtotal", "GHS " + money(r.subtotalPesewas()), width);
        if (r.discountPesewas() > 0) {
            addSpread(out, "Discount", "-GHS " + money(r.discountPesewas()), width);
        }
        if (r.taxPesewas() > 0) {
            addSpread(out, "Tax/VAT", "GHS " + money(r.taxPesewas()), width);
        }
        if (r.tipPesewas() > 0) {
            addSpread(out, "Tip", "GHS " + money(r.tipPesewas()), width);
        }
        out.add(rule);
        addSpread(out, "TOTAL", "GHS " + money(r.totalPesewas()), width);
        out.add(rule);

        if (r.payments() != null && !r.payments().isEmpty()) {
            out.add("PAYMENTS:");
            for (Payment p : r.payments()) {
                String label = p.tenderType() == null ? "PAYMENT" : p.tenderType().replace('_', ' ');
                addSpread(out, " " + label, "GHS " + money(p.amountPesewas()), width);
                if (p.reference() != null && !p.reference().isBlank()) {
                    out.addAll(KitchenTicketText.wrap("Ref: " + p.reference().strip(), width - 2, "  ")
                            .stream().map(s -> "  " + s).toList());
                }
            }
        }
        if (r.changePesewas() > 0) {
            addSpread(out, "CHANGE", "GHS " + money(r.changePesewas()), width);
        }

        out.add(dash);
        out.add(center("THANK YOU!", width));
        out.add(rule);
        return String.join("\n", out) + "\n";
    }

    private static void addSpread(List<String> out, String left, String right, int width) {
        if (left.length() + 1 + right.length() <= width) {
            out.add(left + " ".repeat(width - left.length() - right.length()) + right);
            return;
        }
        List<String> wrapped = KitchenTicketText.wrap(left, width, "  ");
        out.addAll(wrapped);
        if (right.length() <= width) {
            out.add(" ".repeat(width - right.length()) + right);
        } else {
            out.addAll(KitchenTicketText.wrap(right, width, ""));
        }
    }

    private static String qty(BigDecimal q) {
        return q == null ? "1" : q.stripTrailingZeros().toPlainString();
    }

    private static String money(long pesewas) {
        long abs = Math.abs(pesewas);
        String prefix = pesewas < 0 ? "-" : "";
        return String.format("%s%d.%02d", prefix, abs / 100L, abs % 100L);
    }

    private static String center(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return " ".repeat((width - s.length()) / 2) + s;
    }
}
