package io.github.tendo505.simpan.exec;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.engine.Table;
import io.github.tendo505.simpan.engine.Table.Index;
import io.github.tendo505.simpan.sql.Ast.JoinKind;
import io.github.tendo505.simpan.sql.Ast.SetOpKind;
import io.github.tendo505.simpan.types.Type;
import io.github.tendo505.simpan.types.Values;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The plan nodes. */
public final class Operators {
    private Operators() {}

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static List<Type> concatTypes(List<Type> a, List<Type> b) {
        List<Type> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static Object[] join(Object[] l, Object[] r, int rightWidth) {
        Object[] out = Arrays.copyOf(l, l.length + rightWidth);
        if (r != null) System.arraycopy(r, 0, out, l.length, rightWidth);
        return out;
    }

    private static Object[] nullsThen(int leftWidth, Object[] r) {
        Object[] out = new Object[leftWidth + r.length];
        System.arraycopy(r, 0, out, leftWidth, r.length);
        return out;
    }

    /** Values made comparable for hashing: 1 and 1.0 are the same key. */
    static List<Object> hashKey(Object[] row) {
        Object[] k = new Object[row.length];
        for (int i = 0; i < row.length; i++) k[i] = Values.hashKey(row[i]);
        return Arrays.asList(k);
    }

    private static String relation(Table t, String alias) {
        String name = t.def().name();
        return alias == null || alias.equals(name) ? name : name + " " + alias;
    }

    // ---------------- scans ----------------

    public static final class SeqScan extends Operator {
        private final Table table;
        private final String alias;
        private final Expr filter;
        private Iterator<Table.Row> rows;

        public SeqScan(Table table, String alias, Expr filter, List<String> names) {
            super(names, Arrays.asList(table.types()));
            this.table = table;
            this.alias = alias;
            this.filter = filter;
        }

        protected void doOpen() {
            rows = table.scan(ctx.snapshot());
        }

        protected Object[] doNext() {
            while (rows.hasNext()) {
                Object[] r = rows.next().values();
                if (filter == null || Expr.isTrue(filter.eval(r, ctx))) return r;
            }
            return null;
        }

        public String label() {
            return "Seq Scan on " + relation(table, alias);
        }

        public List<String> details() {
            return filter == null ? List.of() : List.of("Filter: " + filter.sql());
        }
    }

    public static final class IndexScan extends Operator {
        private final Table table;
        private final String alias;
        private final Index index;
        private final List<IndexProbe> probes;
        private final Expr filter;
        private Iterator<IndexProbe.Range> ranges;
        private Iterator<Table.Row> rows = Collections.emptyIterator();

        public IndexScan(Table table, String alias, Index index, List<IndexProbe> probes, Expr filter, List<String> names) {
            super(names, Arrays.asList(table.types()));
            this.table = table;
            this.alias = alias;
            this.index = index;
            this.probes = probes;
            this.filter = filter;
        }

        protected void doOpen() {
            ranges = ranges(ctx, index, table, probes).iterator();
            rows = Collections.emptyIterator();
        }

        protected Object[] doNext() {
            while (true) {
                while (!rows.hasNext()) {
                    if (!ranges.hasNext()) return null;
                    IndexProbe.Range r = ranges.next();
                    rows = table.indexScan(index, r.from(), r.to(), ctx.snapshot());
                }
                Object[] row = rows.next().values();
                if (filter == null || Expr.isTrue(filter.eval(row, ctx))) return row;
            }
        }

        public String label() {
            return "Index Scan using " + index.def().name() + " on " + relation(table, alias);
        }

        public List<String> details() {
            List<String> out = new ArrayList<>();
            out.add("Index Cond: " + describe(index, table, probes));
            if (filter != null) out.add("Filter: " + filter.sql());
            return out;
        }
    }

    /** The distinct key ranges for a set of probes, in key order, so overlapping IN values don't return a row twice. */
    static List<IndexProbe.Range> ranges(ExecContext ctx, Index index, Table table, List<IndexProbe> probes) {
        List<Type> types = new ArrayList<>();
        for (int c : index.def().columns()) types.add(table.types()[c]);
        Map<String, IndexProbe.Range> distinct = new java.util.TreeMap<>();
        for (IndexProbe p : probes) {
            IndexProbe.Range r = p.range(ctx, types);
            if (r != null) distinct.putIfAbsent(Arrays.toString(r.from()) + "|" + Arrays.toString(r.to()), r);
        }
        List<IndexProbe.Range> out = new ArrayList<>(distinct.values());
        out.sort((a, b) -> a.from() == null ? -1 : b.from() == null ? 1 : io.github.tendo505.simpan.types.KeyCodec.compare(a.from(), b.from()));
        return out;
    }

    static String describe(Index index, Table table, List<IndexProbe> probes) {
        List<String> cols = new ArrayList<>();
        for (int c : index.def().columns()) cols.add(table.def().columns().get(c).name());
        List<String> parts = new ArrayList<>();
        for (IndexProbe p : probes) parts.add(p.sql(cols));
        return parts.size() == 1 ? "(" + parts.get(0) + ")" : "(" + String.join(") OR (", parts) + ")";
    }

    /** Rows of constant expressions; one empty row for a SELECT without FROM. */
    public static final class ValuesScan extends Operator {
        private final List<List<Expr>> rows;
        private int i;

        public ValuesScan(List<List<Expr>> rows, List<String> names, List<Type> types) {
            super(names, types);
            this.rows = rows;
        }

        protected void doOpen() {
            i = 0;
        }

        protected Object[] doNext() {
            if (i >= rows.size()) return null;
            List<Expr> exprs = rows.get(i++);
            Object[] out = new Object[exprs.size()];
            for (int k = 0; k < out.length; k++) out[k] = Values.cast(exprs.get(k).eval(new Object[0], ctx), types().get(k));
            return out;
        }

        public String label() {
            return rows.size() == 1 && rows.get(0).isEmpty() ? "Result" : "Values Scan";
        }
    }

    // ---------------- row-at-a-time ----------------

    public static final class Filter extends Operator {
        private final Operator child;
        private final Expr cond;

        public Filter(Operator child, Expr cond) {
            super(child.names(), child.types());
            this.child = child;
            this.cond = cond;
        }

        protected void doOpen() {
            child.open(ctx);
        }

        protected Object[] doNext() {
            for (Object[] r = child.next(); r != null; r = child.next()) if (Expr.isTrue(cond.eval(r, ctx))) return r;
            return null;
        }

        public String label() {
            return "Filter";
        }

        public List<String> details() {
            return List.of("Filter: " + cond.sql());
        }

        public List<Operator> children() {
            return List.of(child);
        }
    }

    public static final class Project extends Operator {
        private final Operator child;
        private final List<Expr> exprs;

        public Project(Operator child, List<Expr> exprs, List<String> names) {
            super(names, exprs.stream().map(Expr::type).toList());
            this.child = child;
            this.exprs = exprs;
        }

        protected void doOpen() {
            child.open(ctx);
        }

        protected Object[] doNext() {
            Object[] r = child.next();
            if (r == null) return null;
            Object[] out = new Object[exprs.size()];
            for (int i = 0; i < out.length; i++) out[i] = exprs.get(i).eval(r, ctx);
            return out;
        }

        public String label() {
            return "Project";
        }

        @Override
        protected boolean hiddenInExplain() {
            return true;
        }

        public List<String> details() {
            List<String> items = new ArrayList<>();
            for (Expr e : exprs) items.add(e.sql());
            return List.of("Output: " + String.join(", ", items));
        }

        public List<Operator> children() {
            return List.of(child);
        }
    }

    // ---------------- joins ----------------

    private static String joinName(String base, JoinKind kind) {
        return switch (kind) {
            case LEFT -> base + " Left Join";
            case RIGHT -> base + " Right Join";
            case FULL -> base + " Full Join";
            default -> base;
        };
    }

    /** Compares every left row with every right row; the right side is read once and kept in memory. */
    public static final class NestedLoopJoin extends Operator {
        private final JoinKind kind;
        private final Operator left;
        private final Operator right;
        private final Expr cond;
        private List<Object[]> inner;
        private BitSet innerMatched;
        private Object[] outer;
        private boolean outerMatched;
        private int pos;
        private Iterator<Object[]> unmatched;

        public NestedLoopJoin(JoinKind kind, Operator left, Operator right, Expr cond) {
            super(concat(left.names(), right.names()), concatTypes(left.types(), right.types()));
            this.kind = kind;
            this.left = left;
            this.right = right;
            this.cond = cond;
        }

        protected void doOpen() {
            right.open(ctx);
            inner = new ArrayList<>();
            for (Object[] r = right.next(); r != null; r = right.next()) inner.add(r);
            innerMatched = new BitSet(inner.size());
            left.open(ctx);
            outer = null;
            unmatched = null;
        }

        protected Object[] doNext() {
            while (true) {
                if (unmatched != null) return unmatched.hasNext() ? unmatched.next() : null;
                if (outer == null) {
                    outer = left.next();
                    if (outer == null) {
                        if (kind != JoinKind.RIGHT && kind != JoinKind.FULL) return null;
                        List<Object[]> rest = new ArrayList<>();
                        for (int i = innerMatched.nextClearBit(0); i < inner.size(); i = innerMatched.nextClearBit(i + 1)) {
                            rest.add(nullsThen(left.width(), inner.get(i)));
                        }
                        unmatched = rest.iterator();
                        continue;
                    }
                    outerMatched = false;
                    pos = 0;
                }
                while (pos < inner.size()) {
                    Object[] candidate = join(outer, inner.get(pos), right.width());
                    int at = pos++;
                    if (cond == null || Expr.isTrue(cond.eval(candidate, ctx))) {
                        outerMatched = true;
                        innerMatched.set(at);
                        return candidate;
                    }
                }
                Object[] o = outer;
                outer = null;
                if (!outerMatched && (kind == JoinKind.LEFT || kind == JoinKind.FULL)) return join(o, null, right.width());
            }
        }

        public String label() {
            return joinName("Nested Loop", kind);
        }

        public List<String> details() {
            return cond == null ? List.of() : List.of("Join Filter: " + cond.sql());
        }

        public List<Operator> children() {
            return List.of(left, right);
        }
    }

    /**
     * Builds a hash table from the right input on the equality keys, then
     * streams the left input through it. A NULL key never matches.
     */
    public static final class HashJoin extends Operator {
        private final JoinKind kind;
        private final Operator left;
        private final Operator right;
        private final List<Expr> leftKeys;
        private final List<Expr> rightKeys;
        private final Expr residual;
        private Map<List<Object>, List<Integer>> table;
        private List<Object[]> buildRows;
        private BitSet matched;
        private Object[] probe;
        private List<Integer> candidates;
        private int pos;
        private boolean probeMatched;
        private Iterator<Object[]> unmatched;

        public HashJoin(JoinKind kind, Operator left, Operator right, List<Expr> leftKeys, List<Expr> rightKeys, Expr residual) {
            super(concat(left.names(), right.names()), concatTypes(left.types(), right.types()));
            this.kind = kind;
            this.left = left;
            this.right = right;
            this.leftKeys = leftKeys;
            this.rightKeys = rightKeys;
            this.residual = residual;
        }

        private List<Object> key(Object[] row, List<Expr> exprs) {
            Object[] k = new Object[exprs.size()];
            for (int i = 0; i < k.length; i++) {
                Object v = exprs.get(i).eval(row, ctx);
                if (v == null) return null;
                k[i] = Values.hashKey(v);
            }
            return Arrays.asList(k);
        }

        protected void doOpen() {
            table = new HashMap<>();
            buildRows = new ArrayList<>();
            right.open(ctx);
            for (Object[] r = right.next(); r != null; r = right.next()) {
                buildRows.add(r);
                List<Object> k = key(r, rightKeys);
                if (k != null) table.computeIfAbsent(k, x -> new ArrayList<>()).add(buildRows.size() - 1);
            }
            matched = new BitSet(buildRows.size());
            left.open(ctx);
            probe = null;
            unmatched = null;
        }

        protected Object[] doNext() {
            while (true) {
                if (unmatched != null) return unmatched.hasNext() ? unmatched.next() : null;
                if (probe == null) {
                    probe = left.next();
                    if (probe == null) {
                        if (kind != JoinKind.RIGHT && kind != JoinKind.FULL) return null;
                        List<Object[]> rest = new ArrayList<>();
                        for (int i = matched.nextClearBit(0); i < buildRows.size(); i = matched.nextClearBit(i + 1)) {
                            rest.add(nullsThen(left.width(), buildRows.get(i)));
                        }
                        unmatched = rest.iterator();
                        continue;
                    }
                    List<Object> k = key(probe, leftKeys);
                    candidates = k == null ? List.of() : table.getOrDefault(k, List.of());
                    pos = 0;
                    probeMatched = false;
                }
                while (pos < candidates.size()) {
                    int b = candidates.get(pos++);
                    Object[] out = join(probe, buildRows.get(b), right.width());
                    if (residual == null || Expr.isTrue(residual.eval(out, ctx))) {
                        probeMatched = true;
                        matched.set(b);
                        return out;
                    }
                }
                Object[] p = probe;
                probe = null;
                if (!probeMatched && (kind == JoinKind.LEFT || kind == JoinKind.FULL)) return join(p, null, right.width());
            }
        }

        public String label() {
            return joinName("Hash Join", kind);
        }

        public List<String> details() {
            List<String> conds = new ArrayList<>();
            for (int i = 0; i < leftKeys.size(); i++) conds.add(leftKeys.get(i).sql() + " = " + rightKeys.get(i).sql());
            List<String> out = new ArrayList<>();
            out.add("Hash Cond: (" + String.join(" AND ", conds) + ")");
            if (residual != null) out.add("Join Filter: " + residual.sql());
            return out;
        }

        public List<Operator> children() {
            return List.of(left, right);
        }
    }

    /**
     * For each left row, looks up matching right rows through an index on the
     * right table: fast when the left side is small and the right side big.
     */
    public static final class IndexNestedLoopJoin extends Operator {
        private final JoinKind kind;
        private final Operator left;
        private final Table table;
        private final String alias;
        private final Index index;
        private final List<Expr> keys;   // evaluated on the left row: values for the index's first columns
        private final Expr innerFilter;  // on the right row alone
        private final Expr residual;     // on the joined row
        private final int rightWidth;
        private Object[] outer;
        private boolean outerMatched;
        private Iterator<Table.Row> inner = Collections.emptyIterator();
        private long probes;
        private long innerRows;
        private long innerNanos;

        public IndexNestedLoopJoin(JoinKind kind, Operator left, Table table, String alias, Index index, List<Expr> keys,
                                   Expr innerFilter, Expr residual, List<String> rightNames) {
            super(concat(left.names(), rightNames), concatTypes(left.types(), Arrays.asList(table.types())));
            this.kind = kind;
            this.left = left;
            this.table = table;
            this.alias = alias;
            this.index = index;
            this.keys = keys;
            this.innerFilter = innerFilter;
            this.residual = residual;
            this.rightWidth = table.types().length;
        }

        protected void doOpen() {
            left.open(ctx);
            outer = null;
        }

        protected Object[] doNext() {
            while (true) {
                if (outer == null) {
                    outer = left.next();
                    if (outer == null) return null;
                    outerMatched = false;
                    List<Expr> eq = new ArrayList<>();
                    for (Expr k : keys) eq.add(new Expr.Const(k.eval(outer, ctx), k.type()));
                    long t = ctx.analyze() ? System.nanoTime() : 0;
                    List<IndexProbe.Range> rs = ranges(ctx, index, table, List.of(new IndexProbe(eq, null, false, null, false)));
                    inner = rs.isEmpty() ? Collections.emptyIterator() : table.indexScan(index, rs.get(0).from(), rs.get(0).to(), ctx.snapshot());
                    probes++;
                    if (ctx.analyze()) innerNanos += System.nanoTime() - t;
                }
                while (true) {
                    long t = ctx.analyze() ? System.nanoTime() : 0;
                    boolean more = inner.hasNext();
                    Object[] r = more ? inner.next().values() : null;
                    if (ctx.analyze()) innerNanos += System.nanoTime() - t;
                    if (!more) break;
                    innerRows++;
                    if (innerFilter != null && !Expr.isTrue(innerFilter.eval(r, ctx))) continue;
                    Object[] out = join(outer, r, rightWidth);
                    if (residual == null || Expr.isTrue(residual.eval(out, ctx))) {
                        outerMatched = true;
                        return out;
                    }
                }
                Object[] o = outer;
                outer = null;
                if (!outerMatched && kind == JoinKind.LEFT) return join(o, null, rightWidth);
            }
        }

        public String label() {
            return joinName("Nested Loop", kind);
        }

        public List<String> details() {
            return residual == null ? List.of() : List.of("Join Filter: " + residual.sql());
        }

        public List<Operator> children() {
            List<String> cols = new ArrayList<>();
            for (int c : index.def().columns()) cols.add(table.def().columns().get(c).name());
            List<String> conds = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) conds.add(cols.get(i) + " = " + keys.get(i).sql());
            Operator probeNode = new Described("Index Scan using " + index.def().name() + " on " + relation(table, alias),
                    innerFilter == null ? List.of("Index Cond: (" + String.join(" AND ", conds) + ")")
                            : List.of("Index Cond: (" + String.join(" AND ", conds) + ")", "Filter: " + innerFilter.sql()));
            probeNode.estimate(Math.max(1, estimatedRows() / Math.max(1, left.estimatedRows())));
            probeNode.recordActual(innerRows, innerNanos, (int) probes);
            return List.of(left, probeNode);
        }

        @Override
        public void close() {
            left.close();
        }
    }

