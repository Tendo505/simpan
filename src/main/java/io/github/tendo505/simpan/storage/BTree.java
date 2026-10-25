package io.github.tendo505.simpan.storage;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.storage.BufferPool.Frame;
import io.github.tendo505.simpan.types.KeyCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A B+tree index over pages. Every entry is an encoded key followed by the
 * row's {@link Rid}, which makes entries unique even when keys repeat, and
 * lets a lookup for a key be a range scan over a key prefix.
 *
 * <p>Page 0 holds the root's page number. A node page has, after the page
 * header, an entry count, a link (a leaf's right sibling; an inner node's
 * leftmost child), the start of the entry area, then one
 * {@code [offset][length]} slot per entry, kept in key order. An inner
 * entry is a child page number followed by the smallest key in that child.
 *
 * <p>Index pages aren't logged: an index is derived from its table, so after
 * a crash it's rebuilt instead of repaired. Entries are only ever added;
 * VACUUM rebuilds the index to drop entries for removed rows.
 */
public final class BTree {
    private static final int COUNT = Page.HEADER;
    private static final int LINK = Page.HEADER + 2;
    private static final int FREE_END = Page.HEADER + 6;
    private static final int SLOTS = Page.HEADER + 8;
    private static final int ROOT = Page.HEADER;
    /** A key may use at most a quarter of a page, so a split always leaves room. */
    public static final int MAX_KEY = (Page.SIZE - SLOTS) / 4 - Rid.BYTES - 8;

    private final int fileId;
    private final BufferPool pool;
    private long entries;

    public BTree(int fileId, BufferPool pool) {
        this.fileId = fileId;
        this.pool = pool;
        synchronized (this) {
            if (pool.pageCount(fileId) == 0) create();
        }
    }

    public int fileId() {
        return fileId;
    }

    private void create() {
        Frame meta = pool.allocate(fileId);
        Frame root = pool.allocate(fileId);
        try {
            synchronized (root) {
                initNode(root.data(), Page.BTREE_LEAF);
                Page.putInt(root.data(), LINK, -1);
            }
            synchronized (meta) {
                Page.setType(meta.data(), Page.BTREE_META);
                Page.putInt(meta.data(), ROOT, root.pageNo());
            }
        } finally {
            pool.unpin(root, true);
            pool.unpin(meta, true);
        }
    }

    /** Empties the index, to be filled again from its table. */
    public synchronized void clear() {
        pool.truncate(fileId);
        create();
        entries = 0;
    }

    // ---------------- node layout ----------------

    private static void initNode(byte[] p, byte type) {
        Arrays.fill(p, Page.HEADER, Page.SIZE, (byte) 0);
        Page.setType(p, type);
        Page.putU16(p, COUNT, 0);
        Page.putU16(p, FREE_END, Page.SIZE);
    }

    private static int count(byte[] p) {
        return Page.getU16(p, COUNT);
    }

    private static int offset(byte[] p, int i) {
        return Page.getU16(p, SLOTS + i * 4);
    }

    private static int length(byte[] p, int i) {
        return Page.getU16(p, SLOTS + i * 4 + 2);
    }

    private static boolean isLeaf(byte[] p) {
        return Page.type(p) == Page.BTREE_LEAF;
    }

    /** Where an entry's key starts: inner entries begin with a 4-byte child pointer. */
    private static int keyStart(byte[] p, int i) {
        return offset(p, i) + (isLeaf(p) ? 0 : 4);
    }

    private static int keyEnd(byte[] p, int i) {
        return offset(p, i) + length(p, i);
    }

    private static int child(byte[] p, int i) {
        return i < 0 ? Page.getInt(p, LINK) : Page.getInt(p, offset(p, i));
    }

    private static byte[] entry(byte[] p, int i) {
        return Arrays.copyOfRange(p, offset(p, i), keyEnd(p, i));
    }

    private static byte[] key(byte[] p, int i) {
        return Arrays.copyOfRange(p, keyStart(p, i), keyEnd(p, i));
    }

    private static int compareKey(byte[] p, int i, byte[] key) {
        return KeyCodec.compare(p, keyStart(p, i), keyEnd(p, i), key, 0, key.length);
    }

