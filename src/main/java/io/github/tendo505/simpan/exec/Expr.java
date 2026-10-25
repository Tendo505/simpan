package io.github.tendo505.simpan.exec;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.sql.Ast.BinaryOp;
import io.github.tendo505.simpan.types.Type;
import io.github.tendo505.simpan.types.Values;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * An expression with every name resolved: columns are positions in the input
 * row. Evaluation follows SQL's three-valued logic, where a boolean can be
 * true, false or unknown (null), and almost anything with a NULL input is
 * NULL.
 */
public sealed interface Expr {
    Type type();

    Object eval(Object[] row, ExecContext ctx);

    /** How EXPLAIN prints it. */
    String sql();

    /** A copy with each direct child replaced; used to remap column positions. */
    Expr withChildren(UnaryOperator<Expr> f);

    // ---------------- leaves ----------------

    record Const(Object value, Type type) implements Expr {
        public Object eval(Object[] row, ExecContext ctx) {
            return value;
        }

        public String sql() {
            if (value == null) return "NULL";
            if (value instanceof String s) return "'" + s.replace("'", "''") + "'";
            if (value instanceof Boolean b) return b ? "true" : "false";
            return Values.format(value);
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return this;
        }
    }

    /** A column of the input row. Two are equal if they point at the same position, whatever they're called. */
    record Col(int index, Type type, String name) implements Expr {
        public Object eval(Object[] row, ExecContext ctx) {
            return row[index];
        }

        public String sql() {
            return name;
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return this;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Col c && c.index == index;
        }

        @Override
        public int hashCode() {
            return index;
        }
    }

    /** {@code $n}: a value bound when the statement runs, converted to the type the context needs. */
    record Param(int index, Type type) implements Expr {
        public Object eval(Object[] row, ExecContext ctx) {
            Object v = ctx.param(index);
            return type == Type.UNKNOWN ? v : Values.cast(v, type);
        }

        public String sql() {
            return "$" + index;
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return this;
        }
    }

    // ---------------- logic ----------------

    record Not(Expr operand) implements Expr {
        public Type type() {
            return Type.BOOLEAN;
        }

        public Object eval(Object[] row, ExecContext ctx) {
            Object v = operand.eval(row, ctx);
            return v == null ? null : !(Boolean) v;
        }

        public String sql() {
            return "(NOT " + operand.sql() + ")";
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return new Not(f.apply(operand));
        }
    }

    record And(Expr left, Expr right) implements Expr {
        public Type type() {
            return Type.BOOLEAN;
        }

        /** False wins over unknown: {@code false AND NULL} is false. */
        public Object eval(Object[] row, ExecContext ctx) {
            Object l = left.eval(row, ctx);
            if (Boolean.FALSE.equals(l)) return false;
            Object r = right.eval(row, ctx);
            if (Boolean.FALSE.equals(r)) return false;
            return l == null || r == null ? null : true;
        }

        public String sql() {
            return "(" + left.sql() + " AND " + right.sql() + ")";
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return new And(f.apply(left), f.apply(right));
        }
    }

    record Or(Expr left, Expr right) implements Expr {
        public Type type() {
            return Type.BOOLEAN;
        }

        /** True wins over unknown: {@code true OR NULL} is true. */
        public Object eval(Object[] row, ExecContext ctx) {
            Object l = left.eval(row, ctx);
            if (Boolean.TRUE.equals(l)) return true;
            Object r = right.eval(row, ctx);
            if (Boolean.TRUE.equals(r)) return true;
            return l == null || r == null ? null : false;
        }

        public String sql() {
            return "(" + left.sql() + " OR " + right.sql() + ")";
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return new Or(f.apply(left), f.apply(right));
        }
    }

    record Compare(BinaryOp op, Expr left, Expr right) implements Expr {
        public Type type() {
            return Type.BOOLEAN;
        }

        public Object eval(Object[] row, ExecContext ctx) {
            Object l = left.eval(row, ctx);
            if (l == null) return null;
            Object r = right.eval(row, ctx);
            if (r == null) return null;
            int c = Values.compare(l, r);
            return switch (op) {
                case EQ -> c == 0;
                case NE -> c != 0;
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                case GE -> c >= 0;
                default -> throw new IllegalStateException(op.toString());
            };
        }

