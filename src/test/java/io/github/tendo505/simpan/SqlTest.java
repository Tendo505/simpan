package io.github.tendo505.simpan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tendo505.simpan.engine.Database;
import io.github.tendo505.simpan.engine.Session;
import io.github.tendo505.simpan.types.Values;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SQL end to end: what you type, what comes back. */
class SqlTest {
    @TempDir
    Path dir;
    Database db;
    Session s;

    @BeforeEach
    void open() {
        db = Database.open(dir, new Database.Options(256, false, 64L << 20));
        s = new Session(db);
    }

    @AfterEach
    void close() {
        s.close();
        db.close();
    }

    /** Runs a query and returns its rows as text, one string per row: {@code "1|ann|NULL"}. */
    List<String> q(String sql, Object... params) {
        Session.Result r = s.run(sql, params);
        List<String> out = new ArrayList<>();
        for (Object[] row : r.rows()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < row.length; i++) sb.append(i == 0 ? "" : "|").append(row[i] == null ? "NULL" : Values.format(row[i]));
            out.add(sb.toString());
        }
        return out;
    }

    String tag(String sql) {
        return s.run(sql).tag();
    }

    String error(String sql) {
        return assertThrows(SqlException.class, () -> s.run(sql)).sqlState();
    }

    void people() {
        s.execute("""
                CREATE TABLE people (id int PRIMARY KEY, name text NOT NULL, age int, city text);
                INSERT INTO people VALUES (1, 'ann', 31, 'KL'), (2, 'ben', 25, 'Penang'), (3, 'chong', NULL, 'KL'),
                                          (4, 'devi', 40, 'Ipoh'), (5, 'farid', 25, NULL);
                CREATE TABLE orders (id int PRIMARY KEY, person_id int, total double precision);
                INSERT INTO orders VALUES (10, 1, 12.5), (11, 1, 7.25), (12, 2, 100), (13, 4, 3), (14, 9, 1);
                """);
    }

    @Test
    void basicsWork() {
        people();
        assertEquals(List.of("ann|31", "devi|40"), q("SELECT name, age FROM people WHERE age > 30 ORDER BY age"));
        assertEquals(List.of("5"), q("SELECT count(*) FROM people"));
        assertEquals(List.of("4"), q("SELECT count(age) FROM people"));
        assertEquals(List.of("KL|2", "Ipoh|1", "Penang|1", "NULL|1"), q("SELECT city, count(*) FROM people GROUP BY city ORDER BY count(*) DESC, city NULLS LAST"));
        assertEquals(List.of("30.25"), q("SELECT avg(age) FROM people"));
        assertEquals(List.of("ann|19.75", "ben|100", "devi|3"),
                q("SELECT p.name, sum(o.total) FROM people p JOIN orders o ON o.person_id = p.id GROUP BY p.name ORDER BY 1"));
        assertEquals(List.of("ann|2", "ben|1", "chong|0", "devi|1", "farid|0"),
                q("SELECT p.name, count(o.id) FROM people p LEFT JOIN orders o ON o.person_id = p.id GROUP BY p.name ORDER BY p.name"));
        assertEquals(List.of("3"), q("SELECT 1 + 2"));
        assertEquals(List.of("ben", "farid"), q("SELECT name FROM people WHERE age = $1 ORDER BY name", "25"));
        assertEquals(List.of("chong"), q("SELECT name FROM people WHERE age IS NULL"));
        assertEquals(List.of("ann", "chong"), q("SELECT name FROM people WHERE city = 'KL' ORDER BY 1"));
        assertEquals(List.of("ann", "ben"), q("SELECT name FROM people ORDER BY id LIMIT 2"));
        assertEquals(List.of("ann", "ben", "devi", "farid"), q("SELECT name FROM people WHERE id IN (SELECT person_id FROM orders WHERE total < 50) OR age < 30 ORDER BY name"));
        assertEquals(List.of("2"), q("SELECT count(DISTINCT age) FROM people WHERE age < 35"));
        assertEquals(List.of("adult"), q("SELECT CASE WHEN age >= 18 THEN 'adult' ELSE 'child' END FROM people WHERE id = 1"));
    }

    @Test
    void writesAndConstraints() {
        people();
        assertEquals("UPDATE 2", tag("UPDATE people SET age = age + 1 WHERE age = 25"));
        assertEquals(List.of("ben|26", "farid|26"), q("SELECT name, age FROM people WHERE age = 26 ORDER BY name"));
        assertEquals("DELETE 1", tag("DELETE FROM people WHERE name = 'chong'"));
        assertEquals(List.of("4"), q("SELECT count(*) FROM people"));
        assertEquals(SqlException.UNIQUE, error("INSERT INTO people VALUES (1, 'again', 1, 'x')"));
        assertEquals(SqlException.NOT_NULL, error("INSERT INTO people (id) VALUES (99)"));
        assertEquals(List.of("6|zed"), q("INSERT INTO people (id, name) VALUES (6, 'zed') RETURNING id, name"));
        assertEquals(SqlException.UNDEFINED_TABLE, error("SELECT * FROM nope"));
        assertEquals(SqlException.UNDEFINED_COLUMN, error("SELECT nope FROM people"));
        assertEquals(SqlException.GROUPING, error("SELECT name, count(*) FROM people"));
        assertEquals(SqlException.DIVISION_BY_ZERO, error("SELECT 1 / 0"));
        assertEquals(SqlException.UNDEFINED_FUNCTION, error("SELECT name + 1 FROM people"));
    }

    @Test
    void transactionsCommitAndRollBack() {
        people();
        s.run("BEGIN");
        s.run("DELETE FROM people");
        assertEquals(List.of("0"), q("SELECT count(*) FROM people"));
        s.run("ROLLBACK");
        assertEquals(List.of("5"), q("SELECT count(*) FROM people"));

        s.run("BEGIN");
        s.run("INSERT INTO people VALUES (7, 'gina', 22, 'KL')");
        assertEquals(SqlException.UNIQUE, error("INSERT INTO people VALUES (7, 'dup', 1, 'x')"));
        assertEquals(SqlException.IN_FAILED_TRANSACTION, error("SELECT 1"));
        assertEquals("ROLLBACK", tag("COMMIT")); // a failed block can only roll back
        assertEquals(List.of(), q("SELECT name FROM people WHERE id = 7"));
    }

    /** H2 (the fuzzer's reference) has no FULL JOIN, so this checks it against one worked out by hand, for hash and nested-loop joins. */
    @Test
    void fullJoinMatchesAHandComputedOne() {
        java.util.Random rnd = new java.util.Random(3);
        s.run("CREATE TABLE l (id int PRIMARY KEY, k int)");
        s.run("CREATE TABLE r (id int PRIMARY KEY, k int)");
        s.run("CREATE INDEX r_k ON r (k)");
        Long[] lk = new Long[40];
        Long[] rk = new Long[30];
        for (int i = 0; i < lk.length; i++) lk[i] = rnd.nextInt(6) == 0 ? null : (long) rnd.nextInt(8);
        for (int i = 0; i < rk.length; i++) rk[i] = rnd.nextInt(6) == 0 ? null : (long) rnd.nextInt(8);
        for (int i = 0; i < lk.length; i++) s.run("INSERT INTO l VALUES ($1, $2)", String.valueOf(i), lk[i] == null ? null : lk[i].toString());
        for (int i = 0; i < rk.length; i++) s.run("INSERT INTO r VALUES ($1, $2)", String.valueOf(i), rk[i] == null ? null : rk[i].toString());
        for (String op : new String[] {"=", "<"}) {
            List<String> expected = new ArrayList<>();
            boolean[] rightMatched = new boolean[rk.length];
            for (int i = 0; i < lk.length; i++) {
                boolean any = false;
                for (int j = 0; j < rk.length; j++) {
                    if (lk[i] == null || rk[j] == null) continue;
                    if (op.equals("=") ? lk[i].equals(rk[j]) : lk[i] < rk[j]) {
                        expected.add(i + "|" + j);
                        rightMatched[j] = true;
                        any = true;
                    }
                }
                if (!any) expected.add(i + "|NULL");
            }
            for (int j = 0; j < rk.length; j++) if (!rightMatched[j]) expected.add("NULL|" + j);
            java.util.Collections.sort(expected);
            List<String> actual = new ArrayList<>(q("SELECT l.id, r.id FROM l FULL JOIN r ON l.k " + op + " r.k"));
            java.util.Collections.sort(actual);
            assertEquals(expected, actual, "FULL JOIN ON l.k " + op + " r.k");
        }
    }

    /** Bag semantics of the set operations (H2 has no INTERSECT ALL or EXCEPT ALL to compare against). */
    @Test
    void setOperationsCountDuplicates() {
        s.execute("CREATE TABLE a (x int); CREATE TABLE b (x int); INSERT INTO a VALUES (1), (1), (1), (2), (3), (NULL); INSERT INTO b VALUES (1), (1), (2), (2), (4), (NULL);");
        assertEquals(List.of("1", "1", "2", "NULL"), q("SELECT x FROM a INTERSECT ALL SELECT x FROM b ORDER BY 1"));
        assertEquals(List.of("1", "3"), q("SELECT x FROM a EXCEPT ALL SELECT x FROM b ORDER BY 1"));
        assertEquals(List.of("1", "2", "NULL"), q("SELECT x FROM a INTERSECT SELECT x FROM b ORDER BY 1"));
        assertEquals(List.of("3"), q("SELECT x FROM a EXCEPT SELECT x FROM b ORDER BY 1"));
        assertEquals(List.of("1", "2", "3", "4", "NULL"), q("SELECT x FROM a UNION SELECT x FROM b ORDER BY x"));
        assertEquals(List.of("12"), q("SELECT count(*) FROM (SELECT x FROM a UNION ALL SELECT x FROM b) u"));
    }

    @Test
    void indexesAreUsedAndCorrect() {
        s.run("CREATE TABLE t (id int PRIMARY KEY, k int, v text)");
        StringBuilder sb = new StringBuilder("INSERT INTO t VALUES ");
        for (int i = 0; i < 2000; i++) sb.append(i == 0 ? "" : ",").append("(").append(i).append(",").append(i % 50).append(",'v").append(i).append("')");
        s.run(sb.toString());
        s.run("CREATE INDEX t_k ON t (k)");
        s.run("ANALYZE t");
        assertEquals(List.of("v1234"), q("SELECT v FROM t WHERE id = 1234"));
        String plan = String.join("\n", q("EXPLAIN SELECT v FROM t WHERE id = 1234"));
        assertTrue(plan.contains("Index Scan using t_pkey"), plan);
        assertEquals(List.of("40"), q("SELECT count(*) FROM t WHERE k = 7"));
        assertEquals(List.of("80"), q("SELECT count(*) FROM t WHERE k IN (7, 8, 7)"));
        assertEquals(List.of("3"), q("SELECT count(*) FROM t WHERE id > 1996.5"));
        assertEquals(List.of("2"), q("SELECT count(*) FROM t WHERE id BETWEEN 10 AND 11"));
        assertEquals(List.of(), q("SELECT v FROM t WHERE id = 2.5"));
    }
}
