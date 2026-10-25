package io.github.tendo505.simpan;

import io.github.tendo505.simpan.engine.Database;
import io.github.tendo505.simpan.engine.Session;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A benchmark that also checks itself: bulk load, point and index lookups,
 * an aggregate, a join, then concurrent money transfers with real
 * contention, after which the total balance must not have changed by a sen.
 */
final class Bench {
    private Bench() {}

    static void run(Path dir, int rows, PrintStream out) throws Exception {
        Database db = Database.open(dir, Database.Options.defaults());
        Session s = new Session(db);
        Random rnd = new Random(1);
        out.println("| Workload | Time | Rate |");
        out.println("| --- | ---: | ---: |");

        s.execute("DROP TABLE IF EXISTS transfers; DROP TABLE IF EXISTS accounts;");
        s.execute("CREATE TABLE accounts (id int PRIMARY KEY, branch int NOT NULL, balance bigint NOT NULL, owner text)");
        s.execute("CREATE TABLE transfers (id int PRIMARY KEY, from_id int, to_id int, amount bigint)");
        long t0 = System.nanoTime();
        int batch = 500;
        for (int start = 0; start < rows; start += batch) {
            StringBuilder sql = new StringBuilder("INSERT INTO accounts VALUES ");
            for (int i = start; i < Math.min(rows, start + batch); i++) {
                sql.append(i == start ? "" : ",").append('(').append(i).append(',').append(i % 100).append(",10000,'owner ").append(i).append("')");
            }
            s.run(sql.toString());
        }
        row(out, String.format(Locale.ROOT, "Load %,d rows (%d-row INSERTs, each committed and fsynced)", rows, batch), t0, rows, "rows/s");

        t0 = System.nanoTime();
        s.run("CREATE INDEX accounts_branch ON accounts (branch)");
        s.run("ANALYZE accounts");
        row(out, "Build a secondary index, then ANALYZE", t0, rows, "rows/s");

        int lookups = 50_000;
        long[] latency = new long[lookups];
        t0 = System.nanoTime();
        for (int i = 0; i < lookups; i++) {
            long a = System.nanoTime();
            s.run("SELECT balance FROM accounts WHERE id = $1", String.valueOf(rnd.nextInt(rows)));
            latency[i] = System.nanoTime() - a;
        }
        Arrays.sort(latency);
        row(out, String.format(Locale.ROOT, "Point lookups by primary key (p50 %.1f us, p99 %.1f us; parse + plan + run each)",
                latency[lookups / 2] / 1e3, latency[lookups * 99 / 100] / 1e3), t0, lookups, "queries/s");

        t0 = System.nanoTime();
        for (int i = 0; i < 200; i++) s.run("SELECT count(*), sum(balance) FROM accounts WHERE branch = $1", String.valueOf(i % 100));
        row(out, String.format(Locale.ROOT, "Index range scans (%,d rows each)", rows / 100), t0, 200, "queries/s");

        t0 = System.nanoTime();
        s.run("SELECT branch, count(*), sum(balance) FROM accounts GROUP BY branch ORDER BY branch");
        row(out, String.format(Locale.ROOT, "Full scan + GROUP BY over %,d rows", rows), t0, rows, "rows/s");

        // Concurrent transfers between a hot set of accounts: lock waits, deadlocks (detected and retried), group commit.
        int threads = 8;
        int perThread = 500;
        int hot = 40;
        AtomicLong ids = new AtomicLong();
        AtomicLong retries = new AtomicLong();
        long syncsBefore = db.wal().syncs();
        long commitsBefore = db.txManager().commits();
        t0 = System.nanoTime();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < threads; w++) {
            long seed = w;
            Thread t = new Thread(() -> {
                Random r = new Random(seed);
                try (Session ws = new Session(db)) {
                    for (int k = 0; k < perThread; k++) {
                        int from = r.nextInt(hot);
                        int to = (from + 1 + r.nextInt(hot - 1)) % hot;
                        long amount = 1 + r.nextInt(50);
                        while (true) {
                            try {
                                ws.run("BEGIN");
                                ws.run("UPDATE accounts SET balance = balance - $1 WHERE id = $2", String.valueOf(amount), String.valueOf(from));
                                ws.run("UPDATE accounts SET balance = balance + $1 WHERE id = $2", String.valueOf(amount), String.valueOf(to));
                                ws.run("INSERT INTO transfers VALUES ($1, $2, $3, $4)", String.valueOf(ids.incrementAndGet()),
                                        String.valueOf(from), String.valueOf(to), String.valueOf(amount));
                                ws.run("COMMIT");
                                break;
                            } catch (SqlException e) {
                                ws.run("ROLLBACK");
                                if (!e.sqlState().equals(SqlException.DEADLOCK) && !e.sqlState().equals(SqlException.SERIALIZATION)) throw e;
                                retries.incrementAndGet();
                            }
                        }
                    }
                }
            });
            workers.add(t);
            t.start();
        }
        for (Thread t : workers) t.join();
        int transfers = threads * perThread;
        long commits = db.txManager().commits() - commitsBefore;
        long syncs = db.wal().syncs() - syncsBefore;
        row(out, String.format(Locale.ROOT, "Concurrent transfers: %d threads, %d hot accounts (%d deadlocks retried; %d commits in %d fsyncs)",
                threads, hot, retries.get(), commits, syncs), t0, transfers, "txn/s");

        t0 = System.nanoTime();
        Object total = s.run("SELECT sum(balance) FROM accounts").rows().get(0)[0];
        Object count = s.run("SELECT count(*) FROM transfers").rows().get(0)[0];
        s.run("SELECT a.branch, count(*), sum(t.amount) FROM transfers t JOIN accounts a ON a.id = t.from_id GROUP BY a.branch");
        row(out, "Hash join + GROUP BY of transfers with accounts", t0, transfers, "rows/s");
        long expected = 10_000L * rows;
        out.println();
        out.printf(Locale.ROOT, "Money conserved: total balance %s (expected %d) %s; %s transfers recorded (expected %d).%n",
                total, expected, ((Long) total) == expected ? "(ok)" : "MISMATCH", count, transfers);
        s.close();
        db.close();
        if ((Long) total != expected || (Long) count != transfers) throw new IllegalStateException("benchmark invariant broken");
    }

    private static void row(PrintStream out, String what, long start, long ops, String unit) {
        double secs = (System.nanoTime() - start) / 1e9;
        String time = secs < 1 ? String.format(Locale.ROOT, "%.0f ms", secs * 1000) : String.format(Locale.ROOT, "%.2f s", secs);
        out.printf(Locale.ROOT, "| %s | %s | %,.0f %s |%n", what, time, ops / secs, unit);
    }
}
