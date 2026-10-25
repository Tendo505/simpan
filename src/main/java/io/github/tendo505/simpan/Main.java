package io.github.tendo505.simpan;

import io.github.tendo505.simpan.engine.Database;
import io.github.tendo505.simpan.server.PgServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** {@code simpan shell | serve | bench | run}: see {@link #usage()}. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        String cmd = args.length == 0 ? "shell" : args[0];
        switch (cmd) {
            case "shell" -> {
                try (Database db = Database.open(dataDir(args, 1))) {
                    new Shell(db, System.out).run();
                }
            }
            case "serve" -> serve(args);
            case "bench" -> {
                int rows = args.length > 1 ? Integer.parseInt(args[1]) : 200_000;
                Path dir = args.length > 2 ? Path.of(args[2]) : Files.createTempDirectory("simpan-bench");
                Bench.run(dir, rows, System.out);
            }
            case "run" -> {
                if (args.length < 2) {
                    usage();
                    return;
                }
                try (Database db = Database.open(dataDir(args, 2))) {
                    Shell shell = new Shell(db, System.out);
                    boolean file = args[1].toLowerCase(java.util.Locale.ROOT).endsWith(".sql") && Files.exists(Path.of(args[1]));
                    // Like psql: -f runs a file's statements one by one, -c runs a string as one transaction.
                    if (file) shell.runSql(Files.readString(Path.of(args[1])), false);
                    else shell.runSql(args[1], true);
                }
            }
            default -> usage();
        }
    }

    private static Path dataDir(String[] args, int at) {
        return Path.of(args.length > at ? args[at] : "simpan-data");
    }

    private static void serve(String[] args) throws Exception {
        int port = 5432;
        String host = null;
        Path dir = Path.of("simpan-data");
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                default -> dir = Path.of(args[i]);
            }
        }
        Database db = Database.open(dir);
        PgServer server = new PgServer(db, host, port);
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (Exception e) {
                // shutting down anyway
            }
            db.close();
            stop.countDown();
        }));
        Database.RecoveryReport r = db.recoveryReport();
        if (r.recordsReplayed() > 0) {
            System.out.printf("Recovered: replayed %d WAL records, rebuilt %d indexes, %d unfinished transactions rolled back.%n",
                    r.recordsReplayed(), r.indexesRebuilt(), r.abortedTransactions());
        }
        System.out.println("Simpan is listening on " + (host == null ? "localhost" : host) + ":" + server.port() + " (data in " + dir.toAbsolutePath() + ")");
        System.out.println("Connect with:  psql -h localhost -p " + server.port() + " -U simpan simpan");
        stop.await();
    }

    private static void usage() {
        System.out.println("""
                usage:
                  simpan shell [DIR]                      interactive SQL (data in DIR, default ./simpan-data)
                  simpan serve [--port 5432] [--host H] [DIR]
                                                          a PostgreSQL-compatible server: connect with psql or JDBC
                  simpan run SQL_OR_FILE [DIR]            run statements and print the results
                  simpan bench [ROWS] [DIR]               benchmark (default 200000 rows, in a temporary directory)""");
    }
}