        public String sql() {
            return "(" + left.sql() + " " + op.symbol + " " + right.sql() + ")";
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return new Compare(op, f.apply(left), f.apply(right));
        }
    }

    record IsNull(Expr operand, boolean not) implements Expr {
        public Type type() {
            return Type.BOOLEAN;
        }

        public Object eval(Object[] row, ExecContext ctx) {
            return (operand.eval(row, ctx) == null) != not;
        }

        public String sql() {
            return "(" + operand.sql() + (not ? " IS NOT NULL)" : " IS NULL)");
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return new IsNull(f.apply(operand), not);
        }
    }

    /** {@code x IS [NOT] TRUE/FALSE}: never unknown. */
    record IsBool(Expr operand, boolean value, boolean not) implements Expr {
        public Type type() {
            return Type.BOOLEAN;
        }

        public Object eval(Object[] row, ExecContext ctx) {
            Object v = operand.eval(row, ctx);
            return (v != null && (Boolean) v == value) != not;
        }

        public String sql() {
            return "(" + operand.sql() + " IS " + (not ? "NOT " : "") + (value ? "TRUE)" : "FALSE)");
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return new IsBool(f.apply(operand), value, not);
        }
    }

    /** {@code IS [NOT] DISTINCT FROM}: like = and <>, but NULL equals NULL and the answer is never unknown. */
    record IsDistinct(Expr left, Expr right, boolean not) implements Expr {
        public Type type() {
            return Type.BOOLEAN;
        }

        public Object eval(Object[] row, ExecContext ctx) {
            Object l = left.eval(row, ctx);
            Object r = right.eval(row, ctx);
            boolean distinct = l == null || r == null ? (l == null) != (r == null) : Values.compare(l, r) != 0;
            return distinct != not;
        }

        public String sql() {
            return "(" + left.sql() + " IS " + (not ? "NOT " : "") + "DISTINCT FROM " + right.sql() + ")";
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return new IsDistinct(f.apply(left), f.apply(right), not);
        }
    }

    /** {@code x IN (a, b, ...)}: true if any equal; otherwise unknown if x or any item is NULL. */
    record InList(Expr operand, List<Expr> list, boolean not) implements Expr {
        public Type type() {
            return Type.BOOLEAN;
        }

        public Object eval(Object[] row, ExecContext ctx) {
            Object v = operand.eval(row, ctx);
            if (v == null) return null;
            boolean sawNull = false;
            for (Expr e : list) {
                Object item = e.eval(row, ctx);
                if (item == null) {
                    sawNull = true;
                } else if (Values.compare(v, item) == 0) {
                    return !not;
                }
            }
            return sawNull ? null : not;
        }

        public String sql() {
            List<String> items = new ArrayList<>();
            for (Expr e : list) items.add(e.sql());
            return "(" + operand.sql() + (not ? " NOT IN (" : " IN (") + String.join(", ", items) + "))";
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            List<Expr> items = new ArrayList<>();
            for (Expr e : list) items.add(f.apply(e));
            return new InList(f.apply(operand), items, not);
        }
    }

    // ---------------- subqueries (uncorrelated: run once per statement) ----------------

    /** {@code x IN (SELECT ...)}, with the same NULL rules as an IN list. */
    record InSubquery(Expr operand, SubPlan plan, boolean not) implements Expr {
        public Type type() {
            return Type.BOOLEAN;
        }

        public Object eval(Object[] row, ExecContext ctx) {
            SubPlan.Result r = ctx.subquery(plan);
            if (r.rows().isEmpty()) return not; // IN an empty set is false, even for NULL
            Object v = operand.eval(row, ctx);
            if (v == null) return null;
            if (r.keys().contains(Values.hashKey(v))) return !not;
            return r.hasNull() ? null : not;
        }

        public String sql() {
            return "(" + operand.sql() + (not ? " NOT IN " : " IN ") + "(SubPlan " + plan.id() + "))";
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return new InSubquery(f.apply(operand), plan, not);
        }
    }

