package io.github.tendo505.simpan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tendo505.simpan.engine.Database;
import io.github.tendo505.simpan.engine.Session;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What concurrent transactions see and are allowed to do, matching PostgreSQL's rules. */
class IsolationTest {
    @TempDir
    Path dir;
    Database db;

    @BeforeEach
    void open() {
        db = Database.open(dir, new Database.Options(256, false, 64L << 20));
        try (Session s = new Session(db)) {
            s.execute("CREATE TABLE counter (id int PRIMARY KEY, n int); INSERT INTO counter VALUES (1, 0), (2, 0);");
        }
    }

    @AfterEach
    void close() {
        db.close();
    }

    private static long value(Session s, String sql) {
        return (Long) s.run(sql).rows().get(0)[0];
    }

    @Test
    void uncommittedChangesAreInvisible() {
        try (Session a = new Session(db); Session b = new Session(db)) {
            a.run("BEGIN");
            a.run("UPDATE counter SET n = 5 WHERE id = 1");
            assertEquals(5, value(a, "SELECT n FROM counter WHERE id = 1"), "a sees its own change");
            assertEquals(0, value(b, "SELECT n FROM counter WHERE id = 1"), "b doesn't see it yet");
            a.run("COMMIT");
            assertEquals(5, value(b, "SELECT n FROM counter WHERE id = 1"));
        }
    }

    @Test
    void readCommittedSeesNewCommitsRepeatableReadDoesNot() {
        try (Session rc = new Session(db); Session rr = new Session(db); Session w = new Session(db)) {
            rc.run("BEGIN");
            rr.run("BEGIN ISOLATION LEVEL REPEATABLE READ");
            assertEquals(0, value(rc, "SELECT n FROM counter WHERE id = 1"));
            assertEquals(0, value(rr, "SELECT n FROM counter WHERE id = 1"));
            w.run("UPDATE counter SET n = 9 WHERE id = 1");
            w.run("INSERT INTO counter VALUES (3, 3)");
            assertEquals(9, value(rc, "SELECT n FROM counter WHERE id = 1"), "READ COMMITTED: each statement sees the latest commits");
            assertEquals(3, value(rc, "SELECT count(*) FROM counter"));
            assertEquals(0, value(rr, "SELECT n FROM counter WHERE id = 1"), "REPEATABLE READ: the snapshot is fixed");
            assertEquals(2, value(rr, "SELECT count(*) FROM counter"), "and no phantom rows appear");
            rc.run("COMMIT");
            rr.run("COMMIT");
        }
    }

