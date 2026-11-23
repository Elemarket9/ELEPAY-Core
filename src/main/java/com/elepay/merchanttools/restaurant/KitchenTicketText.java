package com.elepay.merchanttools.restaurant;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-text kitchen ticket for thermal printers (32 columns on 58mm paper, 42/48 on 80mm).
 * The printing device adds ESC/POS formatting (bold header, cut); the text alone must still read correctly.
 */
final class KitchenTicketText {
    private static final ZoneId ACCRA = ZoneId.of("Africa/Accra");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    record Line(BigDecimal quantity, String name, String modifiers, String course) {}

    record Ticket(String kind, String stationName, Integer ticketNumber, String diningMode, String tableNumber,
                  String serverName, OffsetDateTime createdAt, String notes, List<Line> lines) {}

    private KitchenTicketText() {}

    static String render(Ticket t, int width) {
        List<String> out = new ArrayList<>();
        String rule = "=".repeat(width);
        out.add(rule);
        switch (t.kind()) {
            case "VOID" -> out.add(center("*** VOID - CANCELLED ***", width));
            case "REPRINT" -> out.add(center("REPRINT", width));
            case "FALLBACK" -> out.add(center("SCREEN OFFLINE - PRINTED", width));
            default -> { }
        }
        out.add(center(t.stationName() == null ? "KITCHEN" : t.stationName().toUpperCase(), width));
        String number = t.ticketNumber() == null ? "" : String.format("#%03d", t.ticketNumber());
        String mode = t.diningMode() == null ? "" : t.diningMode().replace('_', ' ');
        String where = t.tableNumber() == null ? mode : mode + "  TABLE " + t.tableNumber();
        out.addAll(wrap((number + "  " + where).strip(), width, ""));
        String time = t.createdAt() == null ? "" : TIME.format(t.createdAt().atZoneSameInstant(ACCRA));
        String server = t.serverName() == null || t.serverName().isBlank() ? "" : "Server: " + t.serverName();
        out.add(spread(server, time, width));
        out.add("-".repeat(width));
        String course = null;
        for (Line line : t.lines()) {
            if (line.course() != null && !line.course().equals(course) && !"ALL".equals(line.course())) {
                course = line.course();
                out.add("[" + course + "]");
            }
            out.addAll(wrap(qty(line.quantity()) + " x " + line.name(), width, "    "));
            if (line.modifiers() != null && !line.modifiers().isBlank() && !"[]".equals(line.modifiers().strip()))
                out.addAll(wrap("+ " + line.modifiers().strip(), width - 4, "  ").stream().map(s -> "    " + s).toList());
        }
        if (t.notes() != null && !t.notes().isBlank()) {
            out.add("-".repeat(width));
            out.addAll(wrap("NOTE: " + t.notes().strip(), width, "      "));
        }
        out.add(rule);
        return String.join("\n", out) + "\n";
    }

    private static String qty(BigDecimal q) {
        return q == null ? "1" : q.stripTrailingZeros().toPlainString();
    }

    private static String center(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return " ".repeat((width - s.length()) / 2) + s;
    }

    private static String spread(String left, String right, int width) {
        int gap = width - left.length() - right.length();
        if (gap < 1) return (left + " " + right).length() <= width ? left + " " + right : left;
        return left + " ".repeat(gap) + right;
    }

    /** Word-wrap to the paper width; continuation lines get the indent. Over-long words are hard-split. */
    static List<String> wrap(String text, int width, String indent) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.replaceAll("\\s+", " ").strip().split(" ")) {
            String prefix = lines.isEmpty() ? "" : indent;
            while (word.length() > width - indent.length()) {
                if (!cur.isEmpty()) { lines.add(cur.toString()); cur.setLength(0); }
                int room = width - (lines.isEmpty() ? 0 : indent.length());
                lines.add((lines.isEmpty() ? "" : indent) + word.substring(0, room));
                word = word.substring(room);
            }
            if (cur.isEmpty()) {
                cur.append(lines.isEmpty() ? "" : indent).append(word);
            } else if (cur.length() + 1 + word.length() <= width) {
                cur.append(' ').append(word);
            } else {
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(indent).append(word);
            }
        }
        if (!cur.isEmpty()) lines.add(cur.toString());
        return lines;
    }
}
