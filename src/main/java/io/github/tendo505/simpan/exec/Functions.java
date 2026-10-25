package io.github.tendo505.simpan.exec;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.types.Type;
import io.github.tendo505.simpan.types.Values;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** The scalar functions, with their argument and result types. Most return NULL for a NULL argument. */
public final class Functions {
    private Functions() {}

    @FunctionalInterface
    public interface Impl {
        Object apply(Object[] args);
    }

    /** A resolved function: what it returns and the argument types to cast its inputs to. */
    public record Resolved(Type result, List<Type> argTypes, Impl impl) {}

    public static boolean isAggregate(String name) {
        return switch (name) {
            case "count", "sum", "avg", "min", "max", "bool_and", "bool_or", "every", "string_agg" -> true;
            default -> false;
        };
    }

    /** Finds the function for these argument types, or fails like PostgreSQL does. */
    public static Resolved resolve(String name, List<Type> args) {
        int n = args.size();
        Type a0 = n > 0 ? args.get(0) : null;
        return switch (name) {
            case "abs" -> {
                arity(name, n, 1, 1);
                Type t = numeric(name, a0);
                yield new Resolved(t, List.of(t), strict(a -> a[0] instanceof Long l ? absExact(l) : Math.abs((Double) a[0])));
            }
            case "lower", "upper" -> {
                arity(name, n, 1, 1);
                text(name, a0);
                boolean upper = name.equals("upper");
                yield new Resolved(Type.TEXT, List.of(Type.TEXT), strict(a -> upper
                        ? ((String) a[0]).toUpperCase(Locale.ROOT) : ((String) a[0]).toLowerCase(Locale.ROOT)));
            }
            case "length", "char_length", "character_length" -> {
                arity(name, n, 1, 1);
                text(name, a0);
                yield new Resolved(Type.BIGINT, List.of(Type.TEXT), strict(a -> (long) ((String) a[0]).codePointCount(0, ((String) a[0]).length())));
            }
            case "coalesce" -> {
                arity(name, n, 1, 100);
                Type t = common(name, args);
                yield new Resolved(t, repeat(t, n), a -> {
                    for (Object v : a) if (v != null) return v;
                    return null;
                });
            }
            case "nullif" -> {
                arity(name, n, 2, 2);
                Type t = common(name, args);
                yield new Resolved(t, List.of(t, t), a -> a[0] != null && a[1] != null && Values.compare(a[0], a[1]) == 0 ? null : a[0]);
            }
            case "greatest", "least" -> {
                arity(name, n, 1, 100);
                Type t = common(name, args);
                boolean greatest = name.equals("greatest");
                yield new Resolved(t, repeat(t, n), a -> {
                    Object best = null; // NULLs are skipped, as in PostgreSQL
                    for (Object v : a) {
                        if (v != null && (best == null || (Values.compare(v, best) > 0) == greatest && Values.compare(v, best) != 0)) best = v;
                    }
                    return best;
                });
            }
            case "round" -> {
                arity(name, n, 1, 2);
                numeric(name, a0);
                if (n == 1) yield new Resolved(Type.DOUBLE, List.of(Type.DOUBLE), strict(a -> Math.rint((Double) a[0])));
                if (!args.get(1).isNumeric() && args.get(1) != Type.UNKNOWN) throw noSuch(name, args);
                yield new Resolved(Type.DOUBLE, List.of(Type.DOUBLE, Type.BIGINT), strict(a -> {
                    double d = (Double) a[0];
                    if (Double.isNaN(d) || Double.isInfinite(d)) return d;
                    return BigDecimal.valueOf(d).setScale((int) (long) (Long) a[1], RoundingMode.HALF_UP).doubleValue();
                }));
            }
            case "floor", "ceil", "ceiling", "sqrt", "exp", "ln" -> {
                arity(name, n, 1, 1);
                numeric(name, a0);
                yield new Resolved(Type.DOUBLE, List.of(Type.DOUBLE), strict(a -> {
                    double d = (Double) a[0];
                    return switch (name) {
                        case "floor" -> Math.floor(d);
                        case "sqrt" -> {
                            if (d < 0) throw new SqlException("2201F", "cannot take square root of a negative number");
                            yield Math.sqrt(d);
                        }
                        case "exp" -> Math.exp(d);
                        case "ln" -> {
                            if (d <= 0) throw new SqlException("2201E", d == 0 ? "cannot take logarithm of zero" : "cannot take logarithm of a negative number");
                            yield Math.log(d);
                        }
                        default -> Math.ceil(d);
                    };
                }));
            }
            case "power", "pow" -> {
                arity(name, n, 2, 2);
                numeric(name, a0);
                numeric(name, args.get(1));
                yield new Resolved(Type.DOUBLE, List.of(Type.DOUBLE, Type.DOUBLE), strict(a -> Math.pow((Double) a[0], (Double) a[1])));
            }
            case "mod" -> {
                arity(name, n, 2, 2);
                Type t = common(name, args);
                if (!t.isNumeric()) throw noSuch(name, args);
                yield new Resolved(t, List.of(t, t), a -> Values.modulo(a[0], a[1]));
            }
            case "sign" -> {
                arity(name, n, 1, 1);
                Type t = numeric(name, a0);
                yield new Resolved(t, List.of(t), strict(a -> a[0] instanceof Long l ? (long) Long.signum(l) : Math.signum((Double) a[0])));
            }
            case "substr", "substring" -> {
                arity(name, n, 2, 3);
                text(name, a0);
                yield new Resolved(Type.TEXT, n == 2 ? List.of(Type.TEXT, Type.BIGINT) : List.of(Type.TEXT, Type.BIGINT, Type.BIGINT),
                        strict(a -> substring((String) a[0], (Long) a[1], a.length > 2 ? (Long) a[2] : null)));
            }
            case "trim", "btrim", "ltrim", "rtrim" -> {
                arity(name, n, 1, 1);
                text(name, a0);
                yield new Resolved(Type.TEXT, List.of(Type.TEXT), strict(a -> {
                    String s = (String) a[0];
                    return switch (name) {
                        case "ltrim" -> s.replaceAll("^ +", "");
                        case "rtrim" -> s.replaceAll(" +$", "");
                        default -> s.replaceAll("^ +| +$", "");
                    };
                }));
            }
            case "replace" -> {
                arity(name, n, 3, 3);
                yield new Resolved(Type.TEXT, List.of(Type.TEXT, Type.TEXT, Type.TEXT), strict(a -> ((String) a[1]).isEmpty()
                        ? a[0] : ((String) a[0]).replace((String) a[1], (String) a[2])));
            }
            case "concat" -> {
                arity(name, n, 1, 100);
                yield new Resolved(Type.TEXT, repeat(Type.UNKNOWN, n), a -> {
                    StringBuilder sb = new StringBuilder();
                    for (Object v : a) if (v != null) sb.append(Values.format(v)); // concat() skips NULLs
                    return sb.toString();
                });
            }
            case "left", "right" -> {
                arity(name, n, 2, 2);
                text(name, a0);
                boolean left = name.equals("left");
                yield new Resolved(Type.TEXT, List.of(Type.TEXT, Type.BIGINT), strict(a -> {
                    int[] cps = ((String) a[0]).codePoints().toArray();
                    long k = (Long) a[1];
                    int take = (int) (k >= 0 ? Math.min(k, cps.length) : Math.max(0, cps.length + k));
                    return left ? new String(cps, 0, take) : new String(cps, cps.length - take, take);
                }));
            }
            case "reverse" -> {
                arity(name, n, 1, 1);
                yield new Resolved(Type.TEXT, List.of(Type.TEXT), strict(a -> new StringBuilder((String) a[0]).reverse().toString()));
            }
            case "repeat" -> {
                arity(name, n, 2, 2);
                yield new Resolved(Type.TEXT, List.of(Type.TEXT, Type.BIGINT), strict(a -> {
                    long times = (Long) a[1];
                    if (times <= 0) return "";
                    int len = ((String) a[0]).length();
                    if (len > 0 && times > 10_000_000L / len) throw new SqlException(SqlException.PROGRAM_LIMIT, "requested length too large");
                    return ((String) a[0]).repeat((int) times);
                }));
            }
            case "strpos" -> {
                arity(name, n, 2, 2);
                yield new Resolved(Type.BIGINT, List.of(Type.TEXT, Type.TEXT), strict(a -> {
                    String s = (String) a[0];
                    int i = s.indexOf((String) a[1]);
                    return i < 0 ? 0L : (long) s.codePointCount(0, i) + 1;
                }));
            }
            case "random" -> {
                arity(name, n, 0, 0);
                yield new Resolved(Type.DOUBLE, List.of(), a -> ThreadLocalRandom.current().nextDouble());
            }
            case "version" -> {
                arity(name, n, 0, 0);
                yield new Resolved(Type.TEXT, List.of(), a -> "PostgreSQL 16.0 (Simpan 1.0.0, a database written in Java)");
            }
            case "current_database", "current_schema", "current_user", "session_user" -> {
                arity(name, n, 0, 0);
                yield new Resolved(Type.TEXT, List.of(), a -> name.equals("current_schema") ? "public" : "simpan");
            }
            default -> throw noSuch(name, args);
        };
    }

