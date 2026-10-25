package io.github.tendo505.simpan.engine;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.catalog.Catalog.IndexDef;
import io.github.tendo505.simpan.catalog.Catalog.TableDef;
import io.github.tendo505.simpan.sql.Ast.Isolation;
import io.github.tendo505.simpan.storage.BTree;
import io.github.tendo505.simpan.storage.HeapFile;
import io.github.tendo505.simpan.storage.HeapFile.Tuple;
import io.github.tendo505.simpan.storage.Rid;
import io.github.tendo505.simpan.tx.Snapshot;
import io.github.tendo505.simpan.tx.Transaction;
import io.github.tendo505.simpan.tx.TxManager;
import io.github.tendo505.simpan.types.KeyCodec;
import io.github.tendo505.simpan.types.RowCodec;
import io.github.tendo505.simpan.types.Type;
import io.github.tendo505.simpan.types.Values;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;

/**
 * A table at run time: its heap, its indexes, and the MVCC rules for
 * reading and changing rows.
 *
 * <p>Statements hold the table's lock shared; DROP, VACUUM and CREATE INDEX
 * hold it exclusively. Every page change also holds the database's
 * checkpoint lock shared, briefly, so a checkpoint sees no half-made change;
 * it's never held while waiting for another transaction.
 */
public final class Table {
    /** A visible row and where its version lives. */
    public record Row(Rid rid, Object[] values) {}

    /** An index at run time. */
    public record Index(IndexDef def, BTree tree) {
        public byte[] key(Object[] row) {
            Object[] values = new Object[def.columns().size()];
            for (int i = 0; i < values.length; i++) values[i] = row[def.columns().get(i)];
            return KeyCodec.encode(values);
        }
    }

    private volatile TableDef def;
    private final HeapFile heap;
    private volatile List<Index> indexes;
    private final TxManager tm;
    private final Lock pageLock;
    private final ReentrantReadWriteLock tableLock = new ReentrantReadWriteLock();
    /** Rows added minus rows removed since the last statistics were saved. */
    final AtomicLong rowDelta = new AtomicLong();

    Table(TableDef def, HeapFile heap, List<Index> indexes, TxManager tm, Lock pageLock) {
        this.def = def;
        this.heap = heap;
        this.indexes = List.copyOf(indexes);
        this.tm = tm;
        this.pageLock = pageLock;
    }

    public TableDef def() {
        return def;
    }

    void setDef(TableDef def, List<Index> indexes) {
        this.def = def;
        this.indexes = List.copyOf(indexes);
    }

    public List<Index> indexes() {
        return indexes;
    }

    public HeapFile heap() {
        return heap;
    }

    public ReentrantReadWriteLock lock() {
        return tableLock;
    }

    public Type[] types() {
        return def.types();
    }

    public Object[] decode(Tuple t) {
        return RowCodec.decode(t.data(), 0, t.data().length, def.types());
    }

    // ---------------- reads ----------------

    /** Every row visible to the snapshot, page by page. */
    public Iterator<Row> scan(Snapshot s) {
        return new Iterator<>() {
            private int page;
            private List<Row> batch = List.of();
            private int i;

            @Override
            public boolean hasNext() {
                while (i >= batch.size()) {
                    if (page >= heap.pageCount()) return false;
                    List<Row> rows = new ArrayList<>();
                    for (Tuple t : heap.page(page++)) {
                        if (tm.visible(t.xmin(), t.xmax(), s)) rows.add(new Row(t.rid(), decode(t)));
                    }
                    batch = rows;
                    i = 0;
                }
                return true;
            }

            @Override
            public Row next() {
                if (!hasNext()) throw new NoSuchElementException();
                return batch.get(i++);
            }
        };
    }

    /** The row at {@code rid} if that version is visible to the snapshot. */
    public Row fetch(Rid rid, Snapshot s) {
        Tuple t = heap.read(rid);
        if (t == null || !tm.visible(t.xmin(), t.xmax(), s)) return null;
        return new Row(rid, decode(t));
    }

    /** Rows found through an index, for keys in {@code [from, to)}, that are visible to the snapshot. */
    public Iterator<Row> indexScan(Index index, byte[] from, byte[] to, Snapshot s) {
        Iterator<Rid> rids = index.tree().scan(from, to);
        return new Iterator<>() {
            private Row next;

            @Override
            public boolean hasNext() {
                while (next == null && rids.hasNext()) next = fetch(rids.next(), s);
                return next != null;
            }

            @Override
            public Row next() {
                if (!hasNext()) throw new NoSuchElementException();
                Row r = next;
                next = null;
                return r;
            }
        };
    }

    // ---------------- writes ----------------

    /** Checks constraints and stores a new row (a new version created by {@code tx}). */
    public Rid insert(Transaction tx, Object[] row) {
        for (int i = 0; i < row.length; i++) {
            if (row[i] == null && def.columns().get(i).notNull()) {
                throw new SqlException(SqlException.NOT_NULL, "null value in column \"" + def.columns().get(i).name()
                        + "\" of relation \"" + def.name() + "\" violates not-null constraint");
            }
        }
        byte[] bytes = RowCodec.encode(row, def.types());
        Rid rid;
        tx.markWrote();
        pageLock.lock();
        try {
            rid = heap.insert(tx.xid(), bytes);
        } finally {
            pageLock.unlock();
        }
        for (Index index : indexes) addEntry(tx, index, row, rid);
        rowDelta.incrementAndGet();
        return rid;
    }