    /** A node that only exists to be printed by EXPLAIN. */
    static final class Described extends Operator {
        private final String label;
        private final List<String> details;

        Described(String label, List<String> details) {
            super(List.of(), List.of());
            this.label = label;
            this.details = details;
        }

        protected void doOpen() {
        }

        protected Object[] doNext() {
            return null;
        }

        public String label() {
            return label;
        }

        public List<String> details() {
            return details;
        }
    }

    // ---------------- aggregation ----------------

    /** One aggregate: {@code fn(DISTINCT arg)}; {@code arg} is null for count(*). */
    public record AggCall(String fn, Expr arg, boolean distinct, Type type, Expr separator) {
        public String sql() {
            if (arg == null) return "count(*)";
            return fn + "(" + (distinct ? "DISTINCT " : "") + arg.sql() + (separator == null ? "" : ", " + separator.sql()) + ")";
        }
    }

    private static final class Acc {
        long count;
        long longSum;
        java.math.BigInteger bigSum; // once the running total leaves the long range
        double doubleSum;
        boolean overflow;
        Object best;
        Boolean bool;
        StringBuilder text;
        Set<Object> seen;
    }

    /** Groups rows by key and computes the aggregates for each group. Output: group values, then aggregate values. */
    public static final class HashAggregate extends Operator {
        private final Operator child;
        private final List<Expr> groups;
        private final List<AggCall> aggs;
        private Iterator<Object[]> out;

