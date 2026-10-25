package io.github.tendo505.simpan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tendo505.simpan.storage.BTree;
import io.github.tendo505.simpan.storage.BufferPool;
import io.github.tendo505.simpan.storage.HeapFile;
import io.github.tendo505.simpan.storage.Page;
import io.github.tendo505.simpan.storage.Rid;
import io.github.tendo505.simpan.types.KeyCodec;
import io.github.tendo505.simpan.types.RowCodec;
import io.github.tendo505.simpan.types.Type;
import io.github.tendo505.simpan.types.Values;
import io.github.tendo505.simpan.wal.Wal;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The storage layer on its own, checked against simple in-memory models. */
class StorageTest {
    @TempDir
    Path dir;

    private static byte[] entry(byte[] key, Rid rid) {
        byte[] e = Arrays.copyOf(key, key.length + Rid.BYTES);
        rid.write(e, key.length);
        return e;
    }

    /** 30,000 inserts of repeating keys through a 16-page buffer pool, so pages are evicted and read back constantly. */
    @Test
    void btreeMatchesASortedSet() throws Exception {
        try (BufferPool pool = new BufferPool(dir, 16, lsn -> { })) {
            BTree tree = new BTree(1, pool);
            TreeSet<byte[]> model = new TreeSet<>(KeyCodec::compare);
            Random rnd = new Random(42);
            for (int i = 0; i < 30_000; i++) {
                Object v = rnd.nextBoolean() ? (Object) ((long) rnd.nextInt(5_000) - 2_500) : "k" + rnd.nextInt(3_000) + "x".repeat(rnd.nextInt(40));
                byte[] key = KeyCodec.encode(new Object[] {v});
                Rid rid = new Rid(i / 100, i % 100);
                tree.insert(key, rid);
                model.add(entry(key, rid));
            }
            List<byte[]> scanned = new ArrayList<>();
            Iterator<Rid> it = tree.scan(null, null);
            Iterator<byte[]> expected = model.iterator();
            while (it.hasNext()) {
                Rid r = it.next();
                byte[] e = expected.next();
                assertEquals(Rid.read(e, e.length - Rid.BYTES), r);
                scanned.add(e);
            }
            assertEquals(model.size(), scanned.size());

            // Range scans over key prefixes: exactly the model's entries for that range.
            for (int q = 0; q < 300; q++) {
                long a = rnd.nextInt(5_000) - 2_500;
                long b = a + rnd.nextInt(200);
                byte[] from = KeyCodec.encode(new Object[] {a});
                byte[] to = KeyCodec.prefixEnd(KeyCodec.encode(new Object[] {b}));
                List<Rid> got = new ArrayList<>();
                tree.scan(from, to).forEachRemaining(got::add);
                List<Rid> want = new ArrayList<>();
                for (byte[] e : model.subSet(from, true, to, false)) want.add(Rid.read(e, e.length - Rid.BYTES));
                assertEquals(want, got, "range " + a + ".." + b);
            }
        }
    }

    /** Inserts while a scan is part-way through never make it repeat or skip an entry that was there all along. */
    @Test
    void scansSurviveSplitsUnderneathThem() throws Exception {
        try (BufferPool pool = new BufferPool(dir, 32, lsn -> { })) {
            BTree tree = new BTree(1, pool);
            for (long i = 0; i < 5_000; i += 2) tree.insert(KeyCodec.encode(new Object[] {i}), new Rid((int) i, 0));
            Iterator<Rid> it = tree.scan(null, null);
            List<Integer> seen = new ArrayList<>();
            long odd = 1;
            while (it.hasNext()) {
                seen.add(it.next().page());
                if (odd < 5_000) {
                    tree.insert(KeyCodec.encode(new Object[] {odd}), new Rid((int) odd, 0)); // lands all over, splitting leaves
                    odd += 2;
                }
            }
            for (int i = 1; i < seen.size(); i++) assertTrue(seen.get(i) > seen.get(i - 1), "in order, no repeats");
            for (int even = 0; even < 5_000; even += 2) assertTrue(seen.contains(even), "missed " + even);
        }
    }