    record Exists(SubPlan plan, boolean not) implements Expr {
        public Type type() {
            return Type.BOOLEAN;
        }

        public Object eval(Object[] row, ExecContext ctx) {
            return !ctx.subquery(plan).rows().isEmpty() != not;
        }

        public String sql() {
            return (not ? "(NOT EXISTS (SubPlan " : "(EXISTS (SubPlan ") + plan.id() + "))";
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return this;
        }
    }

    /** {@code (SELECT x ...)}: its one value, NULL if no rows, an error if more than one. */
    record ScalarSubquery(SubPlan plan, Type type) implements Expr {
        public Object eval(Object[] row, ExecContext ctx) {
            List<Object[]> rows = ctx.subquery(plan).rows();
            if (rows.isEmpty()) return null;
            if (rows.size() > 1) throw new SqlException(SqlException.CARDINALITY, "more than one row returned by a subquery used as an expression");
            return rows.get(0)[0];
        }

        public String sql() {
            return "(SubPlan " + plan.id() + ")";
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return this;
        }
    }

    // ---------------- arithmetic and text ----------------

    record Arith(BinaryOp op, Expr left, Expr right, Type type) implements Expr {
        public Object eval(Object[] row, ExecContext ctx) {
            Object l = left.eval(row, ctx);
            Object r = right.eval(row, ctx);
            return switch (op) {
                case ADD -> Values.add(l, r);
                case SUB -> Values.subtract(l, r);
                case MUL -> Values.multiply(l, r);
                case DIV -> Values.divide(l, r);
                case MOD -> Values.modulo(l, r);
                default -> throw new IllegalStateException(op.toString());
            };
        }

        public String sql() {
            return "(" + left.sql() + " " + op.symbol + " " + right.sql() + ")";
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return new Arith(op, f.apply(left), f.apply(right), type);
        }
    }

    record Negate(Expr operand) implements Expr {
        public Type type() {
            return operand.type();
        }

        public Object eval(Object[] row, ExecContext ctx) {
            return Values.negate(operand.eval(row, ctx));
        }

        public String sql() {
            return "(-" + operand.sql() + ")";
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return new Negate(f.apply(operand));
        }
    }

    /** {@code a || b}: text concatenation; NULL if either side is NULL. */
    record Concat(Expr left, Expr right) implements Expr {
        public Type type() {
            return Type.TEXT;
        }

        public Object eval(Object[] row, ExecContext ctx) {
            Object l = left.eval(row, ctx);
            Object r = right.eval(row, ctx);
            if (l == null || r == null) return null;
            return (String) Values.cast(l, Type.TEXT) + Values.cast(r, Type.TEXT);
        }

        public String sql() {
            return "(" + left.sql() + " || " + right.sql() + ")";
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return new Concat(f.apply(left), f.apply(right));
        }
    }

    /** LIKE with {@code %} and {@code _}, and backslash to escape them, as in PostgreSQL. */
    record Like(Expr operand, Expr pattern, boolean not, boolean caseInsensitive) implements Expr {
        public Type type() {
            return Type.BOOLEAN;
        }

        public Object eval(Object[] row, ExecContext ctx) {
            Object v = operand.eval(row, ctx);
            Object p = pattern.eval(row, ctx);
            if (v == null || p == null) return null;
            return ctx.likePattern((String) p, caseInsensitive).matches((String) v) != not;
        }

        public String sql() {
            return "(" + operand.sql() + (not ? " NOT" : "") + (caseInsensitive ? " ILIKE " : " LIKE ") + pattern.sql() + ")";
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return new Like(f.apply(operand), f.apply(pattern), not, caseInsensitive);
        }
    }

    record Cast(Expr operand, Type type) implements Expr {
        public Object eval(Object[] row, ExecContext ctx) {
            return Values.cast(operand.eval(row, ctx), type);
        }

        public String sql() {
            return "(" + operand.sql() + ")::" + type.sqlName();
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            return new Cast(f.apply(operand), type);
        }
    }

    record When(Expr condition, Expr result) {}

