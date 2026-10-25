package io.github.tendo505.simpan.tx;

import java.util.Arrays;

/**
 * Which transactions' work a statement can see: everything committed before
 * it was taken. {@code xmin} is the oldest transaction still running then,
 * {@code xmax} the first one that hadn't started, and {@code active} the ones
 * in between that were still running. {@code me} is the reader's own
 * transaction, whose changes it always sees.
 */
public record Snapshot(long xmin, long xmax, long[] active, long me) {

    /** True if the work of {@code xid}, once committed, is visible to this snapshot. */
    public boolean sees(long xid) {
        if (xid >= xmax) return false;
        if (xid < xmin) return true;
        return Arrays.binarySearch(active, xid) < 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Snapshot s && s.xmin == xmin && s.xmax == xmax && s.me == me && Arrays.equals(s.active, active);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(xmin) * 31 + Long.hashCode(xmax) + Arrays.hashCode(active);
    }

    @Override
    public String toString() {
        return "Snapshot[xmin=" + xmin + ", xmax=" + xmax + ", active=" + Arrays.toString(active) + ", me=" + me + "]";
    }
}