    /** Concurrent read-modify-write in READ COMMITTED: each UPDATE waits for the row and re-reads it, so no increment is lost. */
    @Test
    void noLostUpdates() throws Exception {
        int threads = 8;
        int each = 150;
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            runs.add(CompletableFuture.runAsync(() -> {
                try (Session s = new Session(db)) {
                    for (int i = 0; i < each; i++) s.run("UPDATE counter SET n = n + 1 WHERE id = 1");
                }
            }));
        }
        for (CompletableFuture<Void> r : runs) r.get(60, TimeUnit.SECONDS);
        try (Session s = new Session(db)) {
            assertEquals(threads * each, value(s, "SELECT n FROM counter WHERE id = 1"));
        }
    }

    @Test
    void repeatableReadRefusesToOverwriteAConcurrentUpdate() {
        try (Session a = new Session(db); Session b = new Session(db)) {
            a.run("BEGIN ISOLATION LEVEL REPEATABLE READ");
            assertEquals(0, value(a, "SELECT n FROM counter WHERE id = 1"));
            b.run("UPDATE counter SET n = n + 10 WHERE id = 1");
            SqlException e = assertThrows(SqlException.class, () -> a.run("UPDATE counter SET n = n + 1 WHERE id = 1"));
            assertEquals(SqlException.SERIALIZATION, e.sqlState());
            a.run("ROLLBACK");
            assertEquals(10, value(b, "SELECT n FROM counter WHERE id = 1"));
        }
    }

    /** Snapshot isolation allows write skew; PostgreSQL's REPEATABLE READ does too (only SERIALIZABLE prevents it). */
    @Test
    void repeatableReadAllowsWriteSkewLikePostgres() {
        try (Session a = new Session(db); Session b = new Session(db)) {
            a.run("BEGIN ISOLATION LEVEL REPEATABLE READ");
            b.run("BEGIN ISOLATION LEVEL REPEATABLE READ");
            assertEquals(0, value(a, "SELECT sum(n) FROM counter"));
            assertEquals(0, value(b, "SELECT sum(n) FROM counter"));
            a.run("UPDATE counter SET n = 1 WHERE id = 1"); // each writes a different row, based on the same read
            b.run("UPDATE counter SET n = 1 WHERE id = 2");
            a.run("COMMIT");
            b.run("COMMIT");
            try (Session c = new Session(db)) {
                assertEquals(2, value(c, "SELECT sum(n) FROM counter"));
            }
        }
    }

    @Test
    void deadlocksAreDetected() throws Exception {
        try (Session a = new Session(db); Session b = new Session(db)) {
            a.run("BEGIN");
            b.run("BEGIN");
            a.run("UPDATE counter SET n = 1 WHERE id = 1");
            b.run("UPDATE counter SET n = 2 WHERE id = 2");
            AtomicInteger deadlocks = new AtomicInteger();
            CompletableFuture<Void> aWaits = CompletableFuture.runAsync(() -> {
                try {
                    a.run("UPDATE counter SET n = 1 WHERE id = 2");
                } catch (SqlException e) {
                    if (e.sqlState().equals(SqlException.DEADLOCK)) deadlocks.incrementAndGet();
                    a.run("ROLLBACK");
                }
            });
            Thread.sleep(200);
            try {
                b.run("UPDATE counter SET n = 2 WHERE id = 1");
            } catch (SqlException e) {
                if (e.sqlState().equals(SqlException.DEADLOCK)) deadlocks.incrementAndGet();
                b.run("ROLLBACK");
            }
            aWaits.get(20, TimeUnit.SECONDS);
            assertTrue(deadlocks.get() >= 1, "one of them must be told it's in a deadlock");
            if (b.status() == 'T') b.run("COMMIT");
            if (a.status() == 'T') a.run("COMMIT");
        }
    }

    /** Two transactions insert the same key: the second waits for the first, then fails only if the first committed. */
    @Test
    void uniqueKeysWaitForTheOtherInserter() throws Exception {
        for (boolean firstCommits : new boolean[] {true, false}) {
            int key = firstCommits ? 10 : 11;
            try (Session a = new Session(db); Session b = new Session(db)) {
                a.run("BEGIN");
                a.run("INSERT INTO counter VALUES (" + key + ", 1)");
                CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
                    try {
                        b.run("INSERT INTO counter VALUES (" + key + ", 2)");
                        return "inserted";
                    } catch (SqlException e) {
                        return e.sqlState();
                    }
                });
                Thread.sleep(200);
                assertFalse(second.isDone(), "the second insert waits for the first transaction");
                a.run(firstCommits ? "COMMIT" : "ROLLBACK");
                assertEquals(firstCommits ? SqlException.UNIQUE : "inserted", second.get(10, TimeUnit.SECONDS));
                assertEquals(firstCommits ? 1 : 2, value(b, "SELECT n FROM counter WHERE id = " + key));
            }
        }
    }

    /** VACUUM keeps the old versions an open snapshot can still see. */
    @Test
    void vacuumRespectsOpenSnapshots() {
        try (Session reader = new Session(db); Session writer = new Session(db)) {
            reader.run("BEGIN ISOLATION LEVEL REPEATABLE READ");
            assertEquals(0, value(reader, "SELECT n FROM counter WHERE id = 1"));
            for (int i = 0; i < 20; i++) writer.run("UPDATE counter SET n = n + 1 WHERE id = 1");
            writer.run("VACUUM counter");
            assertEquals(0, value(reader, "SELECT n FROM counter WHERE id = 1"), "the reader's version survived VACUUM");
            reader.run("COMMIT");
            assertEquals(20, value(reader, "SELECT n FROM counter WHERE id = 1"));
            writer.run("VACUUM counter");
            assertEquals(20, value(writer, "SELECT n FROM counter WHERE id = 1"));
            CrashTest.indexesMatchTables(db);
        }
    }
}
