package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Modular monolith rule for data: every table has one owning module, and only that module reads or writes
 * it. Other modules go through the owner's API or events. The back-office {@code operations} module may
 * read any table for reporting but never writes outside its own.
 */
class TableOwnershipTest {
    static final Path SOURCES = Path.of("src/main/java/com/elepay");
    static final Pattern ACCESS = Pattern.compile("\\b(FROM|JOIN|INTO|UPDATE)\\s+(\\w+)");
    static final Pattern WRITE = Pattern.compile("\\b(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+(\\w+)");
    static final String REPORTING = "operations";

    @Test
    void everyTableIsTouchedOnlyByItsOwner() throws IOException {
        Properties owners = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/table-owners.properties")) {
            owners.load(in);
        }
        Set<String> tables = tables();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Path rel = SOURCES.relativize(file);
                // Classes in the root package (the application and its legacy sandbox ledger) belong to "app".
                String module = rel.getNameCount() == 1 ? "app" : rel.getName(0).toString();
                String code = Files.readString(file);
                Set<String> written = new HashSet<>();
                Matcher w = WRITE.matcher(code);
                while (w.find()) written.add(w.group(2));
                Matcher m = ACCESS.matcher(code);
                while (m.find()) {
                    String table = m.group(2);
                    if (!tables.contains(table)) continue;
                    String owner = owners.getProperty(table);
                    if (owner == null) {
                        violations.add(table + ": no owner in table-owners.properties (used by " + module + ")");
                    } else if (!owner.equals(module)
                            && !(module.equals(REPORTING) && !written.contains(table))) {
                        violations.add(module + " -> " + table + " (owned by " + owner + ") in " + SOURCES.relativize(file));
                    }
                }
            }
        }
        assertThat(violations).as("Use the owning module's API or events instead").isEmpty();
    }

    private static Set<String> tables() throws IOException {
        Set<String> tables = new HashSet<>();
        Pattern create = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?(\\w+)", Pattern.CASE_INSENSITIVE);
        Pattern drop = Pattern.compile("DROP TABLE IF EXISTS (\\w+)", Pattern.CASE_INSENSITIVE);
        Set<String> dropped = new HashSet<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path f : files.toList()) {
                String sql = Files.readString(f);
                Matcher c = create.matcher(sql);
                while (c.find()) tables.add(c.group(1));
                Matcher d = drop.matcher(sql);
                while (d.find()) dropped.add(d.group(1));
            }
        }
        tables.removeAll(dropped);
        return tables;
    }
}
