package io.github.tendo505.simpan.server;

import io.github.tendo505.simpan.engine.Database;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Accepts PostgreSQL clients ({@code psql}, JDBC, ...) and gives each its own thread and session. */
public final class PgServer implements Closeable {
    private final Database db;
    private final ServerSocket socket;
    private final ExecutorService clients = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "simpan-client");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger pids = new AtomicInteger(1000);
    private final Thread acceptor;

    /** Listens on {@code port} (0 picks a free one); {@code host} null means localhost only. */
    public PgServer(Database db, String host, int port) throws IOException {
        this.db = db;
        this.socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(host == null ? InetAddress.getLoopbackAddress() : InetAddress.getByName(host), port));
        this.acceptor = new Thread(this::accept, "simpan-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void accept() {
        while (!socket.isClosed()) {
            try {
                Socket s = socket.accept();
                clients.execute(new PgConnection(s, db, pids.incrementAndGet()));
            } catch (IOException e) {
                if (socket.isClosed()) return;
            }
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
        clients.shutdownNow();
    }
}
