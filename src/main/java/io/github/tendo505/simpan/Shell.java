package io.github.tendo505.simpan;

import io.github.tendo505.simpan.catalog.Catalog.Column;
import io.github.tendo505.simpan.catalog.Catalog.IndexDef;
import io.github.tendo505.simpan.catalog.Catalog.TableDef;
import io.github.tendo505.simpan.engine.Database;
import io.github.tendo505.simpan.engine.Session;
import io.github.tendo505.simpan.types.Type;
import io.github.tendo505.simpan.types.Values;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** An interactive SQL prompt, laid out like psql. */
final class Shell {
    private final Database db;
    private final Session session;
    private final PrintStream out;
    private boolean timing;

    Shell(Database db, PrintStream out) {
        this.db = db;
        this.session = new Session(db);
        this.out = out;
    }

    void run() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        out.println("Simpan 1.0.0, a SQL database written in Java. Type \\? for help, \\q to quit.");
        StringBuilder buffer = new StringBuilder();
        while (true) {
            out.print(buffer.isEmpty() ? "simpan=> " : "simpan-> ");
            out.flush();
            String line = in.readLine();
            if (line == null) break;
            String trimmed = line.trim();
            if (buffer.isEmpty() && trimmed.startsWith("\\")) {
                if (!meta(trimmed)) break;
                continue;
            }
            buffer.append(line).append('\n');
            if (endsStatement(buffer)) {
                runSql(buffer.toString(), false);
                buffer.setLength(0);
            }
        }
        session.close();
    }

    /**
     * True if the text ends with a semicolon that isn't inside a string,
     * a quoted name or a comment, so {@code DELETE FROM t -- oops;} waits
     * for the rest of the statement instead of running without its WHERE.
     */
    static boolean endsStatement(CharSequence sql) {
        boolean ends = false;
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                for (i++; i < n && !(sql.charAt(i) == '\'' && (i + 1 >= n || sql.charAt(i + 1) != '\'')); i++) {
                    if (sql.charAt(i) == '\'') i++; // '' inside a string
                }
                if (i >= n) return false; // still inside a string
                ends = false;
            } else if (c == '"') {
                for (i++; i < n && sql.charAt(i) != '"'; i++) {
                    // inside a quoted name
                }
                if (i >= n) return false;
                ends = false;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.toString().indexOf("*/", i + 2);
                if (end < 0) return false; // still inside a comment
                i = end + 1;
            } else if (c == ';') {
                ends = true;
            } else if (!Character.isWhitespace(c)) {
                ends = false;
            }
        }
        return ends;
    }

    /**
     * Runs statements one at a time, printing each result as it comes. With
     * {@code together}, they run as one implicit transaction that stops (and
     * rolls back) at the first error, like {@code psql -c}; otherwise each
     * stands alone, like {@code psql -f}.
     */
    void runSql(String sql, boolean together) {
        long start = System.nanoTime();
        List<io.github.tendo505.simpan.sql.Ast.Statement> statements;
        try {
            statements = io.github.tendo505.simpan.sql.Parser.parseScript(sql);
        } catch (SqlException e) {
            error(e);
            return;
        } catch (StackOverflowError e) {
            error(new SqlException("54001", "stack depth limit exceeded (the statement is nested too deeply)"));
            return;
        }
        boolean batch = together && statements.size() > 1;
        if (batch) session.beginImplicit();
        boolean ok = true;
        for (var statement : statements) {
            try {
                print(session.execute(statement, List.of()));
            } catch (SqlException e) {
                error(e);
                ok = false;
                if (together) break;
            }
        }
        if (batch) session.endImplicit(ok);
        if (timing) out.printf(Locale.ROOT, "Time: %.3f ms%n", (System.nanoTime() - start) / 1e6);
    }

    private void error(SqlException e) {
        out.println("ERROR:  " + e.getMessage() + "  (SQLSTATE " + e.sqlState() + ")");
    }

    private boolean meta(String cmd) {
        String[] parts = cmd.split("\\s+");
        switch (parts[0]) {
            case "\\q" -> {
                return false;
            }
            case "\\timing" -> {
                timing = !timing;
                out.println("Timing is " + (timing ? "on." : "off."));
            }
            case "\\d" -> {
                if (parts.length > 1) describe(parts[1]);
                else listTables();
            }
            case "\\?" -> out.println("""
                    \\d            list tables
                    \\d NAME       describe a table
                    \\timing       show how long each statement takes
                    \\q            quit
                    End SQL statements with a semicolon.""");
            default -> out.println("invalid command " + parts[0] + "; try \\?");
        }
        return true;
    }

    private void listTables() {
        List<Object[]> rows = new ArrayList<>();
        for (TableDef t : db.catalog().tables()) rows.add(new Object[] {t.name(), (long) t.columns().size(), (long) t.indexes().size(), db.estimatedRows(db.table(t.name()))});
        print(new Session.Result("SELECT " + rows.size(), List.of("table", "columns", "indexes", "rows (estimated)"),
                List.of(Type.TEXT, Type.BIGINT, Type.BIGINT, Type.BIGINT), rows, List.of()));
    }

    private void describe(String name) {
        TableDef t = db.catalog().table(name);
        if (t == null) {
            out.println("Did not find any relation named \"" + name + "\".");
            return;
        }
        List<Object[]> rows = new ArrayList<>();
        for (Column c : t.columns()) {
            rows.add(new Object[] {c.name(), c.type().sqlName(), c.notNull() ? "not null" : "", c.defaultValue() == null ? "" : Values.format(c.defaultValue())});
        }
        out.println("Table \"" + name + "\"");
        print(new Session.Result("", List.of("Column", "Type", "Nullable", "Default"), List.of(Type.TEXT, Type.TEXT, Type.TEXT, Type.TEXT), rows, List.of()));
        if (!t.indexes().isEmpty()) {
            out.println("Indexes:");
            for (IndexDef i : t.indexes()) {
                List<String> cols = new ArrayList<>();
                for (int c : i.columns()) cols.add(t.columns().get(c).name());
                out.println("    \"" + i.name() + "\"" + (i.primary() ? " PRIMARY KEY," : i.unique() ? " UNIQUE," : "") + " btree (" + String.join(", ", cols) + ")");
            }
        }
    }

    /** Prints a result the way psql does: aligned columns, numbers on the right, then a row count. */
    void print(Session.Result r) {
        for (String n : r.notices()) out.println("NOTICE:  " + n);
        if (!r.hasRows()) {
            if (!r.tag().isEmpty()) out.println(r.tag());
            return;
        }
        int n = r.columns().size();
        int[] width = new int[n];
        List<String[]> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) width[i] = r.columns().get(i).length();
        for (Object[] row : r.rows()) {
            String[] c = new String[n];
            for (int i = 0; i < n; i++) {
                c[i] = row[i] == null ? "" : Values.format(row[i]);
                width[i] = Math.max(width[i], c[i].length());
            }
            cells.add(c);
        }
        boolean plan = n == 1 && r.columns().get(0).equals("QUERY PLAN");
        StringBuilder header = new StringBuilder();
        StringBuilder rule = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                header.append("|");
                rule.append("+");
            }
            String name = r.columns().get(i);
            int pad = width[i] - name.length();
            header.append(' ').append(" ".repeat(pad / 2)).append(name).append(" ".repeat(pad - pad / 2)).append(' ');
            rule.append("-".repeat(width[i] + 2));
        }
        out.println(header);
        out.println(rule);
        for (String[] c : cells) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) line.append("|");
                boolean right = r.types().get(i).isNumeric() && !plan;
                String pad = " ".repeat(width[i] - c[i].length());
                line.append(' ').append(right ? pad + c[i] : c[i] + pad).append(' ');
            }
            out.println(plan ? line.toString().stripTrailing() : line);
        }
        int rows = r.rows().size();
        out.println("(" + rows + (rows == 1 ? " row)" : " rows)"));
        out.println();
    }
}
