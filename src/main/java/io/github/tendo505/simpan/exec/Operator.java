package io.github.tendo505.simpan.exec;

import io.github.tendo505.simpan.types.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A node of a query plan. Rows are pulled one at a time ({@link #next()}
 * returns null when there are no more), so most plans stream without
 * holding all their rows. Each node keeps the planner's row estimate and,
 * under EXPLAIN ANALYZE, how many rows it really produced and how long it
 * took.
 */
public abstract class Operator {
    private final List<String> names;
    private final List<Type> types;
    private double estimatedRows;
    private long actualRows;
    private long nanos;
    private int loops;
    protected ExecContext ctx;

    protected Operator(List<String> names, List<Type> types) {
        this.names = List.copyOf(names);
        this.types = List.copyOf(types);
    }

    public List<String> names() {
        return names;
    }

    public List<Type> types() {
        return types;
    }

    public int width() {
        return types.size();
    }

    public double estimatedRows() {
        return estimatedRows;
    }

    public Operator estimate(double rows) {
        this.estimatedRows = rows;
        return this;
    }

    public final void open(ExecContext ctx) {
        this.ctx = ctx;
        loops++;
        if (ctx.analyze()) {
            long t = System.nanoTime();
            doOpen();
            nanos += System.nanoTime() - t;
        } else {
            doOpen();
        }
    }

    public final Object[] next() {
        Object[] row;
        if (ctx.analyze()) {
            long t = System.nanoTime();
            row = doNext();
            nanos += System.nanoTime() - t;
        } else {
            row = doNext();
        }
        if (row != null) actualRows++;
        return row;
    }

    public void close() {
        for (Operator c : children()) c.close();
    }

    protected abstract void doOpen();

    protected abstract Object[] doNext();

    /** The node's name in EXPLAIN, e.g. "Hash Join". */
    public abstract String label();

    /** Extra EXPLAIN lines, e.g. "Filter: (x > 3)". */
    public List<String> details() {
        return List.of();
    }

    public List<Operator> children() {
        return List.of();
    }

    /** Set from outside for nodes that don't run themselves (the probe side of an index join). */
    void recordActual(long rows, long nanos, int loops) {
        this.actualRows = rows;
        this.nanos = nanos;
        this.loops = loops;
    }

    /** True for nodes EXPLAIN leaves out, showing their child in their place (plain projections, as PostgreSQL does). */
    protected boolean hiddenInExplain() {
        return false;
    }

    /** The plan as PostgreSQL-style EXPLAIN lines. */
    public List<String> explain(boolean analyze) {
        List<String> out = new ArrayList<>();
        explain(out, 0, analyze);
        return out;
    }

    private void explain(List<String> out, int depth, boolean analyze) {
        if (hiddenInExplain() && children().size() == 1) {
            children().get(0).explain(out, depth, analyze);
            return;
        }
        // PostgreSQL's layout: children start "->  " six columns in per level, details line up under the name.
        String indent = " ".repeat(depth == 0 ? 0 : 6 * (depth - 1) + 2);
        StringBuilder line = new StringBuilder(indent).append(depth == 0 ? "" : "->  ").append(label());
        line.append("  (rows=").append(Math.max(1, Math.round(estimatedRows))).append(')');
        if (analyze) {
            if (loops == 0) line.append(" (never executed)");
            else line.append(String.format(Locale.ROOT, " (actual time=%.3f ms rows=%d loops=%d)", nanos / 1e6 / loops, actualRows / loops, loops));
        }
        out.add(line.toString());
        String detailIndent = " ".repeat(depth == 0 ? 2 : 6 * depth + 2);
        for (String d : details()) out.add(detailIndent + d);
        for (Operator c : children()) c.explain(out, depth + 1, analyze);
    }
}