    /** A searched CASE: the first WHEN that's true wins (unknown doesn't). Simple CASE is turned into this form. */
    record Case(List<When> whens, Expr otherwise, Type type) implements Expr {
        public Object eval(Object[] row, ExecContext ctx) {
            for (When w : whens) if (Boolean.TRUE.equals(w.condition().eval(row, ctx))) return Values.cast(w.result().eval(row, ctx), type);
            return otherwise == null ? null : Values.cast(otherwise.eval(row, ctx), type);
        }

        public String sql() {
            StringBuilder sb = new StringBuilder("CASE");
            for (When w : whens) sb.append(" WHEN ").append(w.condition().sql()).append(" THEN ").append(w.result().sql());
            if (otherwise != null) sb.append(" ELSE ").append(otherwise.sql());
            return sb.append(" END").toString();
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            List<When> ws = new ArrayList<>();
            for (When w : whens) ws.add(new When(f.apply(w.condition()), f.apply(w.result())));
            return new Case(ws, otherwise == null ? null : f.apply(otherwise), type);
        }
    }

    /** A scalar function call; {@code fn} does the work. */
    record Call(String name, List<Expr> args, Type type, Functions.Impl fn) implements Expr {
        public Object eval(Object[] row, ExecContext ctx) {
            Object[] values = new Object[args.size()];
            for (int i = 0; i < values.length; i++) values[i] = args.get(i).eval(row, ctx);
            return fn.apply(values);
        }

        public String sql() {
            List<String> items = new ArrayList<>();
            for (Expr e : args) items.add(e.sql());
            return name + "(" + String.join(", ", items) + ")";
        }

        public Expr withChildren(UnaryOperator<Expr> f) {
            List<Expr> a = new ArrayList<>();
            for (Expr e : args) a.add(f.apply(e));
            return new Call(name, a, type, fn);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Call c && c.name.equals(name) && c.args.equals(args);
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + args.hashCode();
        }
    }

    // ---------------- helpers ----------------

    /** Every subquery inside the expression. */
    static List<SubPlan> subplans(Expr e) {
        List<SubPlan> out = new ArrayList<>();
        collectSubplans(e, out);
        return out;
    }

    private static void collectSubplans(Expr e, List<SubPlan> out) {
        if (e == null) return;
        switch (e) {
            case InSubquery in -> out.add(in.plan());
            case Exists ex -> out.add(ex.plan());
            case ScalarSubquery sq -> out.add(sq.plan());
            default -> { }
        }
        e.withChildren(child -> {
            collectSubplans(child, out);
            return child;
        });
    }

    /** Every column position the expression reads. */
    static Set<Integer> columns(Expr e) {
        Set<Integer> out = new HashSet<>();
        collect(e, out);
        return out;
    }

    private static void collect(Expr e, Set<Integer> out) {
        if (e instanceof Col c) out.add(c.index());
        e.withChildren(child -> {
            collect(child, out);
            return child;
        });
    }

    /** Rewrites column positions: {@code map[old]} is the new position. */
    static Expr remap(Expr e, int[] map) {
        if (e instanceof Col c) return new Col(map[c.index()], c.type(), c.name());
        return e.withChildren(child -> remap(child, map));
    }

    /** Shifts every column position by {@code delta}. */
    static Expr shift(Expr e, int delta) {
        if (e instanceof Col c) return new Col(c.index() + delta, c.type(), c.name());
        return e.withChildren(child -> shift(child, delta));
    }

    /** True if the expression reads no columns (it may still read parameters or subqueries). */
    static boolean isConstant(Expr e) {
        return columns(e).isEmpty();
    }

    /** Splits {@code a AND b AND c} into its parts. */
    static List<Expr> conjuncts(Expr e) {
        List<Expr> out = new ArrayList<>();
        if (e instanceof And a) {
            out.addAll(conjuncts(a.left()));
            out.addAll(conjuncts(a.right()));
        } else if (e != null) {
            out.add(e);
        }
        return out;
    }

    static Expr and(List<Expr> parts) {
        Expr out = null;
        for (Expr p : parts) out = out == null ? p : new And(out, p);
        return out;
    }

    /** True for a filter result that keeps the row: unknown filters it out, like false. */
    static boolean isTrue(Object v) {
        return Boolean.TRUE.equals(v);
    }
}
