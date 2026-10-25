package io.github.tendo505.simpan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tendo505.simpan.engine.Database;
import io.github.tendo505.simpan.engine.Session;
import io.github.tendo505.simpan.types.Values;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One test per bug found in review, so none of them comes back. */
class RegressionTest {
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

    private String one(String sql) {
        Object v = s.run(sql).rows().get(0)[0];
        return v == null ? "NULL" : Values.format(v);
    }

    private List<String> column(String sql) {
        List<String> out = new ArrayList<>();
        for (Object[] r : s.run(sql).rows()) out.add(r[0] == null ? "NULL" : Values.format(r[0]));
        return out;
    }

    /** LIKE used a backtracking regex: '%a%a%a%a%a%b' against 3,000 a's ran for minutes. */
    @Test
    void likeIsLinearAndCorrect() {
        long start = System.nanoTime();
        assertEquals("f", one("SELECT repeat('a', 3000) LIKE '%a%a%a%a%a%b'"));
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "took " + (System.nanoTime() - start) / 1e6 + " ms");
        assertEquals("t", one("SELECT 'abc' LIKE 'a_c'"));
        assertEquals("t", one("SELECT 'a%b' LIKE 'a\\%b'"));
        assertEquals("f", one("SELECT 'axb' LIKE 'a\\%b'"));
        assertEquals("t", one("SELECT 'ABC' ILIKE 'a%c'"));
        assertEquals("t", one("SELECT '' LIKE '%'"));
        assertEquals("f", one("SELECT '' LIKE '_'"));
        assertEquals("t", one("SELECT 'héllo😀' LIKE 'h_llo_'"));
    }

    @Test
    void booleanToTextMatchesPostgres() {
        assertEquals("true", one("SELECT true::text"));
        assertEquals("xfalse", one("SELECT 'x' || false"));
    }

    /** repeat() with a huge count overflowed its size check, and the OutOfMemoryError left the row locked forever. */
    @Test
    void hugeRepeatIsAnErrorAndLeavesNothingLocked() throws Exception {
        s.execute("CREATE TABLE t (id int PRIMARY KEY, c text); INSERT INTO t VALUES (1, 'x');");
        SqlException e = assertThrows(SqlException.class, () -> s.run("UPDATE t SET c = repeat('ab', 4611686019501129728) WHERE id = 1"));
        assertEquals(SqlException.PROGRAM_LIMIT, e.sqlState());
        try (Session other = new Session(db)) {
            other.run("SET lock_timeout = '2s'");
            assertEquals("UPDATE 1", other.run("UPDATE t SET c = 'y' WHERE id = 1").tag(), "the row isn't still locked");
        }
    }

    @Test
    void betweenSymmetric() {
        assertEquals("t", one("SELECT 5 BETWEEN SYMMETRIC 10 AND 1"));
        assertEquals("f", one("SELECT 5 NOT BETWEEN SYMMETRIC 10 AND 1"));
        assertEquals("f", one("SELECT 5 BETWEEN 10 AND 1"));
    }

    @Test
    void leftAndRightAreFunctionsToo() {
        assertEquals("ab", one("SELECT left('abc', 2)"));
        assertEquals("bc", one("SELECT right('abc', 2)"));
    }

    @Test
    void nestedParenthesesInFrom() {
        s.execute("CREATE TABLE t1 (id int); CREATE TABLE t2 (id int); CREATE TABLE t3 (id int);"
                + "INSERT INTO t1 VALUES (1), (2); INSERT INTO t2 VALUES (1), (2); INSERT INTO t3 VALUES (2);");
        assertEquals("1", one("SELECT count(*) FROM ((t1 JOIN t2 ON t1.id = t2.id) JOIN t3 ON t2.id = t3.id)"));
        assertEquals("2", one("SELECT count(*) FROM ((SELECT * FROM t1) s JOIN t2 ON s.id = t2.id)"));
        assertEquals("2", one("SELECT count(*) FROM ((SELECT id FROM t1) UNION (SELECT id FROM t3)) u"));
    }

    /** sum(bigint) failed when a running total overflowed, even though the final total fits. */
    @Test
    void sumOnlyFailsIfTheTotalDoesntFit() {
        s.execute("CREATE TABLE n (v bigint); INSERT INTO n VALUES (9223372036854775807), (1), (-1);");
        assertEquals("9223372036854775807", one("SELECT sum(v) FROM n"));
        s.run("INSERT INTO n VALUES (1)");
        assertEquals(SqlException.OUT_OF_RANGE, assertThrows(SqlException.class, () -> s.run("SELECT sum(v) FROM n")).sqlState());
    }

    /** With more than 31 inputs the greedy join order used int masks: 32 hung, 33+ dropped tables. */
    @Test
    void manyTablesInOneJoin() {
        s.execute("CREATE TABLE one (a int); INSERT INTO one VALUES (1); CREATE TABLE two (b int); INSERT INTO two VALUES (1), (2);");
        StringBuilder from = new StringBuilder();
        for (int i = 1; i <= 32; i++) from.append(i == 1 ? "" : ", ").append("one t").append(i);
        assertEquals("2", one("SELECT count(*) FROM " + from + ", two t33"));
        assertEquals(List.of("1", "2"), column("SELECT t33.b FROM " + from + ", two t33 ORDER BY 1"));
    }

    /** A statement's subqueries saw the statement's own changes (they ran lazily, after the first row changed). */
    @Test
    void statementsDontSeeTheirOwnChanges() {
        s.execute("CREATE TABLE t (id int PRIMARY KEY, v int); INSERT INTO t VALUES (1, 1), (2, 2), (3, 3);");
        s.run("UPDATE t SET v = (SELECT sum(v) FROM t)");
        assertEquals(List.of("6", "6", "6"), column("SELECT v FROM t"));
        assertEquals(List.of("3", "3", "3"), column("DELETE FROM t RETURNING (SELECT count(*) FROM t)"));
        assertEquals(List.of("0"), column("INSERT INTO t VALUES (9, 9) RETURNING (SELECT count(*) FROM t)"));
    }

    /** Several statements sent together are one implicit transaction, as in PostgreSQL. */
    @Test
    void aBatchIsAllOrNothing() {
        s.run("CREATE TABLE m (id int PRIMARY KEY)");
        SqlException e = assertThrows(SqlException.class, () -> s.execute("INSERT INTO m VALUES (1); INSERT INTO m VALUES (1);"));
        assertEquals(SqlException.UNIQUE, e.sqlState());
        assertEquals("0", one("SELECT count(*) FROM m"));
        s.execute("INSERT INTO m VALUES (1); INSERT INTO m VALUES (2);");
        assertEquals("2", one("SELECT count(*) FROM m"));
        assertEquals('I', s.status());
    }

    /** A syntax error inside BEGIN ... COMMIT aborts the block too; COMMIT then rolls back. */
    @Test
    void syntaxErrorsAbortTheBlock() {
        s.run("CREATE TABLE m (id int PRIMARY KEY)");
        s.run("BEGIN");
        s.run("INSERT INTO m VALUES (50)");
        assertThrows(SqlException.class, () -> s.run("INSRT INTO m VALUES (51)"));
        assertEquals('E', s.status());
        assertEquals("ROLLBACK", s.run("COMMIT").tag());
        assertEquals("0", one("SELECT count(*) FROM m"));
    }

    /** lock_timeout was one global value: one session's setting changed everyone's. */
    @Test
    void lockTimeoutIsPerSession() throws Exception {
        s.execute("CREATE TABLE t (id int PRIMARY KEY, v int); INSERT INTO t VALUES (1, 0);");
        try (Session holder = new Session(db); Session impatient = new Session(db); Session patient = new Session(db)) {
            holder.run("BEGIN");
            holder.run("UPDATE t SET v = 1 WHERE id = 1");
            impatient.run("SET lock_timeout = 100");
            SqlException e = assertThrows(SqlException.class, () -> impatient.run("UPDATE t SET v = 2 WHERE id = 1"));
            assertEquals(SqlException.LOCK_TIMEOUT, e.sqlState());
            CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> patient.run("UPDATE t SET v = v + 10 WHERE id = 1").tag());
            Thread.sleep(400);
            assertFalse(waiting.isDone(), "a session that didn't set lock_timeout waits as long as it takes");
            holder.run("COMMIT");
            assertEquals("UPDATE 1", waiting.get(10, TimeUnit.SECONDS));
            assertEquals("11", one("SELECT v FROM t"));
        }
    }

    /** A WHERE with thousands of ORs overflowed the stack and killed the connection; now it's an ordinary error. */
    @Test
    void deeplyNestedStatementsAreAnError() {
        s.execute("CREATE TABLE t (id int PRIMARY KEY); INSERT INTO t VALUES (1);");
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM t WHERE id = 0");
        for (int i = 1; i < 60_000; i++) sql.append(" OR id = ").append(i);
        try {
            assertEquals("1", one(sql.toString()));
        } catch (SqlException e) {
            assertEquals("54001", e.sqlState());
        }
        assertEquals("1", one("SELECT count(*) FROM t"), "the session still works");
    }

    /** CREATE UNIQUE INDEX checked only committed rows, so an uncommitted duplicate could slip under it. */
    @Test
    void uniqueIndexWaitsForUncommittedDuplicates() throws Exception {
        s.execute("CREATE TABLE u (id int, v int); INSERT INTO u VALUES (1, 1);");
        for (boolean commits : new boolean[] {true, false}) {
            try (Session writer = new Session(db); Session ddl = new Session(db)) {
                writer.run("BEGIN");
                writer.run("INSERT INTO u VALUES (1, 2)");
                String name = commits ? "u_a" : "u_b";
                CompletableFuture<String> create = CompletableFuture.supplyAsync(() -> {
                    try {
                        return ddl.run("CREATE UNIQUE INDEX " + name + " ON u (id)").tag();
                    } catch (SqlException e) {
                        return e.sqlState();
                    }
                });
                Thread.sleep(300);
                assertFalse(create.isDone(), "the index build waits for the undecided insert");
                writer.run(commits ? "COMMIT" : "ROLLBACK");
                assertEquals(commits ? SqlException.UNIQUE : "CREATE INDEX", create.get(10, TimeUnit.SECONDS));
            }
            if (commits) s.run("DELETE FROM u WHERE v = 2");
        }
    }

    /** VACUUM queued for a table blocked a row-lock holder's next statement, while a waiter for that row held the table: a silent deadlock. */
    @Test
    void vacuumNeverBlocksARowLockHolder() throws Exception {
        s.execute("CREATE TABLE t (id int PRIMARY KEY, v int); INSERT INTO t VALUES (1, 0);");
        try (Session holder = new Session(db); Session waiter = new Session(db); Session vacuum = new Session(db)) {
            holder.run("BEGIN");
            holder.run("UPDATE t SET v = 1 WHERE id = 1");
            CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> waiter.run("UPDATE t SET v = v + 1 WHERE id = 1").tag());
            Thread.sleep(200);
            CompletableFuture<String> vacuuming = CompletableFuture.supplyAsync(() -> vacuum.run("VACUUM t").tag());
            Thread.sleep(200);
            CompletableFuture<String> next = CompletableFuture.supplyAsync(() -> holder.run("SELECT v FROM t").tag());
            assertEquals("SELECT 1", next.get(5, TimeUnit.SECONDS), "the lock holder can still use the table");
            holder.run("COMMIT");
            assertEquals("UPDATE 1", waiting.get(10, TimeUnit.SECONDS));
            assertEquals("VACUUM", vacuuming.get(10, TimeUnit.SECONDS));
            assertEquals("2", one("SELECT v FROM t"));
        }
    }

    @Test
    void theShellOnlyRunsCompleteStatements() {
        assertFalse(Shell.endsStatement("DELETE FROM t -- only the bad row;\n"));
        assertTrue(Shell.endsStatement("DELETE FROM t -- only the bad row;\nWHERE id = 5;\n"));
        assertFalse(Shell.endsStatement("INSERT INTO t VALUES ('a;\n"));
        assertTrue(Shell.endsStatement("INSERT INTO t VALUES ('a;\nb');\n"));
        assertTrue(Shell.endsStatement("SELECT 'it''s;' ; -- done\n"));
        assertFalse(Shell.endsStatement("SELECT 1 /* ; */"));
    }
}