        public HashAggregate(Operator child, List<Expr> groups, List<AggCall> aggs, List<String> names) {
            super(names, types(groups, aggs));
            this.child = child;
            this.groups = groups;
            this.aggs = aggs;
        }

        private static List<Type> types(List<Expr> groups, List<AggCall> aggs) {
            List<Type> t = new ArrayList<>();
            for (Expr g : groups) t.add(g.type());
            for (AggCall a : aggs) t.add(a.type());
            return t;
        }

        protected void doOpen() {
            Map<List<Object>, Object[]> keys = new LinkedHashMap<>();
            Map<List<Object>, Acc[]> states = new HashMap<>();
            child.open(ctx);
            for (Object[] r = child.next(); r != null; r = child.next()) {
                Object[] g = new Object[groups.size()];
                for (int i = 0; i < g.length; i++) g[i] = groups.get(i).eval(r, ctx);
                List<Object> k = hashKey(g);
                Acc[] acc = states.get(k);
                if (acc == null) {
                    acc = new Acc[aggs.size()];
                    for (int i = 0; i < acc.length; i++) acc[i] = new Acc();
                    states.put(k, acc);
                    keys.put(k, g);
                }
                for (int i = 0; i < aggs.size(); i++) accumulate(aggs.get(i), acc[i], r);
            }
            List<Object[]> rows = new ArrayList<>();
            if (groups.isEmpty() && keys.isEmpty()) {
                Acc[] empty = new Acc[aggs.size()];
                for (int i = 0; i < empty.length; i++) empty[i] = new Acc();
                rows.add(result(new Object[0], empty));
            }
            for (Map.Entry<List<Object>, Object[]> e : keys.entrySet()) rows.add(result(e.getValue(), states.get(e.getKey())));
            out = rows.iterator();
        }

