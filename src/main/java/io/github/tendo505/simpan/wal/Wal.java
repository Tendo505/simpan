package io.github.tendo505.simpan.wal;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

/**
 * The write-ahead log. Records are framed as {@code [length][crc][type][payload]}
 * and addressed by LSN, their end position in a log that never restarts at
 * zero: after a checkpoint the log moves to a new segment file named after
 * its starting LSN, and older segments are deleted.
 *
 * <p>Appending only buffers; {@link #flush(long)} writes and fsyncs. Commits
 * that arrive while another commit is syncing wait for the lock, then find
 * their record already on disk: group commit, one fsync for many commits.
 */
public final class Wal implements Closeable {
    public static final byte HEAP_INSERT = 1;
    public static final byte HEAP_SET_XMAX = 2;
    public static final byte PAGE_IMAGE = 3;
    public static final byte COMMIT = 4;
    public static final byte ABORT = 5;
    public static final byte CATALOG = 6;
    public static final byte CHECKPOINT = 7;

    private static final int FRAME = 9; // length + crc + type

    /** A record read back during recovery; {@code lsn} is where it ends. */
    public record Record(long lsn, byte type, byte[] payload) {}

    private final Path dir;
    private final ReentrantLock flushLock = new ReentrantLock();
    private FileChannel channel;
    private ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private long end;            // LSN after the last appended record
    private volatile long flushed; // LSN up to which the log is on disk
    private long syncs;

    private Wal(Path dir, long start) throws IOException {
        this.dir = dir;
        this.end = start;
        this.flushed = start;
        this.channel = openSegment(start);
    }

    /** Opens the log for appending at {@code start}, in a fresh segment. */
    public static Wal open(Path dir, long start) throws IOException {
        Files.createDirectories(dir);
        return new Wal(dir, start);
    }

    private FileChannel openSegment(long start) throws IOException {
        Path p = dir.resolve(segmentName(start));
        return FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String segmentName(long start) {
        return String.format("%016x.wal", start);
    }

    /** Appends a record and returns its LSN (the log position just after it). Not yet durable. */
    public synchronized long append(byte type, byte[] payload) {
        CRC32C crc = new CRC32C();
        crc.update(type);
        crc.update(payload);
        ByteBuffer frame = ByteBuffer.allocate(FRAME);
        frame.putInt(payload.length).putInt((int) crc.getValue()).put(type);
        pending.write(frame.array(), 0, FRAME);
        pending.write(payload, 0, payload.length);
        end += FRAME + payload.length;
        return end;
    }

    public synchronized long end() {
        return end;
    }

    public long flushed() {
        return flushed;
    }

    /** Makes the log durable at least up to {@code lsn}. */
    public void flush(long lsn) {
        if (flushed >= lsn) return;
        flushLock.lock();
        try {
            if (flushed >= lsn) return; // someone else's fsync covered us
            byte[] data;
            long target;
            synchronized (this) {
                data = pending.toByteArray();
                pending = new ByteArrayOutputStream();
                target = end;
            }
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining()) channel.write(buf);
            channel.force(false);
            syncs++;
            flushed = target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            flushLock.unlock();
        }
    }

    public void flushAll() {
        flush(end());
    }

    /** fsync calls so far; fewer than commits means group commit is working. */
    public long syncs() {
        return syncs;
    }

    /**
     * Starts a new segment at the current end and deletes the older ones.
     * Called right after a checkpoint, when nothing before it is needed.
     */
    public void rotate(long checkpointStart) throws IOException {
        flushLock.lock();
        try {
            flushAll();
            synchronized (this) {
                channel.close();
                channel = openSegment(end);
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : files.toList()) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".wal") && segmentStart(name) < checkpointStart && !name.equals(segmentName(end))) {
                        Files.delete(p);
                    }
                }
            }
        } finally {
            flushLock.unlock();
        }
    }

    private static long segmentStart(String name) {
        return Long.parseUnsignedLong(name.substring(0, name.length() - 4), 16);
    }

    /** What recovery found: the intact records after a point, and where the intact log ends. */
    public record Contents(List<Record> records, long end) {}

    /**
     * Reads every intact record that ends after {@code fromLsn}, in order,
     * across segments. A segment's intact part ends at its first torn or
     * corrupt record (where the log stopped when the process died); the next
     * segment only counts if it starts exactly there, as the one opened after
     * recovery does.
     */
    public static Contents read(Path dir, long fromLsn) throws IOException {
        List<Record> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return new Contents(out, fromLsn);
        List<Path> segments;
        try (Stream<Path> files = Files.list(dir)) {
            segments = files.filter(p -> p.getFileName().toString().endsWith(".wal"))
                    .sorted((a, b) -> Long.compareUnsigned(segmentStart(a.getFileName().toString()), segmentStart(b.getFileName().toString())))
                    .toList();
        }
        long validEnd = -1;
        for (Path seg : segments) {
            long base = segmentStart(seg.getFileName().toString());
            if (validEnd >= 0 && base != validEnd) break;
            if (validEnd < 0 && base > fromLsn) break; // the segment holding fromLsn is missing
            ByteBuffer in = ByteBuffer.wrap(Files.readAllBytes(seg));
            int good = 0;
            while (in.remaining() >= FRAME) {
                int length = in.getInt();
                int crc = in.getInt();
                byte type = in.get();
                if (length < 0 || length > in.remaining()) break; // torn tail
                byte[] payload = new byte[length];
                in.get(payload);
                CRC32C check = new CRC32C();
                check.update(type);
                check.update(payload);
                if ((int) check.getValue() != crc) break;
                good = in.position();
                long lsn = base + good;
                if (lsn > fromLsn) out.add(new Record(lsn, type, payload));
            }
            validEnd = base + good;
        }
        return new Contents(out, Math.max(validEnd, fromLsn));
    }

    /** Closes without writing what's buffered, as a crash would (for tests). */
    public void abandon() throws IOException {
        flushLock.lock();
        try {
            synchronized (this) {
                pending = new ByteArrayOutputStream();
            }
            channel.close();
        } finally {
            flushLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        flushLock.lock();
        try {
            flushAll();
            channel.close();
        } finally {
            flushLock.unlock();
        }
    }
}
