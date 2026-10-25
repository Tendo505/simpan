package io.github.tendo505.simpan.plan;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.catalog.Catalog.IndexDef;
import io.github.tendo505.simpan.catalog.Catalog.Stats;
import io.github.tendo505.simpan.engine.Database;
import io.github.tendo505.simpan.engine.Table;
import io.github.tendo505.simpan.engine.Table.Index;
import io.github.tendo505.simpan.exec.Expr;
import io.github.tendo505.simpan.exec.Functions;
import io.github.tendo505.simpan.exec.IndexProbe;
import io.github.tendo505.simpan.exec.Operator;
import io.github.tendo505.simpan.exec.Operators;
import io.github.tendo505.simpan.exec.Operators.AggCall;
import io.github.tendo505.simpan.exec.Operators.SortKey;
import io.github.tendo505.simpan.exec.SubPlan;
import io.github.tendo505.simpan.sql.Ast;
import io.github.tendo505.simpan.sql.Ast.BinaryOp;
import io.github.tendo505.simpan.sql.Ast.JoinKind;
import io.github.tendo505.simpan.types.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a query into a plan of {@link Operator}s.
 *
 * <p>FROM and WHERE: inner joins are flattened into one set of inputs and
 * one pool of conditions. A condition on a single input is pushed down to
 * it, where an index can serve it. The join order comes from dynamic
 * programming over subsets of the inputs, costed by estimated row counts,
 * and each join picks a hash join, an index nested-loop join, or a plain
 * nested loop. Outer joins keep their written order.
 *
 * <p>Then: GROUP BY and aggregates, HAVING, the select list, DISTINCT,
 * ORDER BY, LIMIT. Uncorrelated subqueries in expressions become
 * {@link SubPlan}s that run once.
 */
public final class Planner implements Binder.Subqueries {
    private final Database db;
    private final Map<Integer, Type> paramTypes;
    private final Set<Table> tables = new LinkedHashSet<>();
    private int subplans;

    public Planner(Database db, Map<Integer, Type> paramTypes) {
        this.db = db;
        this.paramTypes = paramTypes;
    }

    /** Every table the plan reads, so the caller can lock them for the statement. */
    public Set<Table> tables() {
        return tables;
    }

    public Map<Integer, Type> paramTypes() {
        return paramTypes;
    }

    @Override
    public SubPlan plan(Ast.Query query, Scope outer) {
        try {
            return new SubPlan(++subplans, planQuery(query));
        } catch (SqlException e) {
            if (outer.size() > 0 && (e.sqlState().equals(SqlException.UNDEFINED_COLUMN) || e.getMessage().startsWith("missing FROM-clause"))) {
                throw new SqlException(SqlException.FEATURE_NOT_SUPPORTED, e.getMessage()
                        + " (if it refers to the outer query: correlated subqueries are not supported; try a JOIN)");
            }
            throw e;
        }
    }

    // ---------------- queries ----------------

    public Operator planQuery(Ast.Query q) {
        if (q.body() instanceof Ast.SelectCore core) return planSelect(core, q.orderBy(), q.limit(), q.offset());
        Operator body = planBody(q.body());
        if (!q.orderBy().isEmpty()) {
            List<Scope.Entry> entries = new ArrayList<>();
            for (int i = 0; i < body.width(); i++) entries.add(new Scope.Entry(null, body.names().get(i), body.types().get(i)));
            Binder b = new Binder(new Scope(entries), this, paramTypes, "ORDER BY");
            List<Expr> extra = new ArrayList<>();
            List<SortKey> keys = new ArrayList<>();
            for (Ast.OrderItem item : q.orderBy()) {
                int pos = outputPosition(item.expr(), body.names(), body.width());
                Expr e;
                if (pos >= 0) {
                    e = new Expr.Col(pos, body.types().get(pos), body.names().get(pos));
                } else {
                    e = b.bind(item.expr());
                }
                keys.add(sortKey(item, body.width() + extra.size(), e));
                extra.add(e);
            }
            List<Expr> all = new ArrayList<>();
            for (int i = 0; i < body.width(); i++) all.add(new Expr.Col(i, body.types().get(i), body.names().get(i)));
            all.addAll(extra);
            List<String> names = new ArrayList<>(body.names());
            for (int i = 0; i < extra.size(); i++) names.add("?sort?");
            Operator sorted = new Operators.Sort(new Operators.Project(body, all, names).estimate(body.estimatedRows()), keys).estimate(body.estimatedRows());
            body = trim(sorted, body.width(), body.names());
        }
        return limit(body, q.limit(), q.offset());
    }

    private Operator planBody(Ast.QueryBody body) {
        if (body instanceof Ast.SelectCore core) return planSelect(core, List.of(), null, null);
        Ast.SetOp op = (Ast.SetOp) body;
        Operator left = planBody(op.left());
        Operator right = planBody(op.right());
        String name = op.kind().name();
        if (left.width() != right.width()) {
            throw new SqlException(SqlException.SYNTAX, "each " + name + " query must have the same number of columns");
        }
        List<Type> types = new ArrayList<>();
        for (int i = 0; i < left.width(); i++) types.add(Functions.common(name, List.of(left.types().get(i), right.types().get(i))));
        left = castColumns(left, types);
        right = castColumns(right, types);
        double rows = switch (op.kind()) {
            case UNION -> left.estimatedRows() + right.estimatedRows();
            case INTERSECT -> Math.min(left.estimatedRows(), right.estimatedRows()) / 2;
            case EXCEPT -> left.estimatedRows() / 2;
        };
        return new Operators.SetOp(op.kind(), op.all(), left, right).estimate(rows);
    }

