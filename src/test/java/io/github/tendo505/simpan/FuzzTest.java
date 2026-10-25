package io.github.tendo505.simpan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.tendo505.simpan.engine.Database;
import io.github.tendo505.simpan.engine.Session;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Differential testing: random schemas, data and queries, run on Simpan and
 * on H2 (in PostgreSQL mode), which must return the same rows. The queries
 * are well-typed by construction and cover joins of every kind, three-valued
 * logic with NULLs everywhere, IN and EXISTS subqueries, GROUP BY with
 * HAVING, DISTINCT, set operations, and ORDER BY with LIMIT. Random indexes
 * make the planner pick index scans and index joins too.
 */
class FuzzTest {
    @TempDir
    Path dir;

    private static final java.util.Map<String, Integer> H2_ERRORS = new java.util.TreeMap<>();

    private static final String[] TEXTS = {"a", "b", "ab", "ba", "abc", "", "zz"};
    private static final String[] LIKES = {"a%", "%b", "_", "%", "a_c", "", "%a%", "b_"};

    @Test
    void randomQueriesAgreeWithH2() throws Exception {
        int queries = 0;
        int bothFailed = 0;
        int h2Only = 0;
        for (int seed = 1; seed <= 6; seed++) {
            int[] counts = runSeed(seed, 500);
            queries += counts[0];
            bothFailed += counts[1];
            h2Only += counts[2];
        }
        System.out.printf("fuzz: %d random queries agreed with H2 (%d rejected by both, %d only H2 rejected)%n", queries, bothFailed, h2Only);
        H2_ERRORS.forEach((k, v) -> System.out.println("  H2 rejected " + v + "x: " + k.lines().findFirst().orElse("")));
        assertEquals(0, h2Only, "every generated query should be one H2 can check");
    }

