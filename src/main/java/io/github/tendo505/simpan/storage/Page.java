package io.github.tendo505.simpan.storage;

import java.util.zip.CRC32C;

/**
 * The 16-byte header every page starts with:
 * <pre>
 *   0  long  LSN of the last WAL record that changed this page
 *   8  byte  page type
 *  12  int   CRC32C of the whole page, computed with this field zeroed
 * </pre>
 * Multi-byte values are big-endian.
 */
public final class Page {
    public static final int SIZE = 8192;
    public static final int HEADER = 16;

    public static final byte FREE = 0;
    public static final byte HEAP = 1;
    public static final byte BTREE_LEAF = 2;
    public static final byte BTREE_INNER = 3;
    public static final byte BTREE_META = 4;

    private Page() {}

    public static long lsn(byte[] p) {
        return getLong(p, 0);
    }

    public static void setLsn(byte[] p, long lsn) {
        putLong(p, 0, lsn);
    }

    public static byte type(byte[] p) {
        return p[8];
    }

    public static void setType(byte[] p, byte type) {
        p[8] = type;
    }

    /** Stamps the checksum just before the page is written. */
    public static void seal(byte[] p) {
        putInt(p, 12, 0);
        putInt(p, 12, checksum(p));
    }

    /** True if the page is all zeros (never written) or its checksum matches. */
    public static boolean verify(byte[] p) {
        int stored = getInt(p, 12);
        if (stored == 0 && isZero(p)) return true;
        putInt(p, 12, 0);
        int actual = checksum(p);
        putInt(p, 12, stored);
        return actual == stored;
    }

    private static int checksum(byte[] p) {
        CRC32C crc = new CRC32C();
        crc.update(p, 0, SIZE);
        int v = (int) crc.getValue();
        return v == 0 ? 1 : v; // 0 means "no checksum" (a zeroed page)
    }

    private static boolean isZero(byte[] p) {
        for (byte b : p) if (b != 0) return false;
        return true;
    }

    // ---------------- big-endian helpers ----------------

    public static int getU16(byte[] p, int at) {
        return ((p[at] & 0xFF) << 8) | (p[at + 1] & 0xFF);
    }

    public static void putU16(byte[] p, int at, int v) {
        p[at] = (byte) (v >>> 8);
        p[at + 1] = (byte) v;
    }

    public static int getInt(byte[] p, int at) {
        return ((p[at] & 0xFF) << 24) | ((p[at + 1] & 0xFF) << 16) | ((p[at + 2] & 0xFF) << 8) | (p[at + 3] & 0xFF);
    }

    public static void putInt(byte[] p, int at, int v) {
        p[at] = (byte) (v >>> 24);
        p[at + 1] = (byte) (v >>> 16);
        p[at + 2] = (byte) (v >>> 8);
        p[at + 3] = (byte) v;
    }

    public static long getLong(byte[] p, int at) {
        return ((long) getInt(p, at) << 32) | (getInt(p, at + 4) & 0xFFFFFFFFL);
    }

    public static void putLong(byte[] p, int at, long v) {
        putInt(p, at, (int) (v >>> 32));
        putInt(p, at + 4, (int) v);
    }
}