    /** The first entry whose key is >= {@code key} (count if none). */
    private static int lowerBound(byte[] p, byte[] key) {
        int lo = 0;
        int hi = count(p);
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compareKey(p, mid, key) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** In an inner node, the child to follow for {@code key}: the last entry with key <= it, or the leftmost child. */
    private static int childFor(byte[] p, byte[] key) {
        int lo = 0;
        int hi = count(p);
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compareKey(p, mid, key) <= 0) lo = mid + 1;
            else hi = mid;
        }
        return child(p, lo - 1);
    }

    private static int free(byte[] p) {
        return Page.getU16(p, FREE_END) - (SLOTS + count(p) * 4);
    }

    private static void insertAt(byte[] p, int i, byte[] entry) {
        int n = count(p);
        int end = Page.getU16(p, FREE_END) - entry.length;
        System.arraycopy(entry, 0, p, end, entry.length);
        Page.putU16(p, FREE_END, end);
        System.arraycopy(p, SLOTS + i * 4, p, SLOTS + (i + 1) * 4, (n - i) * 4);
        Page.putU16(p, SLOTS + i * 4, end);
        Page.putU16(p, SLOTS + i * 4 + 2, entry.length);
        Page.putU16(p, COUNT, n + 1);
    }

    /** Rewrites a node with the given entries (used by splits; also compacts). */
    private static void fill(byte[] p, byte type, int link, List<byte[]> entries) {
        initNode(p, type);
        Page.putInt(p, LINK, link);
        for (int i = 0; i < entries.size(); i++) insertAt(p, i, entries.get(i));
    }

    // ---------------- insert ----------------

    /** Adds an entry for {@code key} pointing at {@code rid}. */
    public synchronized void insert(byte[] key, Rid rid) {
        if (key.length > MAX_KEY) {
            throw new SqlException(SqlException.PROGRAM_LIMIT, "index row size " + key.length + " exceeds maximum " + MAX_KEY);
        }
        byte[] entry = Arrays.copyOf(key, key.length + Rid.BYTES);
        rid.write(entry, key.length);
        int root = root();
        Split split = insert(root, entry);
        if (split != null) {
            // The root split: a new root points at the old root and its new sibling.
            Frame f = pool.allocate(fileId);
            try {
                synchronized (f) {
                    byte[] sep = new byte[4 + split.separator.length];
                    Page.putInt(sep, 0, split.right);
                    System.arraycopy(split.separator, 0, sep, 4, split.separator.length);
                    fill(f.data(), Page.BTREE_INNER, root, List.of(sep));
                }
                setRoot(f.pageNo());
            } finally {
                pool.unpin(f, true);
            }
        }
        entries++;
    }

    /** A node split in two: {@code right} is the new page, {@code separator} its smallest key. */
    private record Split(int right, byte[] separator) {}

    private Split insert(int pageNo, byte[] entry) {
        Frame f = pool.pin(fileId, pageNo);
        try {
            byte[] p = f.data();
            if (isLeaf(p)) return addToLeaf(f, entry);
            int child;
            synchronized (f) {
                child = childFor(p, entry);
            }
            Split below = insert(child, entry);
            if (below == null) return null;
            byte[] sep = new byte[4 + below.separator.length];
            Page.putInt(sep, 0, below.right);
            System.arraycopy(below.separator, 0, sep, 4, below.separator.length);
            return addToInner(f, sep, below.separator);
        } finally {
            pool.unpin(f, true);
        }
    }

    private Split addToLeaf(Frame f, byte[] entry) {
        byte[] p = f.data();
        synchronized (f) {
            int at = lowerBound(p, entry);
            if (free(p) >= entry.length + 4) {
                insertAt(p, at, entry);
                return null;
            }
        }
        List<byte[]> all = new ArrayList<>();
        int link;
        synchronized (f) {
            for (int i = 0; i < count(p); i++) all.add(entry(p, i));
            link = Page.getInt(p, LINK);
        }
        all.add(insertionPoint(all, entry, 0), entry);
        int mid = splitPoint(all);
        Frame right = pool.allocate(fileId);
        try {
            synchronized (right) {
                fill(right.data(), Page.BTREE_LEAF, link, all.subList(mid, all.size()));
            }
            synchronized (f) {
                fill(p, Page.BTREE_LEAF, right.pageNo(), all.subList(0, mid));
            }
            return new Split(right.pageNo(), all.get(mid));
        } finally {
            pool.unpin(right, true);
        }
    }

    /** {@code key} is the separator itself; {@code sep} is it with the child pointer in front. */
    private Split addToInner(Frame f, byte[] sep, byte[] key) {
        byte[] p = f.data();
        synchronized (f) {
            int at = lowerBound(p, key);
            if (free(p) >= sep.length + 4) {
                insertAt(p, at, sep);
                return null;
            }
        }
        List<byte[]> all = new ArrayList<>();
        int leftmost;
        synchronized (f) {
            for (int i = 0; i < count(p); i++) all.add(entry(p, i));
            leftmost = Page.getInt(p, LINK);
        }
        all.add(insertionPoint(all, sep, 4), sep);
        // The middle entry moves up: its child becomes the new node's leftmost child.
        int mid = Math.min(splitPoint(all), all.size() - 1);
        byte[] up = all.get(mid);
        int upChild = Page.getInt(up, 0);
        byte[] upKey = Arrays.copyOfRange(up, 4, up.length);
        Frame right = pool.allocate(fileId);
        try {
            synchronized (right) {
                fill(right.data(), Page.BTREE_INNER, upChild, all.subList(mid + 1, all.size()));
            }
            synchronized (f) {
                fill(p, Page.BTREE_INNER, leftmost, all.subList(0, mid));
            }
            return new Split(right.pageNo(), upKey);
        } finally {
            pool.unpin(right, true);
        }
    }

    /** Splits by bytes, not entry count, so neither half can overflow when key sizes vary. */
    private static int splitPoint(List<byte[]> all) {
        int total = 0;
        for (byte[] e : all) total += e.length + 4;
        int acc = 0;
        for (int i = 0; i < all.size(); i++) {
            acc += all.get(i).length + 4;
            if (acc >= total / 2) return Math.max(1, Math.min(i + 1, all.size() - 1));
        }
        return all.size() / 2;
    }

    private static int insertionPoint(List<byte[]> sorted, byte[] e, int skip) {
        int lo = 0;
        int hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            byte[] m = sorted.get(mid);
            if (KeyCodec.compare(m, skip, m.length, e, skip, e.length) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private int root() {
        Frame meta = pool.pin(fileId, 0);
        try {
            synchronized (meta) {
                return Page.getInt(meta.data(), ROOT);
            }
        } finally {
            pool.unpin(meta, false);
        }
    }

    private void setRoot(int pageNo) {
        Frame meta = pool.pin(fileId, 0);
        try {
            synchronized (meta) {
                Page.putInt(meta.data(), ROOT, pageNo);
            }
        } finally {
            pool.unpin(meta, true);
        }
    }

    // ---------------- scans ----------------

    /**
     * The rows whose keys are in {@code [from, to)}, in key order. Null bounds
     * are open. Reads one leaf at a time, and finds its place again from the
     * root for the next leaf, so inserts and splits between calls can't make
     * it skip or repeat an entry.
     */
    public Iterator<Rid> scan(byte[] from, byte[] to) {
        return new Iterator<>() {
            private List<byte[]> batch = List.of();
            private int i;
            private byte[] after = null; // last entry returned
            private boolean done;

            private void load() {
                while (i >= batch.size() && !done) {
                    batch = nextLeaf(after == null ? from : after, after != null, to);
                    i = 0;
                    if (batch.isEmpty()) done = true;
                }
            }

            @Override
            public boolean hasNext() {
                load();
                return i < batch.size();
            }

            @Override
            public Rid next() {
                if (!hasNext()) throw new NoSuchElementException();
                after = batch.get(i++);
                return Rid.read(after, after.length - Rid.BYTES);
            }
        };
    }

    /** Entries of the leaf holding the first entry after {@code start} (or at it, unless {@code exclusive}), before {@code to}. */
    private synchronized List<byte[]> nextLeaf(byte[] start, boolean exclusive, byte[] to) {
        int pageNo = root();
        byte[] seek = start == null ? new byte[0] : start;
        while (true) {
            Frame f = pool.pin(fileId, pageNo);
            try {
                byte[] p = f.data();
                synchronized (f) {
                    if (!isLeaf(p)) {
                        pageNo = childFor(p, seek);
                        continue;
                    }
                }
                List<byte[]> out = new ArrayList<>();
                int link;
                synchronized (f) {
                    int at = lowerBound(p, seek);
                    if (exclusive && at < count(p) && compareKey(p, at, seek) == 0) at++;
                    for (int k = at; k < count(p); k++) {
                        if (to != null && compareKey(p, k, to) >= 0) return out;
                        out.add(entry(p, k));
                    }
                    link = Page.getInt(p, LINK);
                }
                if (!out.isEmpty() || link < 0) return out;
                pageNo = link; // nothing left here (we were at the end of the leaf): carry on in the next one
                seek = new byte[0];
                exclusive = false;
            } finally {
                pool.unpin(f, false);
            }
        }
    }

    /**
     * For a unique index: adds the entry only if {@code check} accepts every
     * row already indexed under the same key, all while holding the tree, so
     * two inserts of one key can't both pass. {@code check} returns 0 for
     * "no conflict", throws for a duplicate, or returns the id of a running
     * transaction whose outcome decides it; then nothing is inserted and that
     * id is returned for the caller to wait on (outside the tree) and retry.
     */
    public synchronized long insertUnique(byte[] key, Rid rid, java.util.function.ToLongFunction<Rid> check) {
        byte[] end = KeyCodec.prefixEnd(key);
        byte[] after = null;
        while (true) {
            List<byte[]> batch = nextLeaf(after == null ? key : after, after != null, end);
            if (batch.isEmpty()) break;
            for (byte[] e : batch) {
                long wait = check.applyAsLong(Rid.read(e, e.length - Rid.BYTES));
                if (wait != 0) return wait;
            }
            after = batch.get(batch.size() - 1);
        }
        insert(key, rid);
        return 0;
    }

    /** Number of entries added since this tree was opened or cleared (for statistics). */
    public synchronized long entriesAdded() {
        return entries;
    }
}
