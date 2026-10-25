package io.github.tendo505.simpan.storage;

import io.github.tendo505.simpan.SqlException;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * A fixed number of page-sized frames shared by every table and index file,
 * with clock eviction. A page is used by pinning it (so it can't be evicted),
 * reading or changing its bytes, then unpinning it.
 *
 * <p>Two rules keep it safe. Before a page with an LSN is written, the WAL
 * is flushed up to that LSN, so the log always describes what's on disk.
 * And nobody calls into the pool while holding a frame's monitor, because
 * eviction takes the pool lock and then a frame's monitor.
 */
public final class BufferPool implements Closeable {

    /** One page-sized slot. Its bytes may be read or changed only while pinned, under its monitor. */
    public static final class Frame {
        private final byte[] data = new byte[Page.SIZE];
        private int fileId = -1;
        private int pageNo = -1;
        private int pins;
        private boolean dirty;
        private boolean referenced;

        public byte[] data() {
            return data;
        }

        public int pageNo() {
            return pageNo;
        }

        public int fileId() {
            return fileId;
        }
    }

    private final Path dir;
    private final Frame[] frames;
    private final Map<Long, Frame> table = new HashMap<>();
    private final Map<Integer, FileChannel> files = new HashMap<>();
    private final Map<Integer, Integer> pageCounts = new HashMap<>();
    private final LongConsumer walFlush;
    private int hand;
    private long reads;
    private long writes;

    /** {@code walFlush} is called with a page's LSN before that page is written. */
    public BufferPool(Path dir, int capacity, LongConsumer walFlush) {
        this.dir = dir;
        this.frames = new Frame[capacity];
        for (int i = 0; i < capacity; i++) frames[i] = new Frame();
        this.walFlush = walFlush;
    }

    private static long key(int fileId, int pageNo) {
        return ((long) fileId << 32) | (pageNo & 0xFFFFFFFFL);
    }

    public static Path path(Path dir, int fileId) {
        return dir.resolve("rel-" + fileId + ".dat");
    }

    private FileChannel channel(int fileId) throws IOException {
        FileChannel ch = files.get(fileId);
        if (ch == null) {
            ch = FileChannel.open(path(dir, fileId), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            files.put(fileId, ch);
            pageCounts.putIfAbsent(fileId, (int) (ch.size() / Page.SIZE));
        }
        return ch;
    }

    /** Pages in the file, counting ones allocated but not yet written. */
    public synchronized int pageCount(int fileId) {
        try {
            channel(fileId);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return pageCounts.get(fileId);
    }

    /** Pins a page, reading it from disk if it isn't cached. Fails on a checksum mismatch. */
    public synchronized Frame pin(int fileId, int pageNo) {
        return pin(fileId, pageNo, true);
    }

    /** Pins a page that's about to be overwritten whole (recovery restoring an image), so a torn copy on disk is fine. */
    public synchronized Frame pinForOverwrite(int fileId, int pageNo) {
        return pin(fileId, pageNo, false);
    }

    private Frame pin(int fileId, int pageNo, boolean verify) {
        Frame f = table.get(key(fileId, pageNo));
        if (f != null) {
            f.pins++;
            f.referenced = true;
            return f;
        }
        f = victim();
        try {
            FileChannel ch = channel(fileId);
            ByteBuffer buf = ByteBuffer.wrap(f.data);
            long at = (long) pageNo * Page.SIZE;
            while (buf.hasRemaining()) {
                int n = ch.read(buf, at + buf.position());
                if (n < 0) break; // past the end: a page allocated but never written reads as zeros
            }
            while (buf.hasRemaining()) buf.put((byte) 0);
            reads++;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (verify && !Page.verify(f.data)) {
            throw new SqlException(SqlException.DATA_CORRUPTED, "page " + pageNo + " of file " + fileId + " failed its checksum");
        }
        install(f, fileId, pageNo);
        return f;
    }

    /** Adds a zeroed page at the end of the file and pins it. */
    public synchronized Frame allocate(int fileId) {
        int pageNo = pageCount(fileId);
        pageCounts.put(fileId, pageNo + 1);
        Frame f = victim();
        Arrays.fill(f.data, (byte) 0);
        install(f, fileId, pageNo);
        f.dirty = true;
        return f;
    }

    /** Makes a page exist (zeroed) if the file is shorter, for redo of a page that never reached disk. */
    public synchronized void ensurePages(int fileId, int pageCount) {
        if (pageCount(fileId) < pageCount) pageCounts.put(fileId, pageCount);
    }

    private void install(Frame f, int fileId, int pageNo) {
        f.fileId = fileId;
        f.pageNo = pageNo;
        f.pins = 1;
        f.dirty = false;
        f.referenced = true;
        table.put(key(fileId, pageNo), f);
    }

    public synchronized void unpin(Frame f, boolean dirty) {
        if (f.pins <= 0) throw new IllegalStateException("unpinning a page that isn't pinned");
        f.pins--;
        if (dirty) f.dirty = true;
    }

    /** Clock: skip pinned frames, give referenced ones a second chance, write out a dirty victim. */
    private Frame victim() {
        for (int sweep = 0; sweep < frames.length * 3; sweep++) {
            Frame f = frames[hand];
            hand = (hand + 1) % frames.length;
            if (f.fileId < 0) return f;
            if (f.pins > 0) continue;
            if (f.referenced) {
                f.referenced = false;
                continue;
            }
            if (f.dirty) write(f);
            table.remove(key(f.fileId, f.pageNo));
            f.fileId = -1;
            f.pageNo = -1;
            return f;
        }
        throw new SqlException(SqlException.INTERNAL, "buffer pool is full: every page is pinned");
    }

    private void write(Frame f) {
        byte[] copy;
        synchronized (f) {
            Page.seal(f.data);
            copy = f.data.clone();
        }
        long lsn = Page.lsn(copy);
        if (lsn > 0) walFlush.accept(lsn); // the log must reach disk before the page it describes
        try {
            FileChannel ch = channel(f.fileId);
            ByteBuffer buf = ByteBuffer.wrap(copy);
            long at = (long) f.pageNo * Page.SIZE;
            while (buf.hasRemaining()) ch.write(buf, at + buf.position());
            writes++;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        f.dirty = false;
    }

    /** Writes every dirty page and syncs the files. Used by checkpoints and clean shutdown. */
    public synchronized void flushAll() {
        for (Frame f : frames) if (f.fileId >= 0 && f.dirty) write(f);
        try {
            for (FileChannel ch : files.values()) ch.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Forgets a file's cached pages without writing them, and empties the file (for rebuilding an index). */
    public synchronized void truncate(int fileId) {
        forget(fileId);
        try {
            channel(fileId).truncate(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        pageCounts.put(fileId, 0);
    }

    /** Forgets a file's pages, closes it and deletes it. */
    public synchronized void delete(int fileId) {
        forget(fileId);
        try {
            FileChannel ch = files.remove(fileId);
            if (ch != null) ch.close();
            pageCounts.remove(fileId);
            Files.deleteIfExists(path(dir, fileId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void forget(int fileId) {
        for (Frame f : frames) {
            if (f.fileId != fileId) continue;
            if (f.pins > 0) throw new IllegalStateException("page " + f.pageNo + " of file " + fileId + " is still pinned");
            table.remove(key(f.fileId, f.pageNo));
            f.fileId = -1;
            f.pageNo = -1;
            f.dirty = false;
        }
    }

    public synchronized long reads() {
        return reads;
    }

    public synchronized long writes() {
        return writes;
    }

    @Override
    public synchronized void close() throws IOException {
        for (FileChannel ch : files.values()) ch.close();
        files.clear();
    }
}
