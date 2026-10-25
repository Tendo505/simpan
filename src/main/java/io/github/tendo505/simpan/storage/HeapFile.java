package io.github.tendo505.simpan.storage;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.storage.BufferPool.Frame;
import io.github.tendo505.simpan.wal.Wal;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A table's rows, in slotted pages. After the page header comes a slot
 * count, the start of the tuple area, then one {@code [offset][length]}
 * slot per tuple; tuples fill the page from the end backwards.
 *
 * <p>Each tuple is one version of a row: a 24-byte header ({@code xmin},
 * {@code xmax}, and where its replacement version is) and the row bytes.
 * Versions are never changed in place except to set {@code xmax}.
 *
 * <p>Every change is logged before the page can reach disk. The first change
 * to a page after a checkpoint logs a full image of the page, so a page torn
 * by a crash mid-write is restored whole; later changes log just the change.
 */
public final class HeapFile {
    public static final int TUPLE_HEADER = 24;
    private static final int SLOT_COUNT = Page.HEADER;
    private static final int FREE_END = Page.HEADER + 2;
    private static final int SLOTS = Page.HEADER + 4;
    public static final int MAX_TUPLE = Page.SIZE - SLOTS - 4;

    /** A row version as stored. {@code next} is the newer version that replaced it, or null. */
    public record Tuple(Rid rid, long xmin, long xmax, Rid next, byte[] data) {}

    private final int fileId;
    private final BufferPool pool;
    private final Wal wal;
    private final LongSupplier redoPoint;
    private final Object insertLock = new Object();
    private final ArrayDeque<Integer> pagesWithSpace = new ArrayDeque<>();
    private int lastPage = -1;

    /** {@code redoPoint} is where recovery would start: pages last changed before it get a full image. */
    public HeapFile(int fileId, BufferPool pool, Wal wal, LongSupplier redoPoint) {
        this.fileId = fileId;
        this.pool = pool;
        this.wal = wal;
        this.redoPoint = redoPoint;
    }

    public int fileId() {
        return fileId;
    }

    public int pageCount() {
        return pool.pageCount(fileId);
    }

    // ---------------- page layout ----------------

    private static void init(byte[] p) {
        Page.setType(p, Page.HEAP);
        Page.putU16(p, SLOT_COUNT, 0);
        Page.putU16(p, FREE_END, Page.SIZE);
    }

    private static int slotCount(byte[] p) {
        return Page.getU16(p, SLOT_COUNT);
    }

    private static int slotOffset(byte[] p, int slot) {
        return Page.getU16(p, SLOTS + slot * 4);
    }

    private static int slotLength(byte[] p, int slot) {
        return Page.getU16(p, SLOTS + slot * 4 + 2);
    }

    private static int freeSpace(byte[] p) {
        return Page.getU16(p, FREE_END) - (SLOTS + slotCount(p) * 4);
    }

    /** The slot an insert of this size would use, or -1 if it doesn't fit. */
    private static int chooseSlot(byte[] p, int length) {
        int n = slotCount(p);
        for (int s = 0; s < n; s++) if (slotLength(p, s) == 0) return length <= freeSpace(p) ? s : -1;
        return length + 4 <= freeSpace(p) ? n : -1;
    }

    private static void putAt(byte[] p, int slot, byte[] tuple) {
        int end = Page.getU16(p, FREE_END) - tuple.length;
        System.arraycopy(tuple, 0, p, end, tuple.length);
        Page.putU16(p, FREE_END, end);
        if (slot == slotCount(p)) Page.putU16(p, SLOT_COUNT, slot + 1);
        Page.putU16(p, SLOTS + slot * 4, end);
        Page.putU16(p, SLOTS + slot * 4 + 2, tuple.length);
    }

    private static Tuple tupleAt(byte[] p, int pageNo, int slot) {
        int off = slotOffset(p, slot);
        int len = slotLength(p, slot);
        if (len == 0) return null;
        long xmin = Page.getLong(p, off);
        long xmax = Page.getLong(p, off + 8);
        int nextPage = Page.getInt(p, off + 16);
        Rid next = nextPage < 0 ? null : new Rid(nextPage, Page.getU16(p, off + 20));
        byte[] data = new byte[len - TUPLE_HEADER];
        System.arraycopy(p, off + TUPLE_HEADER, data, 0, data.length);
        return new Tuple(new Rid(pageNo, slot), xmin, xmax, next, data);
    }

    private static void writeXmax(byte[] p, int slot, long xmax, Rid next) {
        int off = slotOffset(p, slot);
        Page.putLong(p, off + 8, xmax);
        Page.putInt(p, off + 16, next == null ? -1 : next.page());
        Page.putU16(p, off + 20, next == null ? 0 : next.slot());
    }

    // ---------------- changes ----------------

