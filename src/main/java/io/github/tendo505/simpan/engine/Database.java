package io.github.tendo505.simpan.engine;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.catalog.Catalog;
import io.github.tendo505.simpan.catalog.Catalog.IndexDef;
import io.github.tendo505.simpan.catalog.Catalog.Stats;
import io.github.tendo505.simpan.catalog.Catalog.TableDef;
import io.github.tendo505.simpan.engine.Table.Index;
import io.github.tendo505.simpan.sql.Ast.Isolation;
import io.github.tendo505.simpan.storage.BTree;
import io.github.tendo505.simpan.storage.BufferPool;
import io.github.tendo505.simpan.storage.HeapFile;
import io.github.tendo505.simpan.tx.Snapshot;
import io.github.tendo505.simpan.tx.Transaction;
import io.github.tendo505.simpan.tx.TxManager;
import io.github.tendo505.simpan.types.Values;
import io.github.tendo505.simpan.wal.Wal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

/**
 * One database directory: table and index files, the WAL, and checkpoints.
 *
 * <p>A checkpoint writes every dirty page, then the schema and commit log
 * into a state file, then points the control file at it. Recovery loads
 * that state and replays the WAL after it: heap changes (skipping ones a
 * page already has), commits and aborts, schema changes. Transactions that
 * never committed are marked aborted, and since MVCC never overwrites a row
 * in place, nothing needs undoing. Indexes are rebuilt from the heap if
 * anything was replayed.
 */
public final class Database implements Closeable {
    /** {@code bufferPages} frames of 8 KiB. {@code syncCommit=false} skips waiting for the fsync, like PostgreSQL's synchronous_commit=off. */
    public record Options(int bufferPages, boolean syncCommit, long checkpointWalBytes) {
        public static Options defaults() {
            return new Options(4096, true, 64L << 20);
        }
    }

    private static final long MAGIC = 0x53494D50414E3031L; // "SIMPAN01"

    private final Path dir;
    private final Options options;
    private final ReentrantReadWriteLock checkpointLock = new ReentrantReadWriteLock();
    private final Map<String, Table> tables = new ConcurrentHashMap<>();
    private final List<Integer> pendingDeletes = new ArrayList<>();
    private final Object ddlLock = new Object();
    private volatile Wal wal;
    private BufferPool pool;
    private TxManager tm;
    private Catalog catalog;
    private volatile long redoPoint;
    private volatile boolean closed;
    private Thread checkpointer;
    private RecoveryReport recovery;

    /** What opening the database had to do. */
    public record RecoveryReport(int recordsReplayed, int indexesRebuilt, long abortedTransactions) {}

    private Database(Path dir, Options options) {
        this.dir = dir;
        this.options = options;
    }

    public static Database open(Path dir) {
        return open(dir, Options.defaults());
    }