    private static Impl strict(Impl f) {
        return a -> {
            for (Object v : a) if (v == null) return null;
            return f.apply(a);
        };
    }

    private static long absExact(long l) {
        if (l == Long.MIN_VALUE) throw Values.outOfRange();
        return Math.abs(l);
    }

    /** PostgreSQL's substring: 1-based, and a start before 1 eats into the length. */
    private static String substring(String s, long start, Long length) {
        int[] cps = s.codePoints().toArray();
        if (length != null && length < 0) throw new SqlException("22011", "negative substring length not allowed");
        long from = Math.max(start, 1);
        long to = length == null ? cps.length + 1L : Math.min(start + length, cps.length + 1L);
        if (to <= from) return "";
        return new String(cps, (int) from - 1, (int) Math.min(to - from, cps.length - from + 1));
    }

    private static List<Type> repeat(Type t, int n) {
        return java.util.Collections.nCopies(n, t);
    }

    private static void arity(String name, int n, int min, int max) {
        if (n < min || n > max) {
            throw new SqlException(SqlException.UNDEFINED_FUNCTION, "function " + name + " does not take " + n + " argument" + (n == 1 ? "" : "s"));
        }
    }

    private static Type numeric(String name, Type t) {
        if (t == Type.UNKNOWN) return Type.DOUBLE;
        if (!t.isNumeric()) throw new SqlException(SqlException.UNDEFINED_FUNCTION, "function " + name + "(" + t.sqlName() + ") does not exist");
        return t;
    }

    private static void text(String name, Type t) {
        if (t != Type.TEXT && t != Type.UNKNOWN) throw new SqlException(SqlException.UNDEFINED_FUNCTION, "function " + name + "(" + t.sqlName() + ") does not exist");
    }

    /** The type several values share: numbers widen to double precision; anything else must match. */
    public static Type common(String what, List<Type> types) {
        Type out = Type.UNKNOWN;
        for (Type t : types) {
            if (t == Type.UNKNOWN || t == out) continue;
            if (out == Type.UNKNOWN) {
                out = t;
            } else if (out.isNumeric() && t.isNumeric()) {
                out = Type.DOUBLE;
            } else {
                throw new SqlException(SqlException.DATATYPE_MISMATCH, what + " types " + out.sqlName() + " and " + t.sqlName() + " cannot be matched");
            }
        }
        return out == Type.UNKNOWN ? Type.TEXT : out;
    }

    private static SqlException noSuch(String name, List<Type> args) {
        StringBuilder sb = new StringBuilder();
        for (Type t : args) sb.append(sb.isEmpty() ? "" : ", ").append(t.sqlName());
        return new SqlException(SqlException.UNDEFINED_FUNCTION, "function " + name + "(" + sb + ") does not exist");
    }
}