    /** Stores a new version created by {@code xmin} and returns where it went. */
    public Rid insert(long xmin, byte[] row) {
        byte[] tuple = new byte[TUPLE_HEADER + row.length];
        if (tuple.length > MAX_TUPLE) {
            throw new SqlException(SqlException.PROGRAM_LIMIT, "row is too big: size " + tuple.length + ", maximum size " + MAX_TUPLE);
        }
        Page.putLong(tuple, 0, xmin);
        Page.putInt(tuple, 16, -1);
        System.arraycopy(row, 0, tuple, TUPLE_HEADER, row.length);
        synchronized (insertLock) {
            if (lastPage < 0 && pageCount() > 0) lastPage = pageCount() - 1;
            List<Integer> candidates = new ArrayList<>(pagesWithSpace);
            if (lastPage >= 0) candidates.add(lastPage);
            for (int pageNo : candidates) {
                Rid rid = tryInsert(pageNo, tuple);
                if (rid != null) return rid;
                pagesWithSpace.remove(pageNo);
            }
            Frame f = pool.allocate(fileId);
            try {
                synchronized (f) {
                    init(f.data());
                    putAt(f.data(), 0, tuple);
                    stamp(f.data(), 0, f.pageNo(), () -> insertRecord(f.pageNo(), 0, tuple));
                }
                lastPage = f.pageNo();
                return new Rid(f.pageNo(), 0);
            } finally {
                pool.unpin(f, true);
            }
        }
    }

    private Rid tryInsert(int pageNo, byte[] tuple) {
        Frame f = pool.pin(fileId, pageNo);
        boolean changed = false;
        try {
            synchronized (f) {
                byte[] p = f.data();
                long before = Page.lsn(p);
                int slot = chooseSlot(p, tuple.length);
                if (slot < 0) return null;
                putAt(p, slot, tuple);
                stamp(p, before, pageNo, () -> insertRecord(pageNo, slot, tuple));
                changed = true;
                return new Rid(pageNo, slot);
            }
        } finally {
            pool.unpin(f, changed);
        }
    }

    /**
     * Sets a version's {@code xmax} (and the pointer to its replacement), but
     * only if its {@code xmax} is still {@code expected}. Returns false if
     * someone else changed it first.
     */
    public boolean compareAndSetXmax(Rid rid, long expected, long xmax, Rid next) {
        Frame f = pool.pin(fileId, rid.page());
        boolean changed = false;
        try {
            synchronized (f) {
                byte[] p = f.data();
                if (rid.slot() >= slotCount(p) || slotLength(p, rid.slot()) == 0) return false;
                if (Page.getLong(p, slotOffset(p, rid.slot()) + 8) != expected) return false;
                long before = Page.lsn(p);
                writeXmax(p, rid.slot(), xmax, next);
                stamp(p, before, rid.page(), () -> xmaxRecord(rid, xmax, next));
                changed = true;
                return true;
            }
        } finally {
            pool.unpin(f, changed);
        }
    }

    private record LogRecord(byte type, byte[] payload) {}

    /** Logs a change already made to page bytes (with the frame's monitor held) and stamps the page's LSN. */
    private void stamp(byte[] p, long lsnBefore, int pageNo, Supplier<LogRecord> logical) {
        long lsn;
        if (lsnBefore <= redoPoint.getAsLong()) {
            lsn = wal.append(Wal.PAGE_IMAGE, imageRecord(fileId, pageNo, p));
        } else {
            LogRecord r = logical.get();
            lsn = wal.append(r.type(), r.payload());
        }
        Page.setLsn(p, lsn);
    }

    // ---------------- reads ----------------

    /** The version at {@code rid}, or null if the slot is empty (removed by VACUUM). */
    public Tuple read(Rid rid) {
        if (rid.page() >= pageCount()) return null;
        Frame f = pool.pin(fileId, rid.page());
        try {
            synchronized (f) {
                byte[] p = f.data();
                if (Page.type(p) != Page.HEAP || rid.slot() >= slotCount(p)) return null;
                return tupleAt(p, rid.page(), rid.slot());
            }
        } finally {
            pool.unpin(f, false);
        }
    }

    /** Every version on one page, copied out so the page isn't held while the caller works. */
    public List<Tuple> page(int pageNo) {
        Frame f = pool.pin(fileId, pageNo);
        try {
            synchronized (f) {
                byte[] p = f.data();
                List<Tuple> out = new ArrayList<>();
                if (Page.type(p) != Page.HEAP) return out;
                int n = slotCount(p);
                for (int s = 0; s < n; s++) {
                    Tuple t = tupleAt(p, pageNo, s);
                    if (t != null) out.add(t);
                }
                return out;
            }
        } finally {
            pool.unpin(f, false);
        }
    }

    // ---------------- vacuum ----------------