    public static Database open(Path dir, Options options) {
        Database db = new Database(dir, options);
        try {
            db.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return db;
    }

    private Path walDir() {
        return dir.resolve("wal");
    }

    private void start() throws IOException {
        Files.createDirectories(dir);
        long checkpointLsn = readControl();
        catalog = new Catalog();
        tm = new TxManager(null, 1);
        if (checkpointLsn >= 0) loadState(checkpointLsn);
        Wal.Contents log = Wal.read(walDir(), Math.max(checkpointLsn, 0));
        // Pages may be written before the WAL is open (it's only being read then, and it's already on disk).
        pool = new BufferPool(dir, options.bufferPages(), lsn -> {
            Wal w = wal;
            if (w != null) w.flush(lsn);
        });
        for (Wal.Record r : log.records()) replay(r);
        long aborted = tm.abortUnfinished();
        wal = Wal.open(walDir(), log.end());
        TxManager replayed = tm;
        tm = new TxManager(wal, replayed.nextXid());
        copyClog(replayed, tm);
        redoPoint = log.end();
        for (TableDef def : catalog.tables()) tables.put(def.name(), open(def));
        int rebuilt = 0;
        if (!log.records().isEmpty()) {
            // The indexes weren't logged, so after a crash they're rebuilt from the recovered heap.
            for (Table t : tables.values()) {
                for (Index i : t.indexes()) {
                    t.rebuild(i, tm.nextXid());
                    rebuilt++;
                }
            }
        }
        deleteOrphanFiles();
        recovery = new RecoveryReport(log.records().size(), rebuilt, aborted);
        checkpoint();
        checkpointer = new Thread(this::checkpointLoop, "simpan-checkpointer");
        checkpointer.setDaemon(true);
        checkpointer.start();
    }

    private static void copyClog(TxManager from, TxManager to) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            from.save(bytes);
            to.load(new ByteArrayInputStream(bytes.toByteArray()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void replay(Wal.Record r) {
        switch (r.type()) {
            case Wal.PAGE_IMAGE, Wal.HEAP_INSERT, Wal.HEAP_SET_XMAX -> HeapFile.redo(pool, r.type(), r.payload(), r.lsn(), tm::sawXid);
            case Wal.COMMIT -> tm.setStatus(ByteBuffer.wrap(r.payload()).getLong(), TxManager.COMMITTED);
            case Wal.ABORT -> tm.setStatus(ByteBuffer.wrap(r.payload()).getLong(), TxManager.ABORTED);
            case Wal.CATALOG -> catalog = Catalog.deserialize(r.payload());
            case Wal.CHECKPOINT -> { }
            default -> throw new SqlException(SqlException.DATA_CORRUPTED, "unknown WAL record type " + r.type());
        }
    }

    private Table open(TableDef def) {
        HeapFile heap = new HeapFile(def.fileId(), pool, wal, () -> redoPoint);
        List<Index> indexes = new ArrayList<>();
        for (IndexDef i : def.indexes()) indexes.add(new Index(i, new BTree(i.fileId(), pool)));
        return new Table(def, heap, indexes, tm, checkpointLock.readLock());
    }

    private void deleteOrphanFiles() throws IOException {
        Set<String> live = new HashSet<>();
        for (TableDef t : catalog.tables()) {
            live.add(BufferPool.path(dir, t.fileId()).getFileName().toString());
            for (IndexDef i : t.indexes()) live.add(BufferPool.path(dir, i.fileId()).getFileName().toString());
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith("rel-") && !live.contains(name)) Files.delete(p);
            }
        }
    }

    // ---------------- control file and checkpoint state ----------------

    private long readControl() throws IOException {
        Path control = dir.resolve("control");
        if (!Files.exists(control)) return -1;
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(control));
        if (b.remaining() < 20 || b.getLong() != MAGIC) throw new SqlException(SqlException.DATA_CORRUPTED, "not a Simpan database: " + dir);
        long lsn = b.getLong();
        CRC32C crc = new CRC32C();
        crc.update(ByteBuffer.allocate(8).putLong(lsn).array());
        if ((int) crc.getValue() != b.getInt()) throw new SqlException(SqlException.DATA_CORRUPTED, "control file is corrupt");
        return lsn;
    }

    private void writeControl(long lsn) throws IOException {
        CRC32C crc = new CRC32C();
        crc.update(ByteBuffer.allocate(8).putLong(lsn).array());
        ByteBuffer b = ByteBuffer.allocate(20).putLong(MAGIC).putLong(lsn).putInt((int) crc.getValue());
        writeDurably(dir.resolve("control"), b.array());
    }

