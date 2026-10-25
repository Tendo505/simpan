package io.github.tendo505.simpan.exec;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.engine.Database;
import io.github.tendo505.simpan.tx.Snapshot;
import io.github.tendo505.simpan.tx.Transaction;
import io.github.tendo505.simpan.types.Values;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What a running statement needs: its transaction and snapshot, parameter values, and per-statement caches. */
public final class ExecContext {
    private final Database db;
    private final Transaction tx;
    private final Snapshot snapshot;
    private final List<Object> params;
    private final Map<SubPlan, SubPlan.Result> subqueries = new IdentityHashMap<>();
    private final Map<String, LikePattern> likes = new HashMap<>();
    private final boolean analyze;

    public ExecContext(Database db, Transaction tx, Snapshot snapshot, List<Object> params, boolean analyze) {
        this.db = db;
        this.tx = tx;
        this.snapshot = snapshot;
        this.params = params;
        this.analyze = analyze;
    }

    public Database db() {
        return db;
    }

    public Transaction tx() {
        return tx;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** True for EXPLAIN ANALYZE: operators time themselves. */
    public boolean analyze() {
        return analyze;
    }

    public Object param(int index) {
        if (index < 1 || index > params.size()) {
            throw new SqlException(SqlException.UNDEFINED_PARAMETER, "there is no parameter $" + index);
        }
        return params.get(index - 1);
    }

    /** Runs an uncorrelated subquery the first time it's needed in this statement. */
    public SubPlan.Result subquery(SubPlan plan) {
        SubPlan.Result r = subqueries.get(plan);
        if (r != null) return r;
        List<Object[]> rows = new ArrayList<>();
        Set<Object> keys = new HashSet<>();
        boolean hasNull = false;
        Operator op = plan.plan();
        op.open(this);
        try {
            for (Object[] row = op.next(); row != null; row = op.next()) {
                rows.add(row);
                if (row.length > 0) {
                    if (row[0] == null) hasNull = true;
                    else keys.add(Values.hashKey(row[0]));
                }
            }
        } finally {
            op.close();
        }
        r = new SubPlan.Result(rows, keys, hasNull);
        subqueries.put(plan, r);
        return r;
    }

    public LikePattern likePattern(String pattern, boolean caseInsensitive) {
        return likes.computeIfAbsent((caseInsensitive ? "i:" : "s:") + pattern, k -> LikePattern.compile(pattern, caseInsensitive));
    }

    /**
     * Runs every subquery in these expressions now. A write statement calls
     * this before it changes anything, so its subqueries see the table as it
     * was when the statement started, not its own changes.
     */
    public void prefetch(List<Expr> exprs) {
        for (Expr e : exprs) for (SubPlan p : Expr.subplans(e)) subquery(p);
    }
}