        private void accumulate(AggCall a, Acc acc, Object[] row) {
            if (a.arg() == null) {
                acc.count++;
                return;
            }
            Object v = a.arg().eval(row, ctx);
            if (v == null) return; // aggregates skip NULLs
            if (a.distinct()) {
                if (acc.seen == null) acc.seen = new HashSet<>();
                if (!acc.seen.add(Values.hashKey(v))) return;
            }
            acc.count++;
            switch (a.fn()) {
                case "sum", "avg" -> {
                    if (v instanceof Long l && a.fn().equals("sum") && a.type() == Type.BIGINT) {
                        if (acc.bigSum != null) {
                            acc.bigSum = acc.bigSum.add(java.math.BigInteger.valueOf(l));
                        } else {
                            try {
                                acc.longSum = Math.addExact(acc.longSum, l);
                            } catch (ArithmeticException e) {
                                acc.bigSum = java.math.BigInteger.valueOf(acc.longSum).add(java.math.BigInteger.valueOf(l));
                            }
                        }
                    } else {
                        acc.doubleSum += ((Number) v).doubleValue();
                    }
                }
                case "min", "max" -> {
                    int c = acc.best == null ? 0 : Values.compare(v, acc.best);
                    if (acc.best == null || (a.fn().equals("min") ? c < 0 : c > 0)) acc.best = v;
                }
                case "bool_and", "every" -> acc.bool = acc.bool == null ? (Boolean) v : acc.bool && (Boolean) v;
                case "bool_or" -> acc.bool = acc.bool == null ? (Boolean) v : acc.bool || (Boolean) v;
                case "string_agg" -> {
                    Object sep = a.separator().eval(row, ctx);
                    if (acc.text == null) {
                        acc.text = new StringBuilder();
                    } else if (sep != null) {
                        acc.text.append(sep);
                    }
                    acc.text.append((String) v);
                }
                default -> { } // count
            }
        }

