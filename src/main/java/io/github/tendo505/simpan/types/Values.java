package io.github.tendo505.simpan.types;

import io.github.tendo505.simpan.SqlException;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * Operations on SQL values, following PostgreSQL: NULL in, NULL out;
 * integer overflow is an error, not a wrap-around; text compares by code
 * point (the "C" collation), which is also the order of its UTF-8 bytes in
 * an index.
 */
public final class Values {
    private Values() {}

    /** Compares two non-null values of comparable types (numbers with numbers, text with text, ...). */
    public static int compare(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) return Long.compare(x, y);
        if (a instanceof Number x && b instanceof Number y) return compareDoubles(x.doubleValue(), y.doubleValue());
        if (a instanceof String x && b instanceof String y) return compareText(x, y);
        if (a instanceof Boolean x && b instanceof Boolean y) return Boolean.compare(x, y);
        throw new SqlException(SqlException.DATATYPE_MISMATCH,
                "cannot compare " + Type.of(a).sqlName() + " with " + Type.of(b).sqlName());
    }

    /** PostgreSQL orders NaN above every other number and treats it as equal to itself. */
    public static int compareDoubles(double x, double y) {
        if (Double.isNaN(x)) return Double.isNaN(y) ? 0 : 1;
        if (Double.isNaN(y)) return -1;
        return x < y ? -1 : x > y ? 1 : 0; // -0.0 equals 0.0
    }

    /** Code point order, the same as the order of the UTF-8 bytes. */
    public static int compareText(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) return Integer.compare(ca, cb);
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** For grouping, DISTINCT and hash joins: equal values must hash alike, so 1 and 1.0 share a key. */
    public static Object hashKey(Object v) {
        if (v instanceof Double d) {
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 0x1p63) return (long) (double) d;
            return d == 0.0 ? 0.0 : d; // fold -0.0 into 0.0
        }
        return v;
    }

    // ---------------- arithmetic ----------------

    public static Object add(Object a, Object b) {
        if (a == null || b == null) return null;
        if (a instanceof Long x && b instanceof Long y) {
            try {
                return Math.addExact(x, y);
            } catch (ArithmeticException e) {
                throw outOfRange();
            }
        }
        return num(a) + num(b);
    }

    public static Object subtract(Object a, Object b) {
        if (a == null || b == null) return null;
        if (a instanceof Long x && b instanceof Long y) {
            try {
                return Math.subtractExact(x, y);
            } catch (ArithmeticException e) {
                throw outOfRange();
            }
        }
        return num(a) - num(b);
    }

    public static Object multiply(Object a, Object b) {
        if (a == null || b == null) return null;
        if (a instanceof Long x && b instanceof Long y) {
            try {
                return Math.multiplyExact(x, y);
            } catch (ArithmeticException e) {
                throw outOfRange();
            }
        }
        return num(a) * num(b);
    }

    /** Integer division truncates toward zero, as in PostgreSQL (and Java). */
    public static Object divide(Object a, Object b) {
        if (a == null || b == null) return null;
        if (a instanceof Long x && b instanceof Long y) {
            if (y == 0) throw divisionByZero();
            if (x == Long.MIN_VALUE && y == -1) throw outOfRange();
            return x / y;
        }
        double d = num(b);
        if (d == 0.0) throw divisionByZero();
        return num(a) / d;
    }

    public static Object modulo(Object a, Object b) {
        if (a == null || b == null) return null;
        if (a instanceof Long x && b instanceof Long y) {
            if (y == 0) throw divisionByZero();
            if (y == -1) return 0L;
            return x % y;
        }
        double d = num(b);
        if (d == 0.0) throw divisionByZero();
        return num(a) % d;
    }

    public static Object negate(Object a) {
        if (a == null) return null;
        if (a instanceof Long x) {
            if (x == Long.MIN_VALUE) throw outOfRange();
            return -x;
        }
        return -num(a);
    }

    private static double num(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        throw new SqlException(SqlException.DATATYPE_MISMATCH, "expected a number, got " + Type.of(v).sqlName());
    }

    public static SqlException outOfRange() {
        return new SqlException(SqlException.OUT_OF_RANGE, "bigint out of range");
    }

    public static SqlException divisionByZero() {
        return new SqlException(SqlException.DIVISION_BY_ZERO, "division by zero");
    }

    // ---------------- casts ----------------

    /** Converts a value to a type, as {@code CAST(v AS type)} does. */
    public static Object cast(Object v, Type to) {
        if (v == null || to == Type.UNKNOWN) return v;
        Type from = Type.of(v);
        if (from == to) return v;
        return switch (to) {
            case BIGINT -> switch (v) {
                case Double d -> doubleToLong(d);
                case String s -> parseLong(s);
                case Boolean b -> b ? 1L : 0L;
                default -> throw cannotCast(from, to);
            };
            case DOUBLE -> switch (v) {
                case Long l -> (double) l;
                case String s -> parseDouble(s);
                default -> throw cannotCast(from, to);
            };
            case TEXT -> v instanceof Boolean b ? (b ? "true" : "false") : format(v);
            case BOOLEAN -> switch (v) {
                case String s -> parseBoolean(s);
                case Long l -> l != 0;
                default -> throw cannotCast(from, to);
            };
            case UNKNOWN -> v;
        };
    }

    /** True if every value of {@code from} can be stored in a column of type {@code to} without asking. */
    public static boolean assignable(Type from, Type to) {
        return from == to || from == Type.UNKNOWN || (from == Type.BIGINT && to == Type.DOUBLE);
    }

    /** Converts a value for storing in a column: numbers convert, text into a number column must be valid. */
    public static Object coerceForColumn(Object v, Type column, String columnName) {
        if (v == null) return null;
        Type from = Type.of(v);
        if (from == column) return v;
        if (from.isNumeric() && column.isNumeric()) return cast(v, column);
        if (from == Type.TEXT) return cast(v, column); // like an untyped string literal in PostgreSQL
        throw new SqlException(SqlException.DATATYPE_MISMATCH, "column \"" + columnName + "\" is of type " + column.sqlName()
                + " but expression is of type " + from.sqlName());
    }

    private static long doubleToLong(double d) {
        double r = Math.rint(d); // PostgreSQL rounds float to int half-to-even
        if (Double.isNaN(r) || r < -0x1p63 || r >= 0x1p63) throw outOfRange();
        return (long) r;
    }

    public static long parseLong(String s) {
        String t = s.trim();
        try {
            return Long.parseLong(t.startsWith("+") ? t.substring(1) : t);
        } catch (NumberFormatException e) {
            if (t.matches("[+-]?\\d+")) throw outOfRange();
            throw new SqlException(SqlException.INVALID_TEXT, "invalid input syntax for type bigint: \"" + s + "\"");
        }
    }

    public static double parseDouble(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "nan":
                return Double.NaN;
            case "infinity", "+infinity", "inf", "+inf":
                return Double.POSITIVE_INFINITY;
            case "-infinity", "-inf":
                return Double.NEGATIVE_INFINITY;
            default:
                break;
        }
        if (!t.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)(e[+-]?\\d+)?")) {
            throw new SqlException(SqlException.INVALID_TEXT, "invalid input syntax for type double precision: \"" + s + "\"");
        }
        return Double.parseDouble(t);
    }

    public static boolean parseBoolean(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "t", "true", "y", "yes", "on", "1" -> true;
            case "f", "false", "n", "no", "off", "0" -> false;
            default -> throw new SqlException(SqlException.INVALID_TEXT, "invalid input syntax for type boolean: \"" + s + "\"");
        };
    }

    private static SqlException cannotCast(Type from, Type to) {
        return new SqlException(SqlException.CANNOT_COERCE, "cannot cast type " + from.sqlName() + " to " + to.sqlName());
    }

    // ---------------- output ----------------

    /** The text form PostgreSQL sends to clients: {@code t}/{@code f}, shortest round-trip floats, ... */
    public static String format(Object v) {
        return switch (v) {
            case null -> null;
            case Boolean b -> b ? "t" : "f";
            case Double d -> formatDouble(d);
            default -> v.toString();
        };
    }

    /** Like PostgreSQL 12+: the shortest digits that read back exactly, in plain or {@code 1.5e+20} form. */
    public static String formatDouble(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        if (d == 0) return (1 / d < 0) ? "-0" : "0";
        BigDecimal shortest = new BigDecimal(Double.toString(d)).stripTrailingZeros(); // Java 19+ prints the shortest digits
        String digits = shortest.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - shortest.scale(); // d = 0.digits * 10^(exponent+1)
        String sign = d < 0 ? "-" : "";
        if (exponent < -4 || exponent >= 15) {
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + String.format(Locale.ROOT, "%02d", Math.abs(exponent));
        }
        return sign + shortest.abs().toPlainString();
    }
}
