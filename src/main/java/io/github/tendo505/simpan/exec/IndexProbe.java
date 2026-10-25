package io.github.tendo505.simpan.exec;

import io.github.tendo505.simpan.types.KeyCodec;
import io.github.tendo505.simpan.types.Type;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One contiguous stretch of an index: equal values for its first columns,
 * then optionally a range on the next column. {@code col IN (1, 5)} becomes
 * two probes. Values are expressions, so parameters work; they're turned
 * into key bytes when the scan starts.
 */
public record IndexProbe(List<Expr> equal, Expr low, boolean lowInclusive, Expr high, boolean highInclusive) {

    /** Key bounds {@code [from, to)}; null means open. */
    public record Range(byte[] from, byte[] to) {}

    private static final Object NO_MATCH = new Object();
    private static final Object UNBOUNDED = new Object();

    /** The byte range to scan, or null if no row can match (a NULL, or 2.5 for an integer column). */
    public Range range(ExecContext ctx, List<Type> columnTypes) {
        Object[] eq = new Object[equal.size()];
        for (int i = 0; i < eq.length; i++) {
            Object v = toColumn(equal.get(i).eval(null, ctx), columnTypes.get(i), 0);
            if (v == NO_MATCH) return null;
            eq[i] = v;
        }
        byte[] prefix = KeyCodec.encode(eq);
        if (low == null && high == null) return eq.length == 0 ? new Range(null, null) : new Range(prefix, KeyCodec.prefixEnd(prefix));
        Type rangeType = columnTypes.get(eq.length);
        boolean loInc = lowInclusive;
        boolean hiInc = highInclusive;
        Object lo = low == null ? UNBOUNDED : low.eval(null, ctx);
        Object hi = high == null ? UNBOUNDED : high.eval(null, ctx);
        if (lo == null || hi == null) return null;
        if (lo != UNBOUNDED) {
            Object c = toColumn(lo, rangeType, +1);
            if (c == NO_MATCH) return null;
            if (c != UNBOUNDED && !exact(lo, rangeType)) loInc = true; // > 2.5 becomes >= 3
            lo = c;
        }
        if (hi != UNBOUNDED) {
            Object c = toColumn(hi, rangeType, -1);
            if (c == NO_MATCH) return null;
            if (c != UNBOUNDED && !exact(hi, rangeType)) hiInc = true;
            hi = c;
        }
        byte[] from;
        if (lo == UNBOUNDED) {
            from = prefix;
        } else {
            byte[] k = append(prefix, lo);
            from = loInc ? k : KeyCodec.prefixEnd(k);
        }
        byte[] to;
        if (hi == UNBOUNDED) {
            to = append(prefix, null); // NULLs sort last: stop before them, a range never includes NULL
        } else {
            byte[] k = append(prefix, hi);
            to = hiInc ? KeyCodec.prefixEnd(k) : k;
        }
        if (from != null && to != null && KeyCodec.compare(from, to) >= 0) return null;
        return new Range(from, to);
    }

    private static boolean exact(Object v, Type column) {
        return !(column == Type.BIGINT && v instanceof Double d && d != Math.rint(d));
    }

    private static byte[] append(byte[] prefix, Object v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(prefix);
        KeyCodec.encode(out, v);
        return out.toByteArray();
    }

    /**
     * Converts a bound to the column's type. For an integer column a
     * fractional bound rounds inward ({@code > 2.5} is {@code >= 3}); an
     * equality with a fraction can't match. Bounds beyond the column's range
     * drop out or match nothing.
     */
    private static Object toColumn(Object v, Type column, int direction) {
        if (v == null) return NO_MATCH;
        if (column != Type.BIGINT) return column == Type.DOUBLE && v instanceof Long l ? (Object) (double) l : v;
        if (v instanceof Long) return v;
        if (!(v instanceof Double d)) return v;
        if (Double.isNaN(d)) return direction < 0 ? UNBOUNDED : NO_MATCH; // NaN sorts above every number
        double r = direction > 0 ? Math.ceil(d) : direction < 0 ? Math.floor(d) : d;
        if (direction == 0 && r != Math.rint(r)) return NO_MATCH;
        if (r >= 0x1p63) return direction < 0 ? UNBOUNDED : NO_MATCH;
        if (r < -0x1p63) return direction > 0 ? UNBOUNDED : NO_MATCH;
        return (long) r;
    }

    public String sql(List<String> columns) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < equal.size(); i++) parts.add(columns.get(i) + " = " + equal.get(i).sql());
        String c = columns.size() > equal.size() ? columns.get(equal.size()) : "?";
        if (low != null) parts.add(c + (lowInclusive ? " >= " : " > ") + low.sql());
        if (high != null) parts.add(c + (highInclusive ? " <= " : " < ") + high.sql());
        return String.join(" AND ", parts);
    }
}