        private Object[] result(Object[] group, Acc[] acc) {
            Object[] row = Arrays.copyOf(group, groups.size() + aggs.size());
            for (int i = 0; i < aggs.size(); i++) {
                AggCall a = aggs.get(i);
                Acc s = acc[i];
                row[groups.size() + i] = switch (a.fn()) {
                    case "count" -> s.count;
                    case "sum" -> s.count == 0 ? null : a.type() == Type.BIGINT ? (Object) bigintTotal(s) : (Object) s.doubleSum;
                    case "avg" -> s.count == 0 ? null : s.doubleSum / s.count;
                    case "min", "max" -> s.best;
                    case "bool_and", "every", "bool_or" -> s.bool;
                    case "string_agg" -> s.text == null ? null : s.text.toString();
                    default -> throw new IllegalStateException(a.fn());
                };
            }
            return row;
        }

        private static long bigintTotal(Acc s) {
            if (s.bigSum == null) return s.longSum;
            if (s.bigSum.bitLength() > 63) throw Values.outOfRange();
            return s.bigSum.longValue();
        }

        protected Object[] doNext() {
            return out.hasNext() ? out.next() : null;
        }

        public String label() {
            return groups.isEmpty() ? "Aggregate" : "HashAggregate";
        }

        public List<String> details() {
            List<String> out = new ArrayList<>();
            if (!groups.isEmpty()) {
                List<String> g = new ArrayList<>();
                for (Expr e : groups) g.add(e.sql());
                out.add("Group Key: " + String.join(", ", g));
            }
            return out;
        }