    /** Writes a temporary file, syncs it, then renames it over the target, so the target is always whole. */
    private void writeDurably(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path statePath(long lsn) {
        return dir.resolve(String.format("state-%016x.dat", lsn));
    }

    private void loadState(long lsn) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(statePath(lsn))));
        byte[] cat = new byte[in.readInt()];
        in.readFully(cat);
        catalog = Catalog.deserialize(cat);
        tm.load(in);
    }

    /**
     * Makes everything so far durable in the data files, so recovery can start
     * here. Briefly stops all page changes while it runs (a "sharp" checkpoint).
     */
    public void checkpoint() {
        checkpointLock.writeLock().lock();
        try {
            wal.flushAll();
            pool.flushAll();
            for (Table t : tables.values()) saveRowEstimate(t);
            long lsn = wal.append(Wal.CHECKPOINT, new byte[0]);
            wal.flush(lsn);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            byte[] cat = catalog.serialize();
            out.writeInt(cat.length);
            out.write(cat);
            tm.save(out);
            out.flush();
            writeDurably(statePath(lsn), bytes.toByteArray());
            writeControl(lsn);
            redoPoint = lsn;
            wal.rotate(lsn);
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : files.toList()) {
                    String name = p.getFileName().toString();
                    if (name.startsWith("state-") && !p.equals(statePath(lsn))) Files.delete(p);
                }
            }
            synchronized (pendingDeletes) {
                for (int fileId : pendingDeletes) pool.delete(fileId);
                pendingDeletes.clear();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            checkpointLock.writeLock().unlock();
        }
    }

    private void saveRowEstimate(Table t) {
        long delta = t.rowDelta.getAndSet(0);
        if (delta == 0) return;
        Stats s = catalog.stats(t.def().name());
        catalog.setStats(t.def().name(), new Stats(Math.max(0, (s == null ? 0 : s.rows()) + delta), s == null ? null : s.distinct()));
    }

    private void checkpointLoop() {
        while (!closed) {
            try {
                Thread.sleep(250);
                if (!closed && wal.end() - redoPoint > options.checkpointWalBytes()) checkpoint();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // a failed background checkpoint is retried next time round
            }
        }
    }

    // ---------------- transactions ----------------

    public Transaction begin(Isolation isolation) {
        return tm.begin(isolation);
    }

    public Snapshot snapshot(Transaction t) {
        return tm.snapshotFor(t);
    }

    /** Commits; the commit record and the commit-log update happen together relative to checkpoints. */
    public void commit(Transaction t) {
        checkpointLock.readLock().lock();
        try {
            if (options.syncCommit()) {
                tm.commit(t);
            } else {
                tm.commitAsync(t);
            }
        } finally {
            checkpointLock.readLock().unlock();
        }
    }

    public void abort(Transaction t) {
        checkpointLock.readLock().lock();
        try {
            tm.abort(t);
        } finally {
            checkpointLock.readLock().unlock();
        }
    }

    public TxManager txManager() {
        return tm;
    }

    // ---------------- schema ----------------

    public Catalog catalog() {
        return catalog;
    }

    public Table table(String name) {
        Table t = tables.get(name);
        if (t == null) throw new SqlException(SqlException.UNDEFINED_TABLE, "relation \"" + name + "\" does not exist");
        return t;
    }

    public Table tableOrNull(String name) {
        return tables.get(name);
    }

    /** Logs the whole schema after a change, durably, as part of the change. */
    private void logCatalog() {
        long lsn = wal.append(Wal.CATALOG, catalog.serialize());
        wal.flush(lsn);
    }

    public void createTable(TableDef def) {
        synchronized (ddlLock) {
            if (catalog.nameInUse(def.name())) {
                throw new SqlException(SqlException.DUPLICATE_TABLE, "relation \"" + def.name() + "\" already exists");
            }
            checkpointLock.readLock().lock();
            try {
                catalog.put(def);
                logCatalog();
                tables.put(def.name(), open(def));
            } finally {
                checkpointLock.readLock().unlock();
            }
        }
    }

    public int allocateFileId() {
        return catalog.allocateFileId();
    }

    public void dropTable(String name) {
        synchronized (ddlLock) {
            Table t = table(name);
            lockExclusive(t);
            try {
                checkpointLock.readLock().lock();
                try {
                    catalog.remove(name);
                    logCatalog();
                    tables.remove(name);
                } finally {
                    checkpointLock.readLock().unlock();
                }
                // The files go after the next checkpoint, once no log record that recovery might replay mentions them.
                synchronized (pendingDeletes) {
                    pendingDeletes.add(t.def().fileId());
                    for (IndexDef i : t.def().indexes()) pendingDeletes.add(i.fileId());
                }
            } finally {
                t.lock().writeLock().unlock();
            }
        }
    }

    /** Builds a new index from the table's rows; a unique index fails if they already have duplicates. */
    public void createIndex(String tableName, IndexDef def) {
        synchronized (ddlLock) {
            if (catalog.nameInUse(def.name())) {
                throw new SqlException(SqlException.DUPLICATE_TABLE, "relation \"" + def.name() + "\" already exists");
            }
            Table t = table(tableName);
            lockExclusive(t);
            try {
                Index index = new Index(def, new BTree(def.fileId(), pool));
                t.rebuild(index, tm.oldestActive());
                if (def.unique()) checkNoDuplicates(t, index);
                List<IndexDef> defs = new ArrayList<>(t.def().indexes());
                defs.add(def);
                TableDef updated = new TableDef(t.def().name(), t.def().fileId(), t.def().columns(), List.copyOf(defs));
                List<Index> indexes = new ArrayList<>(t.indexes());
                indexes.add(index);
                checkpointLock.readLock().lock();
                try {
                    // Index pages aren't logged, so they must be on disk before the log says the index exists.
                    pool.flushAll();
                    catalog.put(updated);
                    logCatalog();
                    t.setDef(updated, indexes);
                } finally {
                    checkpointLock.readLock().unlock();
                }
            } catch (RuntimeException e) {
                pool.delete(def.fileId());
                throw e;
            } finally {
                t.lock().writeLock().unlock();
            }
        }
    }

    /**
     * Reads the new index in key order: two neighbouring live versions with
     * equal keys are a duplicate. "Live" means as the data will end up, not
     * as some snapshot sees it: a version whose insert or delete is still
     * undecided makes us wait for that transaction and look again, so an
     * uncommitted duplicate can't slip under a new unique index.
     */
    private void checkNoDuplicates(Table t, Index index) {
        Transaction probe = tm.begin(Isolation.READ_COMMITTED);
        probe.setLockTimeoutMillis(30_000);
        try {
            while (true) {
                long undecided = 0;
                byte[] previous = null;
                for (Iterator<io.github.tendo505.simpan.storage.Rid> rids = index.tree().scan(null, null); rids.hasNext() && undecided == 0; ) {
                    HeapFile.Tuple v = t.heap().read(rids.next());
                    if (v == null) continue;
                    byte created = tm.status(v.xmin());
                    if (created == TxManager.ABORTED) continue;
                    if (created == TxManager.IN_PROGRESS) {
                        undecided = v.xmin();
                        continue;
                    }
                    if (v.xmax() != 0) {
                        byte deleted = tm.status(v.xmax());
                        if (deleted == TxManager.COMMITTED) continue;
                        if (deleted == TxManager.IN_PROGRESS) {
                            undecided = v.xmax();
                            continue;
                        }
                    }
                    Object[] row = t.decode(v);
                    if (index.def().columns().stream().anyMatch(c -> row[c] == null)) continue;
                    byte[] key = index.key(row);
                    if (previous != null && java.util.Arrays.equals(previous, key)) {
                        StringBuilder vals = new StringBuilder();
                        for (int c : index.def().columns()) vals.append(vals.isEmpty() ? "" : ", ").append(Values.format(row[c]));
                        throw new SqlException(SqlException.UNIQUE, "could not create unique index \"" + index.def().name()
                                + "\": Key (" + vals + ") is duplicated.");
                    }
                    previous = key;
                }
                if (undecided == 0) return;
                tm.waitFor(probe, undecided);
            }
        } finally {
            tm.abort(probe);
        }
    }

    /**
     * Takes a table's lock exclusively without queueing for it. A queued
     * writer makes new readers wait too, so a transaction holding a row lock
     * couldn't run its next statement, while a statement waiting for that
     * row lock keeps the table busy: a deadlock nothing would detect.
     * Trying repeatedly never blocks anyone.
     */
    private static void lockExclusive(Table t) {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (!t.lock().writeLock().tryLock()) {
            if (System.nanoTime() > deadline) {
                throw new SqlException(SqlException.LOCK_TIMEOUT, "could not lock table \"" + t.def().name() + "\": it stayed in use for a minute");
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SqlException(SqlException.LOCK_TIMEOUT, "interrupted while waiting to lock table \"" + t.def().name() + "\"");
            }
        }
    }

    public void dropIndex(String name) {
        synchronized (ddlLock) {
            TableDef owner = catalog.tableOfIndex(name);
            if (owner == null) throw new SqlException(SqlException.UNDEFINED_TABLE, "index \"" + name + "\" does not exist");
            Table t = table(owner.name());
            lockExclusive(t);
            try {
                IndexDef def = owner.indexes().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
                if (def.primary()) {
                    throw new SqlException(SqlException.DEPENDENT_OBJECTS, "cannot drop index " + name + " because it backs the primary key of table " + owner.name());
                }
                List<IndexDef> defs = new ArrayList<>(owner.indexes());
                defs.remove(def);
                TableDef updated = new TableDef(owner.name(), owner.fileId(), owner.columns(), List.copyOf(defs));
                List<Index> indexes = new ArrayList<>(t.indexes());
                indexes.removeIf(i -> i.def().name().equals(name));
                checkpointLock.readLock().lock();
                try {
                    catalog.put(updated);
                    logCatalog();
                    t.setDef(updated, indexes);
                } finally {
                    checkpointLock.readLock().unlock();
                }
                synchronized (pendingDeletes) {
                    pendingDeletes.add(def.fileId());
                }
            } finally {
                t.lock().writeLock().unlock();
            }
        }
    }

    // ---------------- maintenance ----------------

    /** Removes dead row versions; returns how many. */
    public long vacuum(String tableName) {
        Table t = table(tableName);
        lockExclusive(t);
        try {
            return t.vacuum();
        } finally {
            t.lock().writeLock().unlock();
        }
    }

    /** Counts rows and distinct values per column, for the planner. */
    public void analyze(String tableName) {
        Table t = table(tableName);
        t.lock().readLock().lock();
        Transaction probe = tm.begin(Isolation.READ_COMMITTED);
        try {
            Snapshot s = tm.snapshotFor(probe);
            int n = t.def().columns().size();
            List<Set<Object>> distinct = new ArrayList<>();
            for (int i = 0; i < n; i++) distinct.add(new HashSet<>());
            long rows = 0;
            for (Iterator<Table.Row> it = t.scan(s); it.hasNext(); ) {
                Object[] row = it.next().values();
                rows++;
                for (int i = 0; i < n; i++) if (row[i] != null) distinct.get(i).add(Values.hashKey(row[i]));
            }
            long[] d = new long[n];
            for (int i = 0; i < n; i++) d[i] = distinct.get(i).size();
            t.rowDelta.set(0);
            catalog.setStats(t.def().name(), new Stats(rows, d));
        } finally {
            tm.abort(probe);
            t.lock().readLock().unlock();
        }
    }

    /** Estimated live rows, for the planner. */
    public long estimatedRows(Table t) {
        Stats s = catalog.stats(t.def().name());
        long base = s == null ? 0 : s.rows();
        return Math.max(0, base + t.rowDelta.get());
    }

    public RecoveryReport recoveryReport() {
        return recovery;
    }

    public Wal wal() {
        return wal;
    }

    public BufferPool pool() {
        return pool;
    }

    public Path dir() {
        return dir;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (checkpointer != null) checkpointer.interrupt();
        try {
            checkpoint();
            wal.close();
            pool.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Stops as a crash would (for tests): no checkpoint, and nothing that wasn't already flushed reaches disk. */
    public void crash() {
        closed = true;
        if (checkpointer != null) checkpointer.interrupt();
        try {
            wal.abandon();
            pool.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
