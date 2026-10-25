package io.github.tendo505.simpan.types;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The on-disk form of a row: a null bitmap, then each non-null value
 * (8-byte longs and doubles, 1-byte booleans, length-prefixed UTF-8 text).
 * Decoding needs the column types, which the catalog keeps.
 */
public final class RowCodec {
    private RowCodec() {}

    public static byte[] encode(Object[] row, Type[] types) {
        int size = (row.length + 7) / 8;
        byte[][] texts = new byte[row.length][];
        for (int i = 0; i < row.length; i++) {
            Object v = row[i];
            if (v == null) continue;
            size += switch (types[i]) {
                case BIGINT, DOUBLE -> 8;
                case BOOLEAN -> 1;
                case TEXT -> {
                    texts[i] = ((String) v).getBytes(StandardCharsets.UTF_8);
                    yield varIntSize(texts[i].length) + texts[i].length;
                }
                case UNKNOWN -> throw new IllegalStateException("column without a type");
            };
        }
        ByteBuffer out = ByteBuffer.allocate(size);
        byte[] nulls = new byte[(row.length + 7) / 8];
        for (int i = 0; i < row.length; i++) if (row[i] == null) nulls[i / 8] |= (byte) (1 << (i % 8));
        out.put(nulls);
        for (int i = 0; i < row.length; i++) {
            Object v = row[i];
            if (v == null) continue;
            switch (types[i]) {
                case BIGINT -> out.putLong((Long) v);
                case DOUBLE -> out.putDouble((Double) v);
                case BOOLEAN -> out.put((byte) ((Boolean) v ? 1 : 0));
                case TEXT -> {
                    putVarInt(out, texts[i].length);
                    out.put(texts[i]);
                }
                case UNKNOWN -> throw new IllegalStateException();
            }
        }
        return out.array();
    }

    public static Object[] decode(byte[] bytes, int offset, int length, Type[] types) {
        ByteBuffer in = ByteBuffer.wrap(bytes, offset, length);
        int nullBytes = (types.length + 7) / 8;
        byte[] nulls = new byte[nullBytes];
        in.get(nulls);
        Object[] row = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if ((nulls[i / 8] & (1 << (i % 8))) != 0) continue;
            row[i] = switch (types[i]) {
                case BIGINT -> in.getLong();
                case DOUBLE -> in.getDouble();
                case BOOLEAN -> in.get() != 0;
                case TEXT -> {
                    int n = getVarInt(in);
                    String s = new String(bytes, in.position(), n, StandardCharsets.UTF_8);
                    in.position(in.position() + n);
                    yield s;
                }
                case UNKNOWN -> throw new IllegalStateException();
            };
        }
        return row;
    }

    private static int varIntSize(int v) {
        int n = 1;
        while ((v >>>= 7) != 0) n++;
        return n;
    }

    private static void putVarInt(ByteBuffer out, int v) {
        while ((v & ~0x7F) != 0) {
            out.put((byte) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.put((byte) v);
    }

    private static int getVarInt(ByteBuffer in) {
        int v = 0;
        for (int shift = 0; ; shift += 7) {
            byte b = in.get();
            v |= (b & 0x7F) << shift;
            if (b >= 0) return v;
        }
    }
}