        public List<Operator> children() {
            return List.of(child);
        }
    }

    // ---------------- sort, limit, distinct ----------------

    public record SortKey(int column, boolean descending, boolean nullsFirst, String sql) {}

    public static final class Sort extends Operator {
        private final Operator child;
        private final List<SortKey> keys;
        private Iterator<Object[]> out;

        public Sort(Operator child, List<SortKey> keys) {
            super(child.names(), child.types());
            this.child = child;
            this.keys = keys;
        }

        protected void doOpen() {
            List<Object[]> rows = new ArrayList<>();
            child.open(ctx);
            for (Object[] r = child.next(); r != null; r = child.next()) rows.add(r);
            Comparator<Object[]> cmp = (a, b) -> {
                for (SortKey k : keys) {
                    Object x = a[k.column()];
                    Object y = b[k.column()];
                    int c;
                    if (x == null || y == null) {
                        if (x == y) continue;
                        c = (x == null) == k.nullsFirst() ? -1 : 1; // NULL placement doesn't flip with DESC
                        return c;
                    }
                    c = Values.compare(x, y);
                    if (c != 0) return k.descending() ? -c : c;
                }
                return 0;
            };
            rows.sort(cmp); // stable
            out = rows.iterator();
        }

        protected Object[] doNext() {
            return out.hasNext() ? out.next() : null;
        }

        public String label() {
            return "Sort";
        }

