package io.github.tendo505.simpan.tx;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.sql.Ast.Isolation;
import io.github.tendo505.simpan.wal.Wal;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Hands out transaction ids, keeps the commit log (each transaction's
 * outcome), takes snapshots, and decides which row versions a snapshot sees.
 *
 * <p>Rows carry the id of the transaction that created them ({@code xmin})
 * and of the one that deleted or replaced them ({@code xmax}). A version is
 * visible if its creator committed before the snapshot (or is the reader)
 * and its deleter didn't. Aborted work needs no undo: its versions simply
 * never become visible.
 */
public final class TxManager {
    public static final byte IN_PROGRESS = 0;
    public static final byte COMMITTED = 1;
    public static final byte ABORTED = 2;

    private final Wal wal;
    private byte[] clog = new byte[1024];
    private long nextXid;
    private final TreeMap<Long, Transaction> active = new TreeMap<>();
    private final Map<Long, Long> waitsFor = new ConcurrentHashMap<>();
    private long commits;

    public TxManager(Wal wal, long nextXid) {
        this.wal = wal;
        this.nextXid = Math.max(nextXid, 1);
    }

    public synchronized Transaction begin(Isolation isolation) {
        long xid = nextXid++;
        ensure(xid);
        Transaction t = new Transaction(xid, isolation);
        active.put(xid, t);
        return t;
    }

    /** A new snapshot for the transaction's next statement (READ COMMITTED), or its first (REPEATABLE READ). */
    public synchronized Snapshot snapshotFor(Transaction t) {
        if (t.isolation() == Isolation.REPEATABLE_READ && t.snapshot() != null) return t.snapshot();
        long[] ids = new long[active.size()];
        int i = 0;
        for (long id : active.keySet()) ids[i++] = id;
        long xmin = ids.length == 0 ? nextXid : ids[0];
        Snapshot s = new Snapshot(xmin, nextXid, ids, t.xid());
        t.setSnapshot(s);
        return s;
    }

    /** The oldest transaction any running snapshot might still need to see as running. */
    public synchronized long oldestActive() {
        long oldest = nextXid;
        for (Transaction t : active.values()) {
            oldest = Math.min(oldest, t.xid());
            if (t.snapshot() != null) oldest = Math.min(oldest, t.snapshot().xmin());
        }
        return oldest;
    }

    /** Makes the commit durable (if it wrote anything), then visible to new snapshots. */
    public void commit(Transaction t) {
        if (t.wrote()) {
            long lsn = wal.append(Wal.COMMIT, xidBytes(t.xid()));
            wal.flush(lsn);
        }
        finish(t, COMMITTED);
    }

    /** Commits without waiting for the fsync: a crash can lose it, but never half of it. */
    public void commitAsync(Transaction t) {
        if (t.wrote()) wal.append(Wal.COMMIT, xidBytes(t.xid()));
        finish(t, COMMITTED);
    }

    public void abort(Transaction t) {
        if (t.wrote()) wal.append(Wal.ABORT, xidBytes(t.xid())); // no flush: losing it in a crash means "aborted" anyway
        finish(t, ABORTED);
    }

    private void finish(Transaction t, byte outcome) {
        synchronized (this) {
            clog[(int) t.xid()] = outcome;
            active.remove(t.xid());
            if (outcome == COMMITTED) commits++;
        }
        t.done.countDown();
    }

    public static byte[] xidBytes(long xid) {
        return ByteBuffer.allocate(8).putLong(xid).array();
    }

    public synchronized byte status(long xid) {
        if (xid <= 0 || xid >= clog.length) return xid > 0 && xid < nextXid ? IN_PROGRESS : ABORTED;
        return clog[(int) xid];
    }

    private void ensure(long xid) {
        if (xid > Integer.MAX_VALUE - 16) throw new SqlException(SqlException.PROGRAM_LIMIT, "transaction ids exhausted");
        if (xid >= clog.length) clog = Arrays.copyOf(clog, (int) Math.max(xid + 1, (long) clog.length * 2));
    }

    // ---------------- visibility ----------------

    /** Is the version created by {@code xmin} and deleted by {@code xmax} (0 = not deleted) visible to {@code s}? */
    public boolean visible(long xmin, long xmax, Snapshot s) {
        if (xmin != s.me() && (status(xmin) != COMMITTED || !s.sees(xmin))) return false;
        if (xmax == 0) return true;
        if (xmax == s.me()) return false;
        return status(xmax) != COMMITTED || !s.sees(xmax);
    }

    /** Could any snapshot, now or later, still see this version? False means VACUUM may remove it. */
    public boolean mightBeVisible(long xmin, long xmax, long oldestActive) {
        byte created = status(xmin);
        if (created == ABORTED) return false;
        if (xmax == 0) return true;
        return status(xmax) != COMMITTED || xmax >= oldestActive;
    }

    // ---------------- waiting for other transactions ----------------

    /**
     * Blocks until {@code xid} commits or aborts, because {@code me} wants a
     * row it has locked. Fails at once if that would close a cycle of
     * transactions waiting on each other.
     */
    public void waitFor(Transaction me, long xid) {
        Transaction other;
        synchronized (this) {
            other = active.get(xid);
        }
        if (other == null) return;
        waitsFor.put(me.xid(), xid);
        try {
            for (Long cur = xid; cur != null; cur = waitsFor.get(cur)) {
                if (cur == me.xid()) {
                    throw new SqlException(SqlException.DEADLOCK, "deadlock detected: transaction " + me.xid()
                            + " and transaction " + xid + " are waiting for each other");
                }
            }
            if (!other.done.await(me.lockTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                throw new SqlException(SqlException.LOCK_TIMEOUT, "canceling statement due to lock timeout (waiting for transaction " + xid + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SqlException(SqlException.INTERNAL, "interrupted while waiting for a lock");
        } finally {
            waitsFor.remove(me.xid());
        }
    }

    public synchronized long commits() {
        return commits;
    }

    public synchronized int activeCount() {
        return active.size();
    }

    // ---------------- persistence and recovery ----------------

    public synchronized long nextXid() {
        return nextXid;
    }

    /** Recovery: an id seen in the log was used, so never hand it out again. */
    public synchronized void sawXid(long xid) {
        if (xid >= nextXid) {
            nextXid = xid + 1;
            ensure(xid);
        }
    }

    /** Recovery: the outcome written in the log. */
    public synchronized void setStatus(long xid, byte status) {
        sawXid(xid);
        clog[(int) xid] = status;
    }

    /** Recovery is over: whatever never committed or aborted was cut off by the crash, so it aborted. Returns how many. */
    public synchronized long abortUnfinished() {
        long n = 0;
        for (long x = 1; x < nextXid; x++) {
            if (clog[(int) x] == IN_PROGRESS) {
                clog[(int) x] = ABORTED;
                n++;
            }
        }
        return n;
    }

    /** Written at each checkpoint. Running transactions are saved as in progress. */
    public synchronized void save(OutputStream out) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        d.writeLong(nextXid);
        d.write(clog, 0, (int) nextXid);
        d.flush();
    }

    public synchronized void load(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        nextXid = d.readLong();
        clog = new byte[(int) Math.max(nextXid + 1024, 1024)];
        d.readFully(clog, 0, (int) nextXid);
    }
}
