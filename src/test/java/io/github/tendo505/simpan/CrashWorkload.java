package io.github.tendo505.simpan;

import io.github.tendo505.simpan.engine.Database;
import io.github.tendo505.simpan.engine.Session;
import java.nio.file.Path;
import java.util.Random;

/**
 * Run in a child process by {@link CrashTest}, which kills it at a random
 * moment. Moves money between accounts in transactions, records each one in
 * a ledger, churns a scratch table (so VACUUM and checkpoints happen too),
 * and prints "ok N" only after transaction N's COMMIT has returned: those
 * are the ones that must survive.
 */
public final class CrashWorkload {
    private CrashWorkload() {}

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        long next = Long.parseLong(args[1]);
        Random rnd = new Random(next);
        Database db = Database.open(dir, new Database.Options(64, true, 1L << 20)); // tiny pool and frequent checkpoints
        Session s = new Session(db);
        System.out.println("started");
        System.out.flush();
        while (true) {
            long id = next++;
            int from = rnd.nextInt(50);
            int to = (from + 1 + rnd.nextInt(49)) % 50;
            long amount = 1 + rnd.nextInt(100);
            s.run("BEGIN");
            s.run("UPDATE accounts SET balance = balance - $1 WHERE id = $2", String.valueOf(amount), String.valueOf(from));
            s.run("UPDATE accounts SET balance = balance + $1 WHERE id = $2", String.valueOf(amount), String.valueOf(to));
            s.run("INSERT INTO ledger VALUES ($1, $2, $3, $4, $5)", String.valueOf(id), String.valueOf(from), String.valueOf(to),
                    String.valueOf(amount), "transfer number " + id + " " + "x".repeat(rnd.nextInt(200)));
            if (rnd.nextInt(10) == 0) {
                s.run("ROLLBACK"); // never acknowledged, so it must not survive
                continue;
            }
            s.run("COMMIT");
            System.out.println("ok " + id);
            System.out.flush();
            s.run("INSERT INTO scratch VALUES ($1, $2)", String.valueOf(id), "y".repeat(rnd.nextInt(500)));
            if (id % 7 == 0) s.run("DELETE FROM scratch WHERE id < $1", String.valueOf(id - 20));
            if (id % 97 == 0) s.run("VACUUM scratch");
            if (id % 151 == 0) s.run("CHECKPOINT");
        }
    }
}