        public List<String> details() {
            List<String> k = new ArrayList<>();
            for (SortKey s : keys) k.add(s.sql());
            return List.of("Sort Key: " + String.join(", ", k));
        }

        public List<Operator> children() {
            return List.of(child);
        }
    }

    public static final class Limit extends Operator {
        private final Operator child;
        private final Expr limit;
        private final Expr offset;
        private long remaining;

        public Limit(Operator child, Expr limit, Expr offset) {
            super(child.names(), child.types());
            this.child = child;
            this.limit = limit;
            this.offset = offset;
        }

        private long value(Expr e, String what, long ifNull) {
            if (e == null) return ifNull;
            Object v = Values.cast(e.eval(new Object[0], ctx), Type.BIGINT);
            if (v == null) return ifNull;
            long n = (Long) v;
            if (n < 0) throw new SqlException("2201W", what + " must not be negative");
            return n;
        }

        protected void doOpen() {
            remaining = value(limit, "LIMIT", Long.MAX_VALUE);
            long skip = value(offset, "OFFSET", 0);
            child.open(ctx);
            for (long i = 0; i < skip && child.next() != null; i++) {
                // skipped
            }
        }

        protected Object[] doNext() {
            if (remaining <= 0) return null;
            Object[] r = child.next();
            if (r != null) remaining--;
            return r;
        }

        public String label() {
            return "Limit";
        }

        public List<Operator> children() {
            return List.of(child);
        }
    }

    public static final class Distinct extends Operator {
        private final Operator child;
        private Set<List<Object>> seen;

        public Distinct(Operator child) {
            super(child.names(), child.types());
            this.child = child;
        }

        protected void doOpen() {
            seen = new HashSet<>();
            child.open(ctx);
        }

        protected Object[] doNext() {
            for (Object[] r = child.next(); r != null; r = child.next()) if (seen.add(hashKey(r))) return r;
            return null;
        }

        public String label() {
            return "HashAggregate";
        }

        public List<String> details() {
            return List.of("Group Key: (all columns)");
        }

        public List<Operator> children() {
            return List.of(child);
        }
    }

    /** UNION, INTERSECT, EXCEPT, with or without ALL. */
    public static final class SetOp extends Operator {
        private final SetOpKind kind;
        private final boolean all;
        private final Operator left;
        private final Operator right;
        private Iterator<Object[]> out;

        public SetOp(SetOpKind kind, boolean all, Operator left, Operator right) {
            super(left.names(), left.types());
            this.kind = kind;
            this.all = all;
            this.left = left;
            this.right = right;
        }

        protected void doOpen() {
            List<Object[]> rows = new ArrayList<>();
            if (kind == SetOpKind.UNION) {
                Set<List<Object>> seen = new LinkedHashSet<>();
                for (Operator side : List.of(left, right)) {
                    side.open(ctx);
                    for (Object[] r = side.next(); r != null; r = side.next()) if (all || seen.add(hashKey(r))) rows.add(r);
                }
            } else {
                Map<List<Object>, Integer> counts = new HashMap<>();
                right.open(ctx);
                for (Object[] r = right.next(); r != null; r = right.next()) counts.merge(hashKey(r), 1, Integer::sum);
                Set<List<Object>> emitted = new HashSet<>();
                left.open(ctx);
                for (Object[] r = left.next(); r != null; r = left.next()) {
                    List<Object> k = hashKey(r);
                    int c = counts.getOrDefault(k, 0);
                    if (kind == SetOpKind.INTERSECT) {
                        if (c == 0) continue;
                        if (all) {
                            counts.put(k, c - 1);
                            rows.add(r);
                        } else if (emitted.add(k)) {
                            rows.add(r);
                        }
                    } else {
                        if (all) {
                            if (c > 0) counts.put(k, c - 1);
                            else rows.add(r);
                        } else if (c == 0 && emitted.add(k)) {
                            rows.add(r);
                        }
                    }
                }
            }
            out = rows.iterator();
        }

        protected Object[] doNext() {
            return out.hasNext() ? out.next() : null;
        }

        public String label() {
            String name = switch (kind) {
                case UNION -> "Append";
                case INTERSECT -> "HashSetOp Intersect";
                case EXCEPT -> "HashSetOp Except";
            };
            return kind != SetOpKind.UNION && all ? name + " All" : kind == SetOpKind.UNION && !all ? "Append (distinct)" : name;
        }

        public List<Operator> children() {
            return List.of(left, right);
        }
    }
}
