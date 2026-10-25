package io.github.tendo505.simpan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tendo505.simpan.engine.Database;
import io.github.tendo505.simpan.engine.Session;
import io.github.tendo505.simpan.engine.Table;
import io.github.tendo505.simpan.tx.Snapshot;
import io.github.tendo505.simpan.tx.Transaction;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Durability and atomicity across real crashes: a child process runs
 * transactions and is killed (no shutdown, no flush) at a random moment,
 * again and again. After each restart, every transaction it acknowledged
 * must be there, none of the half-done ones, money must add up, and every
 * index must agree with its table.
 */
class CrashTest {
    @TempDir
    Path dir;

    private static final long TOTAL = 50 * 1000;

    @Test
    void acknowledgedCommitsSurviveKill9() throws Exception {
        try (Database db = Database.open(dir); Session s = new Session(db)) {
            s.run("CREATE TABLE accounts (id int PRIMARY KEY, balance bigint NOT NULL)");
            s.run("CREATE TABLE ledger (id bigint PRIMARY KEY, from_id int, to_id int, amount bigint, note text)");
            s.run("CREATE INDEX ledger_from ON ledger (from_id)");
            s.run("CREATE TABLE scratch (id bigint PRIMARY KEY, junk text)");
            StringBuilder sb = new StringBuilder("INSERT INTO accounts VALUES ");
            for (int i = 0; i < 50; i++) sb.append(i == 0 ? "" : ",").append('(').append(i).append(",1000)");
            s.run(sb.toString());
        }
        Random rnd = new Random(11);
        Set<Long> acknowledged = ConcurrentHashMap.newKeySet();
        long next = 1;
        int rounds = 8;
        for (int round = 0; round < rounds; round++) {
            Process child = new ProcessBuilder(javaBin(), "-cp", System.getProperty("java.class.path"),
                    CrashWorkload.class.getName(), dir.toString(), String.valueOf(next))
                    .redirectErrorStream(true).start();
            BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
            List<String> other = new ArrayList<>();
            Thread reader = new Thread(() -> {
                try {
                    for (String line = out.readLine(); line != null; line = out.readLine()) {
                        if (line.startsWith("ok ")) acknowledged.add(Long.parseLong(line.substring(3)));
                        else if (!line.equals("started")) other.add(line);
                    }
                } catch (Exception e) {
                    // the process was killed
                }
            });
            reader.start();
            Thread.sleep(700 + rnd.nextInt(1500));
            child.destroyForcibly(); // like kill -9: no shutdown hooks, no flushing
            child.waitFor();
            reader.join();
            assertTrue(other.isEmpty() || other.stream().noneMatch(l -> l.contains("Exception")), "workload failed: " + other);

            try (Database db = Database.open(dir); Session s = new Session(db)) {
                Set<Long> present = new HashSet<>();
                for (Object[] row : s.run("SELECT id FROM ledger").rows()) present.add((Long) row[0]);
                for (long id : acknowledged) assertTrue(present.contains(id), "round " + round + ": acknowledged transaction " + id + " is missing");
                assertEquals(TOTAL, s.run("SELECT sum(balance) FROM accounts").rows().get(0)[0], "money appeared or vanished");
                // Every committed transfer is reflected in the balances exactly once: replay the ledger and compare.
                long[] expected = new long[50];
                java.util.Arrays.fill(expected, 1000);
                for (Object[] row : s.run("SELECT from_id, to_id, amount FROM ledger").rows()) {
                    expected[(int) (long) (Long) row[0]] -= (Long) row[2];
                    expected[(int) (long) (Long) row[1]] += (Long) row[2];
                }
                for (Object[] row : s.run("SELECT id, balance FROM accounts").rows()) {
                    assertEquals(expected[(int) (long) (Long) row[0]], row[1], "account " + row[0] + " doesn't match the ledger");
                }
                indexesMatchTables(db);
                next = present.stream().mapToLong(Long::longValue).max().orElse(0) + 1000;
                System.out.printf("crash round %d: %d acknowledged so far, recovery replayed %d WAL records and rebuilt %d indexes%n",
                        round, acknowledged.size(), db.recoveryReport().recordsReplayed(), db.recoveryReport().indexesRebuilt());
            }
        }
        assertTrue(acknowledged.size() > 100, "the workload barely ran: " + acknowledged.size());
    }

    /** Each index finds exactly the rows a full scan finds. */
    static void indexesMatchTables(Database db) {
        Transaction t = db.begin(io.github.tendo505.simpan.sql.Ast.Isolation.REPEATABLE_READ);
        try {
            Snapshot snap = db.snapshot(t);
            for (var def : db.catalog().tables()) {
                Table table = db.table(def.name());
                Set<String> scanned = new HashSet<>();
                for (Iterator<Table.Row> it = table.scan(snap); it.hasNext(); ) scanned.add(it.next().rid().toString());
                for (Table.Index index : table.indexes()) {
                    Set<String> viaIndex = new HashSet<>();
                    for (Iterator<Table.Row> it = table.indexScan(index, null, null, snap); it.hasNext(); ) {
                        assertTrue(viaIndex.add(it.next().rid().toString()), "index " + index.def().name() + " returns a row twice");
                    }
                    assertEquals(scanned, viaIndex, "index " + index.def().name() + " disagrees with table " + def.name());
                }
            }
        } finally {
            db.abort(t);
        }
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