    @Test
    void keyEncodingSortsLikeSql() {
        Random rnd = new Random(7);
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            values.add(switch (i % 3) {
                case 0 -> rnd.nextBoolean() ? rnd.nextLong() : (long) rnd.nextInt(10) - 5;
                case 1 -> switch (rnd.nextInt(4)) {
                    case 0 -> rnd.nextGaussian() * 1e6;
                    case 1 -> (double) rnd.nextInt(5) - 2;
                    case 2 -> -0.0;
                    default -> rnd.nextBoolean() ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
                };
                default -> randomText(rnd);
            });
        }
        for (Object a : values) {
            for (Object b : values) {
                if (Type.of(a) != Type.of(b)) continue;
                int sql = Integer.signum(Values.compare(a, b));
                int bytes = Integer.signum(KeyCodec.compare(KeyCodec.encode(new Object[] {a}), KeyCodec.encode(new Object[] {b})));
                assertEquals(sql, bytes, a + " vs " + b);
            }
        }
        // NULL sorts after everything, and a composite key sorts by its first value first.
        assertTrue(KeyCodec.compare(KeyCodec.encode(new Object[] {Long.MAX_VALUE}), KeyCodec.encode(new Object[] {null})) < 0);
        assertTrue(KeyCodec.compare(KeyCodec.encode(new Object[] {"a", 9L}), KeyCodec.encode(new Object[] {"ab", 1L})) < 0);
        assertTrue(KeyCodec.compare(KeyCodec.encode(new Object[] {"a\0b"}), KeyCodec.encode(new Object[] {"a"})) > 0);
    }

    private static String randomText(Random rnd) {
        StringBuilder sb = new StringBuilder();
        int n = rnd.nextInt(6);
        for (int i = 0; i < n; i++) {
            sb.appendCodePoint(switch (rnd.nextInt(5)) {
                case 0 -> 'a' + rnd.nextInt(3);
                case 1 -> 0;
                case 2 -> 0xE9;
                case 3 -> 0x1F600; // outside the BMP: a surrogate pair in Java, 4 bytes in UTF-8
                default -> 0xFFFD;
            });
        }
        return sb.toString();
    }

    @Test
    void rowsRoundTrip() {
        Type[] types = {Type.BIGINT, Type.TEXT, Type.DOUBLE, Type.BOOLEAN, Type.TEXT};
        Object[] row = {-7L, "héllo 😀", 2.5, true, null};
        byte[] bytes = RowCodec.encode(row, types);
        assertArrayEquals(row, RowCodec.decode(bytes, 0, bytes.length, types));
    }

    @Test
    void floatsPrintLikePostgres() {
        assertEquals("0.1", Values.formatDouble(0.1));
        assertEquals("100000", Values.formatDouble(100000.0));
        assertEquals("1e+15", Values.formatDouble(1e15));
        assertEquals("123456789012345", Values.formatDouble(123456789012345.0));
        assertEquals("1.5e-05", Values.formatDouble(0.000015));
        assertEquals("0.0001", Values.formatDouble(0.0001));
        assertEquals("-2.5", Values.formatDouble(-2.5));
        assertEquals("NaN", Values.formatDouble(Double.NaN));
        assertEquals("-Infinity", Values.formatDouble(Double.NEGATIVE_INFINITY));
    }

    /** A heap page torn on disk is detected by its checksum, not read as garbage. */
    @Test
    void tornPagesAreDetected() throws Exception {
        try (Wal wal = Wal.open(dir.resolve("wal"), 0); BufferPool pool = new BufferPool(dir, 8, wal::flush)) {
            HeapFile heap = new HeapFile(3, pool, wal, () -> 0);
            Rid rid = heap.insert(5, new byte[] {1, 2, 3});
            assertEquals(5, heap.read(rid).xmin());
            pool.flushAll();
        }
        try (RandomAccessFile f = new RandomAccessFile(BufferPool.path(dir, 3).toFile(), "rw")) {
            f.seek(Page.SIZE - 10);
            f.write(new byte[] {9, 9, 9}); // flip some bytes in the tuple area
        }
        try (Wal wal = Wal.open(dir.resolve("wal2"), 0); BufferPool pool = new BufferPool(dir, 8, wal::flush)) {
            HeapFile heap = new HeapFile(3, pool, wal, () -> 0);
            SqlException e = assertThrows(SqlException.class, () -> heap.read(new Rid(0, 0)));
            assertEquals(SqlException.DATA_CORRUPTED, e.sqlState());
            assertNull(heap.read(new Rid(1, 0)), "past the end");
        }
    }
}
