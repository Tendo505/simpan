package io.github.tendo505.simpan.tx;

import io.github.tendo505.simpan.sql.Ast.Isolation;
import java.util.concurrent.CountDownLatch;

/** One transaction. Others that need to wait for it to finish wait on {@link #done}. */
public final class Transaction {
    private final long xid;
    private final Isolation isolation;
    final CountDownLatch done = new CountDownLatch(1);
    private Snapshot snapshot;
    private boolean wrote;
    private volatile long lockTimeoutMillis = Long.MAX_VALUE;

    Transaction(long xid, Isolation isolation) {
        this.xid = xid;
        this.isolation = isolation;
    }

    public long xid() {
        return xid;
    }

    public Isolation isolation() {
        return isolation;
    }

    /** The snapshot the current statement reads with. */
    public Snapshot snapshot() {
        return snapshot;
    }

    void setSnapshot(Snapshot s) {
        this.snapshot = s;
    }

    /** True once it has changed data, so committing it must reach the log. */
    public boolean wrote() {
        return wrote;
    }

    public void markWrote() {
        wrote = true;
    }

    /** How long this transaction waits for another's row lock before giving up (its session's lock_timeout). */
    public long lockTimeoutMillis() {
        return lockTimeoutMillis;
    }

    public void setLockTimeoutMillis(long ms) {
        this.lockTimeoutMillis = ms;
    }
}