    private void addEntry(Transaction tx, Index index, Object[] row, Rid rid) {
        byte[] key = index.key(row);
        if (!index.def().unique() || hasNull(index, row)) {
            index.tree().insert(key, rid); // index pages aren't logged, so no checkpoint lock needed
            return;
        }
        while (true) {
            long waitFor = index.tree().insertUnique(key, rid, other -> uniqueConflict(tx, index, row, other));
            if (waitFor == 0) return;
            tm.waitFor(tx, waitFor); // someone else's insert or delete of this key is undecided: wait, then look again
        }
    }

    private static boolean hasNull(Index index, Object[] row) {
        for (int c : index.def().columns()) if (row[c] == null) return true; // NULLs never collide, as in PostgreSQL
        return false;
    }

    /** 0 if the version at {@code other} doesn't block this key, a transaction to wait for, or throws for a duplicate. */
    private long uniqueConflict(Transaction tx, Index index, Object[] row, Rid other) {
        Tuple t = heap.read(other);
        if (t == null) return 0;
        long me = tx.xid();
        if (t.xmin() != me) {
            byte created = tm.status(t.xmin());
            if (created == TxManager.ABORTED) return 0;
            if (created == TxManager.IN_PROGRESS) return t.xmin();
        }
        if (t.xmax() == me) return 0; // we deleted or replaced it
        if (t.xmax() != 0) {
            byte deleted = tm.status(t.xmax());
            if (deleted == TxManager.COMMITTED) return 0;
            if (deleted == TxManager.IN_PROGRESS) return t.xmax();
        }
        StringJoiner cols = new StringJoiner(", ");
        StringJoiner vals = new StringJoiner(", ");
        for (int c : index.def().columns()) {
            cols.add(def.columns().get(c).name());
            vals.add(String.valueOf(Values.format(row[c])));
        }
        throw new SqlException(SqlException.UNIQUE, "duplicate key value violates unique constraint \"" + index.def().name()
                + "\": Key (" + cols + ")=(" + vals + ") already exists.");
    }

    /**
     * Claims the row at {@code rid} for an UPDATE or DELETE by {@code tx}:
     * sets its {@code xmax}, waiting for whoever holds it now. Returns the
     * claimed row, or null if it's gone.
     *
     * <p>If another transaction changed the row and committed first:
     * under REPEATABLE READ that's a serialization failure; under READ
     * COMMITTED we follow the chain to the newest version and, if it still
     * matches the statement's condition, claim that one instead (as
     * PostgreSQL does), so concurrent {@code SET n = n + 1}s never lose an
     * increment.
     */
    public Row lockForWrite(Transaction tx, Rid rid, Predicate<Object[]> stillMatches) {
        long me = tx.xid();
        while (true) {
            Tuple t = heap.read(rid);
            if (t == null) return null;
            long xmax = t.xmax();
            if (xmax == me) return null; // already changed by this transaction
            byte holder = xmax == 0 ? TxManager.ABORTED : tm.status(xmax);
            if (holder == TxManager.ABORTED) {
                tx.markWrote();
                boolean claimed;
                pageLock.lock();
                try {
                    claimed = heap.compareAndSetXmax(rid, xmax, me, null);
                } finally {
                    pageLock.unlock();
                }
                if (claimed) return new Row(rid, decode(t));
                continue; // someone got there first: look again
            }
            if (holder == TxManager.IN_PROGRESS) {
                tm.waitFor(tx, xmax);
                continue;
            }
            // Changed by a transaction that committed after our snapshot.
            if (tx.isolation() == Isolation.REPEATABLE_READ) {
                throw new SqlException(SqlException.SERIALIZATION, "could not serialize access due to concurrent update");
            }
            if (t.next() == null) return null; // it was deleted
            Tuple newer = heap.read(t.next());
            if (newer == null) return null;
            if (!stillMatches.test(decode(newer))) return null;
            rid = t.next();
        }
    }

    /** Points a claimed version at its replacement, so READ COMMITTED writers can follow the chain. */
    public void linkReplacement(Transaction tx, Rid old, Rid replacement) {
        pageLock.lock();
        try {
            heap.compareAndSetXmax(old, tx.xid(), tx.xid(), replacement);
        } finally {
            pageLock.unlock();
        }
    }

    void rowsRemoved(long n) {
        rowDelta.addAndGet(-n);
    }

    // ---------------- maintenance ----------------

    /** Refills an index from the heap: every version some snapshot might still see. */
    void rebuild(Index index, long oldestActive) {
        index.tree().clear();
        for (int p = 0; p < heap.pageCount(); p++) {
            for (Tuple t : heap.page(p)) {
                if (tm.mightBeVisible(t.xmin(), t.xmax(), oldestActive)) index.tree().insert(index.key(decode(t)), t.rid());
            }
        }
    }

    /** Removes versions no snapshot can see any more; rebuilds the indexes if anything went. Returns versions removed. */
    long vacuum() {
        long oldest = tm.oldestActive();
        long removed = 0;
        for (int p = 0; p < heap.pageCount(); p++) {
            pageLock.lock();
            try {
                removed += heap.vacuumPage(p, t -> !tm.mightBeVisible(t.xmin(), t.xmax(), oldest));
            } finally {
                pageLock.unlock();
            }
        }
        if (removed > 0) for (Index index : indexes) rebuild(index, oldest);
        return removed;
    }
}