    private int[] runSeed(int seed, int count) throws Exception {
        Random rnd = new Random(seed);
        Path d = dir.resolve("seed" + seed);
        try (Database db = Database.open(d, new Database.Options(64, false, 64L << 20));
             Session s = new Session(db);
             Connection h2 = DriverManager.getConnection("jdbc:h2:mem:fuzz" + seed + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
             Statement h = h2.createStatement()) {
            List<String> setup = schema(rnd);
            for (String sql : setup) {
                s.run(sql);
                h.execute(sql);
            }
            s.run("ANALYZE");
            Gen g = new Gen(rnd);
            int both = 0;
            int h2Only = 0;
            for (int i = 0; i < count; i++) {
                String sql = g.query();
                List<String> mine;
                List<String> theirs;
                String myError = null;
                String theirError = null;
                try {
                    mine = normalize(s.run(sql).rows());
                } catch (SqlException e) {
                    mine = null;
                    myError = e.sqlState() + " " + e.getMessage();
                }
                try (ResultSet rs = h.executeQuery(sql)) {
                    theirs = normalize(rs);
                } catch (SQLException e) {
                    theirs = null;
                    theirError = e.getSQLState() + " " + e.getMessage();
                }
                if (mine == null && theirs == null) {
                    both++;
                    continue;
                }
                if (mine != null && theirs == null) {
                    H2_ERRORS.merge(theirError.replaceAll("\\[[0-9-]+\\]", "").replaceAll("(?s)SQL statement:.*", "").trim(), 1, Integer::sum);
                    if (System.getenv("FUZZ_DEBUG") != null) System.out.println("H2 only: " + sql + "\n  " + theirError);
                    h2Only++; // H2 is stricter (or lacks a feature) here; PostgreSQL would accept it
                    continue;
                }
                if (mine == null) fail("seed " + seed + ": Simpan failed but H2 didn't\n" + sql + "\n" + myError + "\n" + setup);
                if (!mine.equals(theirs)) {
                    fail("seed " + seed + ": results differ for\n" + sql + "\nSimpan: " + mine + "\nH2:     " + theirs + "\nH2 error: " + theirError);
                }
            }
            return new int[] {count, both, h2Only};
        }
    }

    private static List<String> schema(Random rnd) {
        List<String> sql = new ArrayList<>();
        for (int t = 0; t < 3; t++) {
            sql.add("CREATE TABLE t" + t + " (id bigint PRIMARY KEY, a bigint, b double precision, c varchar(10), d boolean)");
            int rows = 5 + rnd.nextInt(40);
            StringBuilder ins = new StringBuilder("INSERT INTO t" + t + " VALUES ");
            for (int r = 0; r < rows; r++) {
                ins.append(r == 0 ? "" : ", ").append('(').append(r).append(", ")
                        .append(maybe(rnd, String.valueOf(rnd.nextInt(11) - 5))).append(", ")
                        .append(maybe(rnd, String.valueOf((rnd.nextInt(21) - 10) * 0.5))).append(", ")
                        .append(maybe(rnd, "'" + TEXTS[rnd.nextInt(TEXTS.length)] + "'")).append(", ")
                        .append(maybe(rnd, rnd.nextBoolean() ? "true" : "false")).append(')');
            }
            sql.add(ins.toString());
            if (rnd.nextBoolean()) sql.add("CREATE INDEX t" + t + "_a ON t" + t + " (a)");
            if (rnd.nextBoolean()) sql.add("CREATE INDEX t" + t + "_ca ON t" + t + " (c, a)");
            if (rnd.nextBoolean()) sql.add("CREATE INDEX t" + t + "_b ON t" + t + " (b)");
        }
        return sql;
    }

    private static String maybe(Random rnd, String v) {
        return rnd.nextInt(5) == 0 ? "NULL" : v;
    }

    // ---------------- result normalization ----------------

    /** Rows as sorted strings, numbers rounded to 6 places: order-insensitive, type-insensitive (1 vs 1.0). */
    private static List<String> normalize(List<Object[]> rows) {
        List<String> out = new ArrayList<>();
        for (Object[] r : rows) {
            StringBuilder sb = new StringBuilder();
            for (Object v : r) sb.append(value(v)).append('|');
            out.add(sb.toString());
        }
        Collections.sort(out);
        return out;
    }

    private static List<String> normalize(ResultSet rs) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        int n = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            Object[] row = new Object[n];
            for (int i = 0; i < n; i++) row[i] = rs.getObject(i + 1);
            rows.add(row);
        }
        return normalize(rows);
    }

    private static String value(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number num) {
            BigDecimal d = num instanceof BigDecimal b ? b : new BigDecimal(num.toString());
            d = d.setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros();
            return d.signum() == 0 ? "0" : d.toPlainString();
        }
        if (v instanceof Boolean b) return b ? "true" : "false";
        return "'" + v + "'";
    }

    // ---------------- the query generator ----------------

    /** Builds random, well-typed SQL over tables t0..t2 (columns id, a bigint; b double; c text; d boolean). */
    private static final class Gen {
        private final Random rnd;
        private List<String> aliases = new ArrayList<>();
        private int depth;

        Gen(Random rnd) {
            this.rnd = rnd;
        }

        private boolean chance(int pct) {
            return rnd.nextInt(100) < pct;
        }

        private <T> T pick(List<T> items) {
            return items.get(rnd.nextInt(items.size()));
        }

        String query() {
            if (chance(12)) {
                String[] ops = {"UNION", "UNION ALL", "INTERSECT", "EXCEPT"}; // H2 lacks INTERSECT/EXCEPT ALL: see SqlTest
                String type = chance(50) ? "int" : "text";
                return simple(type) + " " + ops[rnd.nextInt(ops.length)] + " " + simple(type);
            }
            return select();
        }

        /** A one-column select of the given type, for set operations and subqueries. */
        String simple(String type) {
            List<String> saved = aliases;
            aliases = new ArrayList<>(List.of("s" + rnd.nextInt(1000)));
            String table = "t" + rnd.nextInt(3);
            String expr = type.equals("int") ? intExpr() : textExpr();
            String where = chance(60) ? " WHERE " + bool() : "";
            String sql = "SELECT " + expr + " FROM " + table + " " + aliases.get(0) + where;
            aliases = saved;
            return sql;
        }

        String select() {
            List<String> saved = aliases;
            aliases = new ArrayList<>();
            String from = from();
            String where = chance(70) ? " WHERE " + bool() : "";
            String sql;
            if (chance(30)) {
                // Aggregation: group by plain columns, select them and some aggregates.
                List<String> groups = new ArrayList<>();
                int g = rnd.nextInt(3);
                for (int i = 0; i < g; i++) groups.add(column(pick(List.of("a", "c", "d", "b"))));
                List<String> items = new ArrayList<>(groups);
                int aggs = 1 + rnd.nextInt(3);
                for (int i = 0; i < aggs; i++) items.add(aggregate());
                String having = !groups.isEmpty() && chance(40) ? " HAVING count(*) > " + rnd.nextInt(3) : "";
                sql = "SELECT " + String.join(", ", items) + " FROM " + from + where
                        + (groups.isEmpty() ? "" : " GROUP BY " + String.join(", ", groups)) + having;
                if (chance(30)) sql += " ORDER BY " + positions(items.size()) + " LIMIT " + (1 + rnd.nextInt(5));
            } else {
                List<String> items = new ArrayList<>();
                int n = 1 + rnd.nextInt(3);
                for (int i = 0; i < n; i++) items.add(anyExpr());
                sql = "SELECT " + (chance(20) ? "DISTINCT " : "") + String.join(", ", items) + " FROM " + from + where;
                if (chance(30)) sql += " ORDER BY " + positions(items.size()) + " LIMIT " + rnd.nextInt(8);
            }
            aliases = saved;
            return sql;
        }

        /** ORDER BY every column, so LIMIT's choice is deterministic. */
        private String positions(int n) {
            List<String> p = new ArrayList<>();
            for (int i = 1; i <= n; i++) p.add(i + (chance(30) ? " DESC" : ""));
            return String.join(", ", p);
        }

        private String from() {
            String first = table();
            StringBuilder sb = new StringBuilder(first);
            int joins = rnd.nextInt(3);
            for (int i = 0; i < joins; i++) {
                String right = table();
                String kind = pick(List.of("JOIN", "LEFT JOIN", "CROSS JOIN", "JOIN", "LEFT JOIN", "RIGHT JOIN")); // H2 has no FULL JOIN: see SqlTest
                sb.append(' ').append(kind).append(' ').append(right);
                if (!kind.equals("CROSS JOIN")) {
                    String l = aliases.get(rnd.nextInt(aliases.size() - 1));
                    String r = aliases.get(aliases.size() - 1);
                    String col = pick(List.of("a", "id", "c", "a"));
                    String on = l + "." + col + " = " + r + "." + col;
                    if (chance(30)) on += (chance(50) ? " AND " : " OR ") + bool();
                    sb.append(" ON ").append(on);
                }
            }
            return sb.toString();
        }

        private String table() {
            String alias = "x" + aliases.size();
            aliases.add(alias);
            return "t" + rnd.nextInt(3) + " " + alias;
        }

        private String column(String name) {
            return pick(aliases) + "." + name;
        }

        private String aggregate() {
            return switch (rnd.nextInt(7)) {
                case 0 -> "count(*)";
                case 1 -> "count(" + column(pick(List.of("a", "c", "b"))) + ")";
                case 2 -> "sum(" + column("a") + ")";
                case 3 -> "min(" + column(pick(List.of("a", "c", "b"))) + ")";
                case 4 -> "max(" + column(pick(List.of("a", "c", "b"))) + ")";
                case 5 -> "count(DISTINCT " + column(pick(List.of("a", "c"))) + ")";
                default -> "sum(" + column("b") + ")";
            };
        }

        private String anyExpr() {
            return switch (rnd.nextInt(4)) {
                case 0 -> intExpr();
                case 1 -> textExpr();
                case 2 -> doubleExpr();
                default -> column(pick(List.of("a", "c", "d", "id")));
            };
        }

        String intExpr() {
            if (++depth > 3) {
                depth--;
                return chance(50) ? column(pick(List.of("a", "id"))) : String.valueOf(rnd.nextInt(7) - 3);
            }
            String e = switch (rnd.nextInt(8)) {
                case 0, 1 -> column(pick(List.of("a", "a", "id")));
                case 2 -> String.valueOf(rnd.nextInt(7) - 3);
                case 3 -> "(" + intExpr() + " + " + intExpr() + ")";
                case 4 -> "(" + intExpr() + " - " + intExpr() + ")";
                case 5 -> "(" + intExpr() + " * " + (rnd.nextInt(5) - 2) + ")";
                case 6 -> "CASE WHEN " + bool() + " THEN " + intExpr() + " ELSE " + intExpr() + " END";
                default -> "coalesce(" + intExpr() + ", " + (rnd.nextInt(5) - 2) + ")";
            };
            depth--;
            return e;
        }

        String doubleExpr() {
            return switch (rnd.nextInt(3)) {
                case 0 -> column("b");
                case 1 -> "(" + column("b") + " + " + intExpr() + ")";
                default -> "(" + column("b") + " * 2)";
            };
        }

        String textExpr() {
            if (++depth > 3) {
                depth--;
                return column("c");
            }
            String e = switch (rnd.nextInt(6)) {
                case 0, 1 -> column("c");
                case 2 -> "'" + TEXTS[rnd.nextInt(TEXTS.length)] + "'";
                case 3 -> "(" + textExpr() + " || " + textExpr() + ")";
                case 4 -> "lower(" + textExpr() + ")";
                default -> "coalesce(" + column("c") + ", 'n')";
            };
            depth--;
            return e;
        }

        String bool() {
            if (++depth > 3) {
                depth--;
                return column("a") + " > " + (rnd.nextInt(5) - 2);
            }
            String e = switch (rnd.nextInt(15)) {
                case 0 -> intExpr() + " " + pick(List.of("=", "<>", "<", "<=", ">", ">=")) + " " + intExpr();
                case 1 -> column("a") + " " + pick(List.of("=", "<", ">=")) + " " + (rnd.nextInt(7) - 3);
                case 2 -> doubleExpr() + " " + pick(List.of("=", "<", ">")) + " " + intExpr();
                case 3 -> textExpr() + " " + pick(List.of("=", "<>", "<", ">")) + " " + textExpr();
                case 4 -> column(pick(List.of("a", "b", "c", "d"))) + (chance(50) ? " IS NULL" : " IS NOT NULL");
                case 5 -> column("a") + (chance(30) ? " NOT" : "") + " IN (" + (rnd.nextInt(5) - 2) + ", " + (rnd.nextInt(5) - 2) + (chance(30) ? ", NULL" : "") + ")";
                case 6 -> intExpr() + (chance(30) ? " NOT" : "") + " BETWEEN " + (rnd.nextInt(5) - 3) + " AND " + (rnd.nextInt(5) - 1);
                case 7 -> column("c") + (chance(30) ? " NOT" : "") + " LIKE '" + LIKES[rnd.nextInt(LIKES.length)] + "'";
                case 8 -> column("d");
                case 9 -> "(" + bool() + " AND " + bool() + ")";
                case 10 -> "(" + bool() + " OR " + bool() + ")";
                case 11 -> "NOT (" + bool() + ")";
                case 12 -> column("a") + (chance(30) ? " NOT" : "") + " IN (" + simple("int") + ")";
                case 13 -> (chance(30) ? "NOT " : "") + "EXISTS (" + simple("int") + ")";
                default -> column("a") + " > (SELECT " + pick(List.of("min", "max")) + "(a) FROM t" + rnd.nextInt(3) + ")";
            };
            depth--;
            return e;
        }
    }

    @Test
    void normalizationTreatsIntegersAndDecimalsAlike() {
        assertEquals(normalize(List.<Object[]>of(new Object[] {1L, 2.50})), normalize(List.<Object[]>of(new Object[] {new BigDecimal("1.000"), 2.5})));
    }
}