    /** Casts columns (e.g. bigint to double precision, or a NULL literal to the other side's type) so both sides of a set operation agree. */
    private static Operator castColumns(Operator op, List<Type> types) {
        if (op.types().equals(types)) return op;
        List<Expr> exprs = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            Expr c = new Expr.Col(i, op.types().get(i), op.names().get(i));
            exprs.add(op.types().get(i) == types.get(i) ? c : new Expr.Cast(c, types.get(i)));
        }
        return new Operators.Project(op, exprs, op.names()).estimate(op.estimatedRows());
    }

    private static Operator trim(Operator op, int width, List<String> names) {
        if (op.width() == width) return op;
        List<Expr> exprs = new ArrayList<>();
        for (int i = 0; i < width; i++) exprs.add(new Expr.Col(i, op.types().get(i), names.get(i)));
        return new Operators.Project(op, exprs, names).estimate(op.estimatedRows());
    }

    private Operator limit(Operator op, Ast.Expr limit, Ast.Expr offset) {
        if (limit == null && offset == null) return op;
        Binder b = new Binder(Scope.empty(), this, paramTypes, "LIMIT");
        Expr l = limit == null ? null : bigint(b, b.bind(limit), "LIMIT");
        Expr o = offset == null ? null : bigint(b, b.bind(offset), "OFFSET");
        double rows = op.estimatedRows();
        if (l instanceof Expr.Const c && c.value() instanceof Long n) rows = Math.min(rows, n);
        return new Operators.Limit(op, l, o).estimate(rows);
    }

    private static Expr bigint(Binder b, Expr e, String what) {
        Expr x = b.coerce(e, Type.BIGINT);
        if (x.type() == Type.DOUBLE) x = new Expr.Cast(x, Type.BIGINT);
        if (x.type() != Type.BIGINT) throw new SqlException(SqlException.DATATYPE_MISMATCH, "argument of " + what + " must be type bigint, not type " + x.type().sqlName());
        return x;
    }

    // ---------------- SELECT ----------------

    private Operator planSelect(Ast.SelectCore core, List<Ast.OrderItem> orderBy, Ast.Expr limit, Ast.Expr offset) {
        FromPlanner from = new FromPlanner();
        Plan input;
        if (core.from() == null) {
            input = new Plan(new Operators.ValuesScan(List.of(List.of()), List.of(), List.of()).estimate(1), new int[0], 1, 0, null, List.of());
            from.scope = Scope.empty();
        } else {
            Node root = from.build(core.from());
            from.scope = new Scope(from.entries);
            Binder where = new Binder(from.scope, this, paramTypes, "WHERE");
            List<Expr> conjuncts = core.where() == null ? List.of() : Expr.conjuncts(where.bindCondition(core.where()));
            input = from.plan(root, conjuncts);
        }
        Scope scope = from.scope;
        Binder plain = new Binder(scope, this, paramTypes, "SELECT");
        if (core.where() == null && core.from() == null) {
            // nothing to do: a SELECT without FROM has an empty input row
        } else if (core.from() == null) {
            Expr w = new Binder(scope, this, paramTypes, "WHERE").bindCondition(core.where());
            input = new Plan(new Operators.Filter(input.op, w).estimate(1), input.layout, 1, 0, null, List.of());
        }
        // Expand the select list and work out names.
        List<Ast.Expr> items = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Ast.SelectItem item : core.items()) {
            if (item instanceof Ast.Star s) {
                if (core.from() == null) throw new SqlException(SqlException.SYNTAX, "SELECT * with no tables specified is not valid");
                for (int i : scope.star(s.qualifier())) {
                    Scope.Entry e = scope.get(i);
                    items.add(new Ast.ColumnRef(e.qualifier(), e.name()));
                    names.add(e.name());
                }
            } else {
                Ast.ExprItem ei = (Ast.ExprItem) item;
                items.add(ei.expr());
                names.add(ei.alias() != null ? ei.alias() : derivedName(ei.expr()));
            }
        }
        boolean aggregated = !core.groupBy().isEmpty() || core.having() != null
                || items.stream().anyMatch(Binder::hasAggregate) || orderBy.stream().anyMatch(o -> Binder.hasAggregate(o.expr()));
        int[] layout = input.layout;
        Operator op = input.op;
        double rows = input.rows;
        Binder outBinder;
        if (aggregated) {
            List<Expr> groups = new ArrayList<>();
            for (Ast.Expr g : core.groupBy()) {
                Ast.Expr resolved = resolveGroupItem(g, core.items(), items, names, scope);
                if (Binder.hasAggregate(resolved)) throw new SqlException(SqlException.GROUPING, "aggregate functions are not allowed in GROUP BY");
                Expr b = plain.bind(resolved);
                if (!groups.contains(b)) groups.add(b);
            }
            List<AggCall> aggs = AggBinder.newList();
            outBinder = new AggBinder(plain, groups, aggs, "SELECT");
            // Bind outputs first so every aggregate is registered before the aggregation node is built.
            List<Expr> outputs = new ArrayList<>();
            for (Ast.Expr e : items) outputs.add(outBinder.bind(e));
            Expr having = core.having() == null ? null : new AggBinder(plain, groups, aggs, "HAVING").bindCondition(core.having());
            SortPlan sort = sortPlan(orderBy, items, names, outputs, outBinder, core.distinct());
            List<Expr> groupExprs = new ArrayList<>();
            for (Expr g : groups) groupExprs.add(remap(g, layout, scope.size()));
            List<AggCall> aggCalls = new ArrayList<>();
            for (AggCall a : aggs) {
                aggCalls.add(new AggCall(a.fn(), a.arg() == null ? null : remap(a.arg(), layout, scope.size()), a.distinct(), a.type(),
                        a.separator() == null ? null : remap(a.separator(), layout, scope.size())));
            }
            List<String> aggNames = new ArrayList<>();
            for (Expr g : groups) aggNames.add(g.sql());
            for (AggCall a : aggs) aggNames.add(a.sql());
            double groupsEst = groups.isEmpty() ? 1 : Math.max(1, Math.min(rows, groupEstimate(groups, from, rows)));
            op = new Operators.HashAggregate(op, groupExprs, aggCalls, aggNames).estimate(groupsEst);
            rows = groupsEst;
            if (having != null) {
                rows = Math.max(1, rows * 0.33);
                op = new Operators.Filter(op, having).estimate(rows);
            }
            return finish(op, rows, outputs, names, sort, core.distinct(), limit, offset);
        }
        List<Expr> outputs = new ArrayList<>();
        for (Ast.Expr e : items) outputs.add(remap(plain.bind(e), layout, scope.size()));
        Binder sortBinder = new Binder(scope, this, paramTypes, "ORDER BY");
        SortPlan sort = sortPlan(orderBy, items, names, outputs, new Remapping(sortBinder, layout, scope.size()), core.distinct());
        return finish(op, rows, outputs, names, sort, core.distinct(), limit, offset);
    }

    /** A binder whose results are remapped from scope positions to the input plan's layout. */
    private final class Remapping extends Binder {
        private final Binder inner;
        private final int[] layout;
        private final int scopeSize;

        Remapping(Binder inner, int[] layout, int scopeSize) {
            super(inner.scope(), Planner.this, Planner.this.paramTypes, "ORDER BY");
            this.inner = inner;
            this.layout = layout;
            this.scopeSize = scopeSize;
        }

        @Override
        public Expr bind(Ast.Expr e) {
            return remap(inner.bind(e), layout, scopeSize);
        }
    }

    /** GROUP BY 2 and GROUP BY alias refer to select items, as in PostgreSQL. */
    private static Ast.Expr resolveGroupItem(Ast.Expr g, List<Ast.SelectItem> raw, List<Ast.Expr> items, List<String> names, Scope scope) {
        if (g instanceof Ast.Literal l && l.value() instanceof Long k) {
            if (k < 1 || k > items.size()) throw new SqlException(SqlException.INVALID_COLUMN_REFERENCE, "GROUP BY position " + k + " is not in select list");
            return items.get((int) (k - 1));
        }
        if (g instanceof Ast.ColumnRef c && c.qualifier() == null && !scope.has(null, c.name())) {
            int i = names.indexOf(c.name());
            if (i >= 0) return items.get(i);
        }
        return g;
    }

    /** ORDER BY keys: positions in the projected row, plus any hidden expressions to compute for sorting. */
    private record SortPlan(List<SortKey> keys, List<Expr> hidden) {}

    private SortPlan sortPlan(List<Ast.OrderItem> orderBy, List<Ast.Expr> items, List<String> names, List<Expr> outputs,
                              Binder binder, boolean distinct) {
        List<SortKey> keys = new ArrayList<>();
        List<Expr> hidden = new ArrayList<>();
        for (Ast.OrderItem item : orderBy) {
            int pos = outputPosition(item.expr(), names, items.size());
            Expr e;
            if (pos >= 0) {
                e = outputs.get(pos);
            } else {
                e = binder.bind(item.expr());
                pos = outputs.indexOf(e);
                if (pos < 0) {
                    if (distinct) {
                        throw new SqlException(SqlException.INVALID_COLUMN_REFERENCE, "for SELECT DISTINCT, ORDER BY expressions must appear in select list");
                    }
                    int at = hidden.indexOf(e);
                    if (at < 0) {
                        hidden.add(e);
                        at = hidden.size() - 1;
                    }
                    pos = outputs.size() + at;
                }
            }
            keys.add(sortKey(item, pos, e));
        }
        return new SortPlan(keys, hidden);
    }

    private static SortKey sortKey(Ast.OrderItem item, int pos, Expr e) {
        boolean nullsFirst = item.nullsFirst() != null ? item.nullsFirst() : item.descending(); // NULLs are "largest", as in PostgreSQL
        return new SortKey(pos, item.descending(), nullsFirst, e.sql() + (item.descending() ? " DESC" : ""));
    }

    /** ORDER BY 2 or ORDER BY some_output_name: the output column's position, else -1. */
    private static int outputPosition(Ast.Expr e, List<String> names, int width) {
        if (e instanceof Ast.Literal l && l.value() instanceof Long k) {
            if (k < 1 || k > width) throw new SqlException(SqlException.INVALID_COLUMN_REFERENCE, "ORDER BY position " + k + " is not in select list");
            return (int) (k - 1);
        }
        if (e instanceof Ast.ColumnRef c && c.qualifier() == null) {
            int found = -1;
            for (int i = 0; i < width; i++) {
                if (names.get(i).equals(c.name())) {
                    if (found >= 0) throw new SqlException(SqlException.AMBIGUOUS_COLUMN, "ORDER BY \"" + c.name() + "\" is ambiguous");
                    found = i;
                }
            }
            return found;
        }
        return -1;
    }

    private Operator finish(Operator op, double rows, List<Expr> outputs, List<String> names, SortPlan sort, boolean distinct,
                            Ast.Expr limit, Ast.Expr offset) {
        List<Expr> projected = new ArrayList<>(outputs);
        projected.addAll(sort.hidden());
        List<String> projectedNames = new ArrayList<>(names);
        for (Expr h : sort.hidden()) projectedNames.add(h.sql());
        op = new Operators.Project(op, projected, projectedNames).estimate(rows);
        if (distinct) op = new Operators.Distinct(op).estimate(Math.max(1, rows * 0.5));
        if (!sort.keys().isEmpty()) op = new Operators.Sort(op, sort.keys()).estimate(op.estimatedRows());
        if (!sort.hidden().isEmpty()) op = trim(op, outputs.size(), names);
        return limit(op, limit, offset);
    }

    /** The column name PostgreSQL would give an unnamed select item. */
    private static String derivedName(Ast.Expr e) {
        return switch (e) {
            case Ast.ColumnRef c -> c.name();
            case Ast.FunctionCall f -> f.name();
            case Ast.Cast c -> c.operand() instanceof Ast.ColumnRef r ? r.name() : c.type().sqlName().split(" ")[0];
            case Ast.Case c -> "case";
            case Ast.Exists x -> "exists";
            default -> "?column?";
        };
    }

    // ---------------- FROM: inputs, conditions, join order ----------------

    /** A table or subquery in FROM, occupying {@code width} positions of the scope starting at {@code offset}. */
    private static final class Leaf {
        final String alias;
        final Table table;
        final Operator subquery;
        final int offset;
        final int width;
        final double rows;

        Leaf(String alias, Table table, Operator subquery, int offset, int width, double rows) {
            this.alias = alias;
            this.table = table;
            this.subquery = subquery;
            this.offset = offset;
            this.width = width;
            this.rows = rows;
        }
    }

    private sealed interface Node permits LeafNode, JoinNode {}

    private record LeafNode(Leaf leaf) implements Node {}

    private record JoinNode(JoinKind kind, Node left, Node right, Ast.Expr on) implements Node {}

    /**
     * A partial plan: its operator, which scope positions its output columns
     * hold, estimated rows and cost. {@code leaf} is set when it's a plain
     * table access, so a join can probe that table's index instead.
     */
    private record Plan(Operator op, int[] layout, double rows, double cost, Leaf leaf, List<Expr> leafConjuncts) {}

    private final class FromPlanner {
        final List<Leaf> leaves = new ArrayList<>();
        final List<Scope.Entry> entries = new ArrayList<>();
        Scope scope;

        Node build(Ast.FromItem f) {
            return switch (f) {
                case Ast.TableRef r -> {
                    Table t = db.table(r.name());
                    tables.add(t);
                    String alias = r.alias() != null ? r.alias() : r.name();
                    yield new LeafNode(addLeaf(alias, t, null, t.def().columns().stream().map(c -> c.name()).toList(),
                            Arrays.asList(t.types()), tableRows(t)));
                }
                case Ast.SubqueryRef s -> {
                    Operator op = planQuery(s.query());
                    yield new LeafNode(addLeaf(s.alias(), null, op, op.names(), op.types(), Math.max(1, op.estimatedRows())));
                }
                case Ast.Join j -> {
                    Node left = build(j.left());
                    Node right = build(j.right());
                    yield new JoinNode(j.kind(), left, right, j.on());
                }
            };
        }

        private Leaf addLeaf(String alias, Table t, Operator sub, List<String> names, List<Type> types, double rows) {
            for (Leaf l : leaves) {
                if (l.alias.equals(alias)) throw new SqlException("42712", "table name \"" + alias + "\" specified more than once");
            }
            Leaf leaf = new Leaf(alias, t, sub, entries.size(), names.size(), rows);
            for (int i = 0; i < names.size(); i++) entries.add(new Scope.Entry(alias, names.get(i), types.get(i)));
            leaves.add(leaf);
            return leaf;
        }

        Leaf leafOf(int column) {
            for (Leaf l : leaves) if (column >= l.offset && column < l.offset + l.width) return l;
            throw new IllegalStateException("no leaf for column " + column);
        }

        BitSet columnsOf(Node n) {
            BitSet b = new BitSet();
            switch (n) {
                case LeafNode l -> b.set(l.leaf().offset, l.leaf().offset + l.leaf().width);
                case JoinNode j -> {
                    b.or(columnsOf(j.left()));
                    b.or(columnsOf(j.right()));
                }
            }
            return b;
        }

        Plan plan(Node root, List<Expr> where) {
            return planGroup(root, where);
        }

        /** Plans a run of inner joins (or a single input) with the given extra conditions. */
        Plan planGroup(Node node, List<Expr> extra) {
            List<Node> units = new ArrayList<>();
            List<Expr> conj = new ArrayList<>(extra);
            flatten(node, units, conj);
            int n = units.size();
            List<BitSet> unitCols = new ArrayList<>();
            for (Node u : units) unitCols.add(columnsOf(u));
            // Which inputs each condition touches.
            List<BitSet> touches = new ArrayList<>();
            List<Expr> constant = new ArrayList<>();
            for (Expr c : conj) {
                BitSet t = new BitSet();
                for (int col : Expr.columns(c)) for (int u = 0; u < n; u++) if (unitCols.get(u).get(col)) t.set(u);
                touches.add(t);
                if (t.isEmpty()) constant.add(c);
            }
            Plan[] base = new Plan[n];
            for (int u = 0; u < n; u++) {
                List<Expr> own = new ArrayList<>();
                for (int i = 0; i < conj.size(); i++) if (touches.get(i).cardinality() == 1 && touches.get(i).get(u)) own.add(conj.get(i));
                base[u] = planUnit(units.get(u), own);
            }
            List<Expr> joinConj = new ArrayList<>();
            List<BitSet> joinTouches = new ArrayList<>();
            for (int i = 0; i < conj.size(); i++) {
                if (touches.get(i).cardinality() > 1) {
                    joinConj.add(conj.get(i));
                    joinTouches.add(touches.get(i));
                }
            }
            if (n > 63) throw SqlException.unsupported("joining more than 63 tables in one FROM clause");
            Plan result = n == 1 ? base[0] : order(base, joinConj, joinTouches);
            if (!constant.isEmpty()) {
                Expr f = remap(Expr.and(constant), result.layout, scope.size());
                result = new Plan(new Operators.Filter(result.op, f).estimate(result.rows), result.layout, result.rows, result.cost, null, List.of());
            }
            return result;
        }

        private void flatten(Node node, List<Node> units, List<Expr> conj) {
            if (node instanceof JoinNode j && (j.kind() == JoinKind.INNER || j.kind() == JoinKind.CROSS)) {
                flatten(j.left(), units, conj);
                flatten(j.right(), units, conj);
                if (j.on() != null) conj.addAll(Expr.conjuncts(new Binder(scope, Planner.this, paramTypes, "JOIN/ON").bindCondition(j.on())));
            } else {
                units.add(node);
            }
        }

        private Plan planUnit(Node unit, List<Expr> own) {
            Plan p;
            if (unit instanceof LeafNode ln && ln.leaf().table != null) return tableAccess(ln.leaf(), own);
            if (unit instanceof LeafNode ln) {
                Leaf l = ln.leaf();
                int[] layout = range(l.offset, l.width);
                p = new Plan(l.subquery, layout, l.rows, l.rows, null, List.of());
            } else {
                p = planOuter((JoinNode) unit);
            }
            if (own.isEmpty()) return p;
            double rows = Math.max(1, p.rows * selectivity(Expr.and(own)));
            Expr f = remap(Expr.and(own), p.layout, scope.size());
            return new Plan(new Operators.Filter(p.op, f).estimate(rows), p.layout, rows, p.cost + p.rows * 0.01, null, List.of());
        }

        /** An outer join, in the order written. Conditions on its inner side only are pushed down into that side. */
        private Plan planOuter(JoinNode j) {
            List<Expr> on = Expr.conjuncts(new Binder(scope, Planner.this, paramTypes, "JOIN/ON").bindCondition(j.on()));
            BitSet leftCols = columnsOf(j.left());
            BitSet rightCols = columnsOf(j.right());
            List<Expr> pushLeft = new ArrayList<>();
            List<Expr> pushRight = new ArrayList<>();
            List<Expr> rest = new ArrayList<>();
            for (Expr c : on) {
                Set<Integer> cols = Expr.columns(c);
                boolean onlyLeft = !cols.isEmpty() && cols.stream().allMatch(leftCols::get);
                boolean onlyRight = !cols.isEmpty() && cols.stream().allMatch(rightCols::get);
                if (j.kind() == JoinKind.LEFT && onlyRight) pushRight.add(c);
                else if (j.kind() == JoinKind.RIGHT && onlyLeft) pushLeft.add(c);
                else rest.add(c);
            }
            Plan left = planGroup(j.left(), pushLeft);
            Plan right = planGroup(j.right(), pushRight);
            Plan inner = joinPair(left, right, rest, j.kind());
            double rows = switch (j.kind()) {
                case LEFT -> Math.max(left.rows, inner.rows);
                case RIGHT -> Math.max(right.rows, inner.rows);
                default -> Math.max(Math.max(left.rows, right.rows), inner.rows);
            };
            return new Plan(inner.op.estimate(rows), inner.layout, rows, inner.cost, null, List.of());
        }

        /** Chooses the join order: dynamic programming over subsets for up to 10 inputs, greedy beyond. */
        private Plan order(Plan[] base, List<Expr> conj, List<BitSet> touches) {
            int n = base.length;
            if (n > 10) return greedy(base, conj, touches);
            Plan[] best = new Plan[1 << n];
            for (int i = 0; i < n; i++) best[1 << i] = base[i];
            for (int mask = 1; mask < (1 << n); mask++) {
                if (Integer.bitCount(mask) < 2) continue;
                boolean anyConnected = false;
                for (int pass = 0; pass < 2 && best[mask] == null; pass++) {
                    for (int a = (mask - 1) & mask; a > 0; a = (a - 1) & mask) {
                        int b = mask & ~a;
                        if (best[a] == null || best[b] == null) continue;
                        List<Expr> here = between(conj, touches, a, b);
                        if (here.isEmpty() && pass == 0) continue; // avoid cross products while there's another way
                        anyConnected |= !here.isEmpty();
                        Plan p = joinPair(best[a], best[b], here, JoinKind.INNER);
                        if (best[mask] == null || p.cost < best[mask].cost) best[mask] = p;
                    }
                    if (anyConnected) break;
                }
            }
            return best[(1 << n) - 1];
        }

        private Plan greedy(Plan[] base, List<Expr> conj, List<BitSet> touches) {
            int n = base.length;
            int start = 0;
            for (int i = 1; i < n; i++) if (base[i].rows < base[start].rows) start = i;
            long mask = 1L << start;
            Plan cur = base[start];
            for (int joined = 1; joined < n; joined++) {
                Plan bestNext = null;
                int bestUnit = -1;
                for (int u = 0; u < n; u++) {
                    if ((mask & (1L << u)) != 0) continue;
                    Plan p = joinPair(cur, base[u], between(conj, touches, mask, 1L << u), JoinKind.INNER);
                    if (bestNext == null || p.cost < bestNext.cost) {
                        bestNext = p;
                        bestUnit = u;
                    }
                }
                cur = bestNext;
                mask |= 1L << bestUnit;
            }
            return cur;
        }

        /** Conditions that become checkable when inputs {@code a} and {@code b} are joined. */
        private List<Expr> between(List<Expr> conj, List<BitSet> touches, long a, long b) {
            List<Expr> out = new ArrayList<>();
            long both = a | b;
            for (int i = 0; i < conj.size(); i++) {
                long t = touches.get(i).toLongArray().length == 0 ? 0 : touches.get(i).toLongArray()[0];
                if ((t & ~both) == 0 && (t & a) != 0 && (t & b) != 0) out.add(conj.get(i));
            }
            return out;
        }

        /** The cheapest way to join two partial plans on these conditions. */
        private Plan joinPair(Plan a, Plan b, List<Expr> conds, JoinKind kind) {
            int[] layout = concat(a.layout, b.layout);
            double sel = 1;
            for (Expr c : conds) sel *= selectivity(c);
            double rows = Math.max(1, a.rows * b.rows * sel);
            Set<Integer> aCols = toSet(a.layout);
            Set<Integer> bCols = toSet(b.layout);
            List<Expr> leftKeys = new ArrayList<>();
            List<Expr> rightKeys = new ArrayList<>();
            List<Expr> residual = new ArrayList<>();
            for (Expr c : conds) {
                if (c instanceof Expr.Compare cmp && cmp.op() == BinaryOp.EQ) {
                    Set<Integer> l = Expr.columns(cmp.left());
                    Set<Integer> r = Expr.columns(cmp.right());
                    if (!l.isEmpty() && !r.isEmpty() && aCols.containsAll(l) && bCols.containsAll(r)) {
                        leftKeys.add(cmp.left());
                        rightKeys.add(cmp.right());
                        continue;
                    }
                    if (!l.isEmpty() && !r.isEmpty() && aCols.containsAll(r) && bCols.containsAll(l)) {
                        leftKeys.add(cmp.right());
                        rightKeys.add(cmp.left());
                        continue;
                    }
                }
                residual.add(c);
            }
            int size = scope.size();
            Plan best = null;
            if (!leftKeys.isEmpty()) {
                List<Expr> lk = new ArrayList<>();
                List<Expr> rk = new ArrayList<>();
                for (Expr e : leftKeys) lk.add(remap(e, a.layout, size));
                for (Expr e : rightKeys) rk.add(remap(e, b.layout, size));
                Expr res = residual.isEmpty() ? null : remap(Expr.and(residual), layout, size);
                double cost = a.cost + b.cost + a.rows + b.rows * 1.5 + rows * 0.1;
                Operator op = new Operators.HashJoin(kind, a.op, b.op, lk, rk, res).estimate(rows);
                best = new Plan(op, layout, rows, cost, null, List.of());
                Plan probe = indexJoin(a, b, leftKeys, rightKeys, residual, kind, rows, layout);
                if (probe != null && probe.cost < best.cost) best = probe;
            }
            if (best == null) {
                Expr cond = conds.isEmpty() ? null : remap(Expr.and(conds), layout, size);
                double cost = a.cost + b.cost + a.rows * b.rows * 0.01 + rows * 0.1;
                best = new Plan(new Operators.NestedLoopJoin(kind, a.op, b.op, cond).estimate(rows), layout, rows, cost, null, List.of());
            }
            return best;
        }

        /**
         * An index nested-loop join, if {@code b} is a table with an index
         * whose leading columns are all joined by equality to {@code a}.
         */
        private Plan indexJoin(Plan a, Plan b, List<Expr> leftKeys, List<Expr> rightKeys, List<Expr> residual, JoinKind kind,
                               double rows, int[] layout) {
            if (b.leaf == null || (kind != JoinKind.INNER && kind != JoinKind.LEFT)) return null;
            Leaf leaf = b.leaf;
            Plan bestPlan = null;
            for (Index index : leaf.table.indexes()) {
                List<Expr> keys = new ArrayList<>();
                Set<Integer> used = new HashSet<>();
                for (int col : index.def().columns()) {
                    int found = -1;
                    for (int i = 0; i < rightKeys.size(); i++) {
                        if (rightKeys.get(i) instanceof Expr.Col c && c.index() == leaf.offset + col && !used.contains(i)) found = i;
                    }
                    if (found < 0) break;
                    used.add(found);
                    keys.add(remap(leftKeys.get(found), a.layout, scope.size()));
                }
                if (keys.isEmpty()) continue;
                List<Expr> res = new ArrayList<>(residual);
                for (int i = 0; i < leftKeys.size(); i++) {
                    if (!used.contains(i)) res.add(new Expr.Compare(BinaryOp.EQ, leftKeys.get(i), rightKeys.get(i)));
                }
                double perProbe = Math.max(1, leaf.rows * keySelectivity(leaf, index, keys.size()));
                double cost = a.cost + a.rows * (Math.log(leaf.rows + 2) / Math.log(2) * 0.2 + perProbe * 1.2) + rows * 0.1;
                if (bestPlan != null && cost >= bestPlan.cost) continue;
                Expr innerFilter = b.leafConjuncts.isEmpty() ? null : Expr.shift(Expr.and(b.leafConjuncts), -leaf.offset);
                Expr resExpr = res.isEmpty() ? null : remap(Expr.and(res), layout, scope.size());
                List<String> rightNames = new ArrayList<>();
                for (int i = 0; i < leaf.width; i++) rightNames.add(scope.get(leaf.offset + i).name());
                Operator op = new Operators.IndexNestedLoopJoin(kind, a.op, leaf.table, leaf.alias, index, keys, innerFilter, resExpr, rightNames).estimate(rows);
                bestPlan = new Plan(op, layout, rows, cost, null, List.of());
            }
            return bestPlan;
        }

        // ---------------- single-table access ----------------

        private Plan tableAccess(Leaf leaf, List<Expr> own) {
            List<Expr> local = new ArrayList<>();
            for (Expr c : own) local.add(Expr.shift(c, -leaf.offset));
            AccessPath path = accessPath(leaf.table, local, leaf.rows);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < leaf.width; i++) names.add(scope.get(leaf.offset + i).name());
            Expr filter = path.residual().isEmpty() ? null : Expr.and(path.residual());
            Operator op = path.index() == null
                    ? new Operators.SeqScan(leaf.table, leaf.alias, filter, names)
                    : new Operators.IndexScan(leaf.table, leaf.alias, path.index(), path.probes(), filter, names);
            double rows = Math.max(1, leaf.rows * selectivity(Expr.and(own)));
            op.estimate(rows);
            return new Plan(op, range(leaf.offset, leaf.width), rows, path.cost(), leaf, own);
        }

        private double keySelectivity(Leaf leaf, Index index, int eqColumns) {
            if (index.def().unique() && eqColumns == index.def().columns().size()) return 1 / Math.max(1, leaf.rows);
            double s = 1;
            for (int k = 0; k < eqColumns; k++) s /= ndv(leaf, index.def().columns().get(k));
            return s;
        }

        // ---------------- estimates ----------------

        double selectivity(Expr e) {
            if (e == null) return 1;
            return switch (e) {
                case Expr.And a -> selectivity(a.left()) * selectivity(a.right());
                case Expr.Or o -> {
                    double l = selectivity(o.left());
                    double r = selectivity(o.right());
                    yield Math.min(1, l + r - l * r);
                }
                case Expr.Not n -> 1 - selectivity(n.operand());
                case Expr.Compare c -> {
                    Expr.Col lc = c.left() instanceof Expr.Col x ? x : null;
                    Expr.Col rc = c.right() instanceof Expr.Col x ? x : null;
                    if (c.op() == BinaryOp.EQ) {
                        double d = Math.max(lc == null ? 1 : ndvOf(lc.index()), rc == null ? 1 : ndvOf(rc.index()));
                        yield lc == null && rc == null ? 0.005 : 1 / Math.max(1, d);
                    }
                    if (c.op() == BinaryOp.NE) yield 1 - (lc == null ? 0.005 : 1 / Math.max(1, ndvOf(lc.index())));
                    yield 1.0 / 3;
                }
                case Expr.InList in -> in.operand() instanceof Expr.Col c ? Math.min(1, in.list().size() / Math.max(1, ndvOf(c.index()))) : 0.1;
                case Expr.IsNull n -> n.not() ? 0.95 : 0.05;
                case Expr.Like l -> 0.1;
                case Expr.Const k -> Boolean.TRUE.equals(k.value()) ? 1 : 0;
                default -> 1.0 / 3;
            };
        }

        double ndvOf(int column) {
            Leaf l = leafOf(column);
            return l.table == null ? Math.max(1, l.rows) : ndv(l, column - l.offset);
        }
    }

    private static int[] range(int offset, int width) {
        int[] out = new int[width];
        for (int i = 0; i < width; i++) out[i] = offset + i;
        return out;
    }

    private static int[] concat(int[] a, int[] b) {
        int[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static Set<Integer> toSet(int[] a) {
        Set<Integer> s = new HashSet<>();
        for (int x : a) s.add(x);
        return s;
    }

    /** Rewrites scope positions into positions of a plan's output. */
    static Expr remap(Expr e, int[] layout, int scopeSize) {
        int[] map = new int[scopeSize];
        Arrays.fill(map, -1);
        for (int i = 0; i < layout.length; i++) map[layout[i]] = i;
        for (int c : Expr.columns(e)) if (map[c] < 0) throw new IllegalStateException("column " + c + " isn't available here");
        return Expr.remap(e, map);
    }

    private double tableRows(Table t) {
        long rows = db.estimatedRows(t);
        if (rows == 0) rows = (long) t.heap().pageCount() * 40;
        return Math.max(1, rows);
    }

    /** Distinct values in a column: from ANALYZE if run, every row for a unique column, else PostgreSQL's default of 200. */
    private double ndv(Leaf leaf, int column) {
        Stats s = db.catalog().stats(leaf.table.def().name());
        if (s != null && s.distinct() != null && column < s.distinct().length && s.distinct()[column] > 0) return s.distinct()[column];
        for (IndexDef i : leaf.table.def().indexes()) {
            if (i.unique() && i.columns().size() == 1 && i.columns().get(0) == column) return Math.max(1, leaf.rows);
        }
        return Math.max(1, Math.min(leaf.rows, 200));
    }

    private double groupEstimate(List<Expr> groups, FromPlanner from, double rows) {
        double g = 1;
        for (Expr e : groups) g *= e instanceof Expr.Col c && from.scope.size() > 0 ? from.ndvOf(c.index()) : 10;
        return Math.min(g, rows);
    }

    // ---------------- access paths (shared with UPDATE and DELETE) ----------------

    /**
     * How to read one table given conditions on its columns (positions are
     * the table's own): a sequential scan, or an index range scan plus the
     * conditions the index doesn't cover.
     */
    public record AccessPath(Index index, List<IndexProbe> probes, List<Expr> residual, double cost) {}

    public AccessPath accessPath(Table table, List<Expr> conds, double rows) {
        double pages = Math.max(1, table.heap().pageCount());
        AccessPath best = new AccessPath(null, List.of(), conds, pages + rows * 0.01);
        // Conditions of the form column OP value, where the value doesn't depend on the row.
        record Pred(int column, BinaryOp op, Expr value, Expr source) {}
        record InPred(int column, List<Expr> values, Expr source) {}
        List<Pred> preds = new ArrayList<>();
        List<InPred> ins = new ArrayList<>();
        for (Expr c : conds) {
            if (c instanceof Expr.Compare cmp && cmp.op() != BinaryOp.NE) {
                if (cmp.left() instanceof Expr.Col col && Expr.columns(cmp.right()).isEmpty() && indexable(col, cmp.right())) {
                    preds.add(new Pred(col.index(), cmp.op(), cmp.right(), c));
                } else if (cmp.right() instanceof Expr.Col col && Expr.columns(cmp.left()).isEmpty() && indexable(col, cmp.left())) {
                    preds.add(new Pred(col.index(), cmp.op().flip(), cmp.left(), c));
                }
            } else if (c instanceof Expr.InList in && !in.not() && in.operand() instanceof Expr.Col col
                    && in.list().stream().allMatch(v -> Expr.columns(v).isEmpty() && indexable(col, v))) {
                ins.add(new InPred(col.index(), in.list(), c));
            }
        }
        for (Index index : table.indexes()) {
            List<List<Expr>> eqValues = new ArrayList<>(); // per index column: one value, or several for IN
            Set<Expr> used = new LinkedHashSet<>();
            boolean listUsed = false;
            Pred low = null;
            Pred high = null;
            for (int col : index.def().columns()) {
                Pred eq = preds.stream().filter(p -> p.column() == col && p.op() == BinaryOp.EQ).findFirst().orElse(null);
                if (eq != null) {
                    eqValues.add(List.of(eq.value()));
                    used.add(eq.source());
                    continue;
                }
                InPred in = listUsed ? null : ins.stream().filter(p -> p.column() == col).findFirst().orElse(null);
                if (in != null) {
                    eqValues.add(in.values());
                    used.add(in.source());
                    listUsed = true;
                    continue;
                }
                low = preds.stream().filter(p -> p.column() == col && (p.op() == BinaryOp.GT || p.op() == BinaryOp.GE)).findFirst().orElse(null);
                high = preds.stream().filter(p -> p.column() == col && (p.op() == BinaryOp.LT || p.op() == BinaryOp.LE)).findFirst().orElse(null);
                if (low != null) used.add(low.source());
                if (high != null) used.add(high.source());
                break;
            }
            if (eqValues.isEmpty() && low == null && high == null) continue;
            // Expand an IN list into one probe per value.
            List<List<Expr>> combos = new ArrayList<>();
            combos.add(new ArrayList<>());
            for (List<Expr> options : eqValues) {
                List<List<Expr>> next = new ArrayList<>();
                for (List<Expr> prefix : combos) {
                    for (Expr v : options) {
                        List<Expr> p = new ArrayList<>(prefix);
                        p.add(v);
                        next.add(p);
                    }
                }
                combos = next;
            }
            List<IndexProbe> probes = new ArrayList<>();
            for (List<Expr> eq : combos) {
                probes.add(new IndexProbe(eq, low == null ? null : low.value(), low != null && low.op() == BinaryOp.GE,
                        high == null ? null : high.value(), high != null && high.op() == BinaryOp.LE));
            }
            double sel;
            if (index.def().unique() && eqValues.size() == index.def().columns().size() && low == null && high == null) {
                sel = combos.size() / Math.max(1, rows);
            } else {
                sel = combos.size();
                for (int k = 0; k < eqValues.size(); k++) sel /= ndvForTable(table, index.def().columns().get(k), rows);
                if (low != null || high != null) sel *= low != null && high != null ? 0.1 : 1.0 / 3;
            }
            double matches = Math.max(1, rows * Math.min(1, sel));
            double cost = combos.size() * Math.log(rows + 2) / Math.log(2) * 0.2 + matches * 1.2;
            if (cost < best.cost()) {
                List<Expr> residual = new ArrayList<>(conds);
                residual.removeAll(used);
                best = new AccessPath(index, probes, residual, cost);
            }
        }
        return best;
    }

    /** An index can serve {@code column OP value} when the types line up (numbers with numbers, text with text...). */
    private static boolean indexable(Expr.Col col, Expr value) {
        Type v = value.type();
        return v == col.type() || (v.isNumeric() && col.type().isNumeric());
    }

    private double ndvForTable(Table t, int column, double rows) {
        Stats s = db.catalog().stats(t.def().name());
        if (s != null && s.distinct() != null && column < s.distinct().length && s.distinct()[column] > 0) return s.distinct()[column];
        for (IndexDef i : t.def().indexes()) {
            if (i.unique() && i.columns().size() == 1 && i.columns().get(0) == column) return Math.max(1, rows);
        }
        return Math.max(1, Math.min(rows, 200));
    }

    public double estimatedRows(Table t) {
        return tableRows(t);
    }
}
