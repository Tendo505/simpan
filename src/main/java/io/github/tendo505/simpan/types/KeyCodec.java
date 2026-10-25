package io.github.tendo505.simpan.types;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Encodes index keys as bytes whose unsigned lexicographic order is the SQL
 * order of the values, so the B+tree only ever compares byte arrays.
 *
 * <p>Each value is a tag (NULL sorts last, like PostgreSQL's default) and
 * then: a big-endian long with the sign bit flipped; a double's bits,
 * flipped so negatives sort first; or UTF-8 text with 0x00 escaped as
 * 0x00 0xFF and ended by 0x00 0x00, so a prefix sorts before longer text.
 */
public final class KeyCodec {
    private static final int NOT_NULL = 0x01;
    private static final int NULL = 0x02;

    private KeyCodec() {}

    public static byte[] encode(Object[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        for (Object v : values) encode(out, v);
        return out.toByteArray();
    }

    public static void encode(ByteArrayOutputStream out, Object v) {
        if (v == null) {
            out.write(NULL);
            return;
        }
        out.write(NOT_NULL);
        switch (v) {
            case Long l -> writeLong(out, l ^ Long.MIN_VALUE);
            case Double d -> {
                double x = d == 0.0 ? 0.0 : Double.isNaN(d) ? Double.NaN : d; // one zero, one NaN
                long bits = Double.doubleToLongBits(x);
                writeLong(out, bits < 0 ? ~bits : bits ^ Long.MIN_VALUE);
            }
            case String s -> {
                for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
                    out.write(b);
                    if (b == 0) out.write(0xFF);
                }
                out.write(0);
                out.write(0);
            }
            case Boolean b -> out.write(b ? 1 : 0);
            default -> throw new IllegalArgumentException("not a SQL value: " + v.getClass());
        }
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        for (int i = 7; i >= 0; i--) out.write((int) (v >>> (i * 8)));
    }

    /** Unsigned lexicographic comparison. */
    public static int compare(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    public static int compare(byte[] a, int aFrom, int aTo, byte[] b, int bFrom, int bTo) {
        return Arrays.compareUnsigned(a, aFrom, aTo, b, bFrom, bTo);
    }

    /** The smallest key greater than every key that starts with {@code prefix}, or null if there is none. */
    public static byte[] prefixEnd(byte[] prefix) {
        byte[] end = prefix.clone();
        for (int i = end.length - 1; i >= 0; i--) {
            if (end[i] != (byte) 0xFF) {
                end[i]++;
                return Arrays.copyOf(end, i + 1);
            }
        }
        return null;
    }
}