    /**
     * Removes the versions {@code dead} picks from one page and compacts it.
     * Logged as a full page image. Returns how many versions were removed.
     */
    public int vacuumPage(int pageNo, Predicate<Tuple> dead) {
        Frame f = pool.pin(fileId, pageNo);
        int removed = 0;
        try {
            synchronized (f) {
                byte[] p = f.data();
                if (Page.type(p) != Page.HEAP) return 0;
                int n = slotCount(p);
                byte[][] keep = new byte[n][];
                for (int s = 0; s < n; s++) {
                    Tuple t = tupleAt(p, pageNo, s);
                    if (t == null) continue;
                    if (dead.test(t)) {
                        removed++;
                    } else {
                        int off = slotOffset(p, s);
                        keep[s] = Arrays.copyOfRange(p, off, off + slotLength(p, s));
                    }
                }
                if (removed == 0) return 0;
                while (n > 0 && keep[n - 1] == null) n--; // trailing empty slots can go
                Page.putU16(p, SLOT_COUNT, 0);
                Page.putU16(p, FREE_END, Page.SIZE);
                Arrays.fill(p, SLOTS, Page.SIZE, (byte) 0);
                Page.putU16(p, SLOT_COUNT, n);
                for (int s = 0; s < n; s++) if (keep[s] != null) putAt(p, s, keep[s]);
                Page.setLsn(p, wal.append(Wal.PAGE_IMAGE, imageRecord(fileId, pageNo, p)));
            }
        } finally {
            pool.unpin(f, removed > 0);
        }
        if (removed > 0) {
            synchronized (insertLock) {
                if (!pagesWithSpace.contains(pageNo)) pagesWithSpace.add(pageNo);
            }
        }
        return removed;
    }

    // ---------------- log records and redo ----------------

    private LogRecord insertRecord(int pageNo, int slot, byte[] tuple) {
        ByteBuffer b = ByteBuffer.allocate(10 + tuple.length);
        b.putInt(fileId).putInt(pageNo).putShort((short) slot).put(tuple);
        return new LogRecord(Wal.HEAP_INSERT, b.array());
    }

    private LogRecord xmaxRecord(Rid rid, long xmax, Rid next) {
        ByteBuffer b = ByteBuffer.allocate(10 + 8 + 6);
        b.putInt(fileId).putInt(rid.page()).putShort((short) rid.slot()).putLong(xmax);
        b.putInt(next == null ? -1 : next.page()).putShort((short) (next == null ? 0 : next.slot()));
        return new LogRecord(Wal.HEAP_SET_XMAX, b.array());
    }

    private static byte[] imageRecord(int fileId, int pageNo, byte[] page) {
        ByteBuffer b = ByteBuffer.allocate(8 + Page.SIZE);
        b.putInt(fileId).putInt(pageNo).put(page);
        return b.array();
    }

    /**
     * Replays one heap record during recovery, if the page doesn't already
     * include it. Calls {@code sawXid} with every transaction id it finds,
     * so recovery never hands out an id that's already in the data.
     */
    public static void redo(BufferPool pool, byte type, byte[] payload, long lsn, LongConsumer sawXid) {
        ByteBuffer in = ByteBuffer.wrap(payload);
        int fileId = in.getInt();
        int pageNo = in.getInt();
        pool.ensurePages(fileId, pageNo + 1);
        Frame f = type == Wal.PAGE_IMAGE ? pool.pinForOverwrite(fileId, pageNo) : pool.pin(fileId, pageNo);
        boolean changed = false;
        try {
            synchronized (f) {
                byte[] p = f.data();
                if (type == Wal.PAGE_IMAGE) {
                    in.get(p, 0, Page.SIZE);
                    for (Tuple t : tuples(p, pageNo)) {
                        sawXid.accept(t.xmin());
                        if (t.xmax() != 0) sawXid.accept(t.xmax());
                    }
                    Page.setLsn(p, lsn);
                    changed = true;
                    return;
                }
                if (Page.lsn(p) >= lsn) return; // already on the page
                int slot = in.getShort() & 0xFFFF;
                if (type == Wal.HEAP_INSERT) {
                    byte[] tuple = new byte[in.remaining()];
                    in.get(tuple);
                    sawXid.accept(Page.getLong(tuple, 0));
                    putAt(p, slot, tuple);
                } else {
                    long xmax = in.getLong();
                    int nextPage = in.getInt();
                    int nextSlot = in.getShort() & 0xFFFF;
                    sawXid.accept(xmax);
                    writeXmax(p, slot, xmax, nextPage < 0 ? null : new Rid(nextPage, nextSlot));
                }
                Page.setLsn(p, lsn);
                changed = true;
            }
        } finally {
            pool.unpin(f, changed);
        }
    }

    private static List<Tuple> tuples(byte[] p, int pageNo) {
        List<Tuple> out = new ArrayList<>();
        if (Page.type(p) != Page.HEAP) return out;
        for (int s = 0; s < slotCount(p); s++) {
            Tuple t = tupleAt(p, pageNo, s);
            if (t != null) out.add(t);
        }
        return out;
    }
}
