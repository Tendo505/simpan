package io.github.tendo505.simpan.plan;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.exec.Expr;
import io.github.tendo505.simpan.exec.Functions;
import io.github.tendo505.simpan.exec.SubPlan;
import io.github.tendo505.simpan.sql.Ast;
import io.github.tendo505.simpan.sql.Ast.BinaryOp;
import io.github.tendo505.simpan.types.Type;
import io.github.tendo505.simpan.types.Values;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns parsed expressions into bound ones: names become column positions,
 * and types are checked the way PostgreSQL checks them. An untyped string
 * literal or parameter takes its type from context, so
 * {@code id = '42'} compares numbers and {@code $1} in {@code WHERE id = $1}
 * is a bigint.
 */
public class Binder {
    /** Plans a subquery found inside an expression. */
    public interface Subqueries {
        SubPlan plan(Ast.Query query, Scope outer);
    }

    protected final Scope scope;
    protected final Subqueries subqueries;
    protected final Map<Integer, Type> paramTypes;
    private final String context;

    /** {@code context} names the clause, for errors like "aggregate functions are not allowed in WHERE". */
    public Binder(Scope scope, Subqueries subqueries, Map<Integer, Type> paramTypes, String context) {
        this.scope = scope;
        this.subqueries = subqueries;
        this.paramTypes = paramTypes;
        this.context = context;
    }

    public Scope scope() {
        return scope;
    }

    public Expr bind(Ast.Expr e) {
        return bindNode(e);
    }

    /** Binds a condition (WHERE, ON, HAVING): it must be boolean. */
    public Expr bindCondition(Ast.Expr e) {
        Expr b = coerce(bind(e), Type.BOOLEAN);
        if (b.type() != Type.BOOLEAN) {
            throw new SqlException(SqlException.DATATYPE_MISMATCH, "argument of " + context + " must be type boolean, not type " + b.type().sqlName());
        }
        return b;
    }

    protected final Expr bindNode(Ast.Expr e) {
        return switch (e) {
            case Ast.Literal l -> new Expr.Const(l.value(), l.type());
            case Ast.Param p -> {
                Type known = paramTypes.getOrDefault(p.index(), Type.UNKNOWN);
                yield new Expr.Param(p.index(), known);
            }
            case Ast.ColumnRef c -> {
                int i = scope.resolve(c.qualifier(), c.name());
                Scope.Entry entry = scope.get(i);
                yield new Expr.Col(i, entry.type(), entry.display());
            }
            case Ast.Unary u -> switch (u.op()) {
                case NOT -> new Expr.Not(booleanOperand(bind(u.operand()), "NOT"));
                case NEGATE -> {
                    Expr x = coerceNumeric(bind(u.operand()), "-");
                    yield new Expr.Negate(x);
                }
            };
            case Ast.Binary b -> binary(b);
            case Ast.IsNull n -> new Expr.IsNull(bind(n.operand()), n.not());
            case Ast.IsBool n -> new Expr.IsBool(booleanOperand(bind(n.operand()), "IS TRUE"), n.value(), n.not());
            case Ast.IsDistinct d -> {
                Expr[] lr = comparable(bind(d.left()), bind(d.right()), "IS DISTINCT FROM");
                yield new Expr.IsDistinct(lr[0], lr[1], d.not());
            }
            case Ast.Between b -> {
                Ast.Expr x = b.operand();
                Ast.Expr inRange = new Ast.Binary(BinaryOp.AND, new Ast.Binary(BinaryOp.GE, x, b.low()), new Ast.Binary(BinaryOp.LE, x, b.high()));
                if (b.symmetric()) {
                    Ast.Expr swapped = new Ast.Binary(BinaryOp.AND, new Ast.Binary(BinaryOp.GE, x, b.high()), new Ast.Binary(BinaryOp.LE, x, b.low()));
                    inRange = new Ast.Binary(BinaryOp.OR, inRange, swapped);
                }
                yield bind(b.not() ? new Ast.Unary(Ast.UnaryOp.NOT, inRange) : inRange);
            }
            case Ast.InList in -> {
                Expr x = bind(in.operand());
                List<Expr> items = new ArrayList<>();
                for (Ast.Expr item : in.list()) items.add(bind(item));
                Type t = x.type();
                if (t == Type.UNKNOWN) {
                    List<Type> all = new ArrayList<>();
                    for (Expr i : items) all.add(i.type());
                    t = Functions.common("IN", all);
                    x = coerce(x, t);
                }
                List<Expr> coerced = new ArrayList<>();
                for (Expr i : items) coerced.add(comparable(x, i, "IN")[1]);
                yield new Expr.InList(x, coerced, in.not());
            }
            case Ast.InSubquery in -> {
                Expr x = bind(in.operand());
                SubPlan plan = subqueries.plan(in.query(), scope);
                if (plan.plan().width() != 1) throw new SqlException(SqlException.SYNTAX, "subquery has too many columns");
                Type t = plan.plan().types().get(0);
                x = coerce(x, t);
                comparable(x, new Expr.Const(null, t), "IN");
                yield new Expr.InSubquery(x, plan, in.not());
            }
            case Ast.Exists ex -> new Expr.Exists(subqueries.plan(ex.query(), scope), ex.not());
            case Ast.ScalarSubquery s -> {
                SubPlan plan = subqueries.plan(s.query(), scope);
                if (plan.plan().width() != 1) throw new SqlException(SqlException.SYNTAX, "subquery must return only one column");
                yield new Expr.ScalarSubquery(plan, plan.plan().types().get(0));
            }
            case Ast.Like l -> {
                Expr x = textOperand(bind(l.operand()), "LIKE");
                Expr p = textOperand(bind(l.pattern()), "LIKE");
                yield new Expr.Like(x, p, l.not(), l.caseInsensitive());
            }
            case Ast.Cast c -> {
                Expr x = bind(c.operand());
                if (x instanceof Expr.Const k) yield new Expr.Const(Values.cast(k.value(), c.type()), c.type());
                if (x instanceof Expr.Param p && p.type() == Type.UNKNOWN) yield coerce(x, c.type());
                yield x.type() == c.type() ? x : new Expr.Cast(x, c.type());
            }
            case Ast.Case c -> caseExpr(c);
            case Ast.FunctionCall f -> call(f);
            case Ast.Default d -> throw new SqlException(SqlException.SYNTAX, "DEFAULT is not allowed in this context");
        };
    }

    // ---------------- operators ----------------

    private Expr binary(Ast.Binary b) {
        BinaryOp op = b.op();
        if (op == BinaryOp.AND || op == BinaryOp.OR) {
            Expr l = booleanOperand(bind(b.left()), op.symbol);
            Expr r = booleanOperand(bind(b.right()), op.symbol);
            return op == BinaryOp.AND ? new Expr.And(l, r) : new Expr.Or(l, r);
        }
        Expr l = bind(b.left());
        Expr r = bind(b.right());
        if (op.isComparison()) {
            Expr[] lr = comparable(l, r, op.symbol);
            return new Expr.Compare(op, lr[0], lr[1]);
        }
        if (op == BinaryOp.CONCAT) {
            return new Expr.Concat(l.type() == Type.UNKNOWN ? coerce(l, Type.TEXT) : l, r.type() == Type.UNKNOWN ? coerce(r, Type.TEXT) : r);
        }
        // arithmetic
        if (l.type() == Type.UNKNOWN && r.type() == Type.UNKNOWN) {
            l = coerce(l, Type.BIGINT);
            r = coerce(r, Type.BIGINT);
        } else if (l.type() == Type.UNKNOWN) {
            l = coerce(l, r.type());
        } else if (r.type() == Type.UNKNOWN) {
            r = coerce(r, l.type());
        }
        if (!l.type().isNumeric() || !r.type().isNumeric()) throw noOperator(op.symbol, l.type(), r.type());
        Type t = l.type() == Type.DOUBLE || r.type() == Type.DOUBLE ? Type.DOUBLE : Type.BIGINT;
        return new Expr.Arith(op, l, r, t);
    }

    /** Makes two sides comparable: untyped sides take the other's type; numbers compare with numbers. */
    protected Expr[] comparable(Expr l, Expr r, String op) {
        if (l.type() == Type.UNKNOWN && r.type() == Type.UNKNOWN) {
            l = coerce(l, Type.TEXT);
            r = coerce(r, Type.TEXT);
        } else if (l.type() == Type.UNKNOWN) {
            l = coerce(l, r.type());
        } else if (r.type() == Type.UNKNOWN) {
            r = coerce(r, l.type());
        }
        boolean ok = l.type() == r.type() || (l.type().isNumeric() && r.type().isNumeric());
        if (!ok) throw noOperator(op, l.type(), r.type());
        return new Expr[] {l, r};
    }

    private static SqlException noOperator(String op, Type l, Type r) {
        return new SqlException(SqlException.UNDEFINED_FUNCTION, "operator does not exist: " + l.sqlName() + " " + op + " " + r.sqlName());
    }

    private Expr booleanOperand(Expr e, String op) {
        Expr b = coerce(e, Type.BOOLEAN);
        if (b.type() != Type.BOOLEAN) {
            throw new SqlException(SqlException.DATATYPE_MISMATCH, "argument of " + op + " must be type boolean, not type " + b.type().sqlName());
        }
        return b;
    }

    private Expr textOperand(Expr e, String op) {
        Expr t = coerce(e, Type.TEXT);
        if (t.type() != Type.TEXT) throw noOperator(op, e.type(), Type.TEXT);
        return t;
    }

    private Expr coerceNumeric(Expr e, String op) {
        if (e.type() == Type.UNKNOWN) return coerce(e, Type.BIGINT);
        if (!e.type().isNumeric()) {
            throw new SqlException(SqlException.UNDEFINED_FUNCTION, "operator does not exist: " + op + " " + e.type().sqlName());
        }
        return e;
    }

    /**
     * Gives an untyped expression (a string literal, NULL, or a parameter
     * seen for the first time) the type the context wants, and widens bigint
     * to double precision. Anything else is returned as is, for the caller to
     * reject if it doesn't fit.
     */
    public Expr coerce(Expr e, Type target) {
        if (e.type() == target || target == Type.UNKNOWN) return e;
        if (e instanceof Expr.Const c && c.type() == Type.UNKNOWN) return new Expr.Const(Values.cast(c.value(), target), target);
        if (e instanceof Expr.Param p && p.type() == Type.UNKNOWN) {
            paramTypes.put(p.index(), target);
            return new Expr.Param(p.index(), target);
        }
        if (e.type() == Type.BIGINT && target == Type.DOUBLE) {
            return e instanceof Expr.Const c ? new Expr.Const(Values.cast(c.value(), Type.DOUBLE), Type.DOUBLE) : new Expr.Cast(e, Type.DOUBLE);
        }
        return e;
    }

    // ---------------- CASE and functions ----------------

    private Expr caseExpr(Ast.Case c) {
        List<Expr.When> whens = new ArrayList<>();
        List<Expr> results = new ArrayList<>();
        Expr operand = c.operand() == null ? null : bind(c.operand());
        for (Ast.When w : c.whens()) {
            Expr cond;
            if (operand == null) {
                cond = booleanOperand(bind(w.condition()), "CASE/WHEN");
            } else {
                Expr[] lr = comparable(operand, bind(w.condition()), "=");
                cond = new Expr.Compare(BinaryOp.EQ, lr[0], lr[1]);
            }
            Expr result = bind(w.result());
            whens.add(new Expr.When(cond, result));
            results.add(result);
        }
        Expr otherwise = c.otherwise() == null ? null : bind(c.otherwise());
        if (otherwise != null) results.add(otherwise);
        List<Type> types = new ArrayList<>();
        for (Expr r : results) types.add(r.type());
        Type t = Functions.common("CASE", types);
        List<Expr.When> typed = new ArrayList<>();
        for (Expr.When w : whens) typed.add(new Expr.When(w.condition(), coerce(w.result(), t)));
        return new Expr.Case(typed, otherwise == null ? null : coerce(otherwise, t), t);
    }

    protected Expr call(Ast.FunctionCall f) {
        if (Functions.isAggregate(f.name())) {
            throw new SqlException(SqlException.GROUPING, "aggregate functions are not allowed in " + context);
        }
        if (f.star() || f.distinct()) throw new SqlException(SqlException.SYNTAX, (f.star() ? "*" : "DISTINCT") + " specified, but " + f.name() + " is not an aggregate function");
        List<Expr> args = new ArrayList<>();
        List<Type> types = new ArrayList<>();
        for (Ast.Expr a : f.args()) {
            Expr b = bind(a);
            args.add(b);
            types.add(b.type());
        }
        Functions.Resolved r = Functions.resolve(f.name(), types);
        List<Expr> typed = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            Type want = r.argTypes().get(i);
            Expr a = coerce(args.get(i), want);
            if (want != Type.UNKNOWN && a.type() != want) a = new Expr.Cast(a, want);
            typed.add(a);
        }
        return new Expr.Call(f.name(), typed, r.result(), r.impl());
    }

    /** True if the parsed expression contains an aggregate call (not looking inside subqueries). */
    public static boolean hasAggregate(Ast.Expr e) {
        if (e == null) return false;
        return switch (e) {
            case Ast.FunctionCall f -> Functions.isAggregate(f.name()) || f.args().stream().anyMatch(Binder::hasAggregate);
            case Ast.Unary u -> hasAggregate(u.operand());
            case Ast.Binary b -> hasAggregate(b.left()) || hasAggregate(b.right());
            case Ast.IsNull n -> hasAggregate(n.operand());
            case Ast.IsBool n -> hasAggregate(n.operand());
            case Ast.IsDistinct d -> hasAggregate(d.left()) || hasAggregate(d.right());
            case Ast.Between b -> hasAggregate(b.operand()) || hasAggregate(b.low()) || hasAggregate(b.high());
            case Ast.InList i -> hasAggregate(i.operand()) || i.list().stream().anyMatch(Binder::hasAggregate);
            case Ast.InSubquery i -> hasAggregate(i.operand());
            case Ast.Like l -> hasAggregate(l.operand()) || hasAggregate(l.pattern());
            case Ast.Cast c -> hasAggregate(c.operand());
            case Ast.Case c -> hasAggregate(c.operand()) || hasAggregate(c.otherwise())
                    || c.whens().stream().anyMatch(w -> hasAggregate(w.condition()) || hasAggregate(w.result()));
            default -> false;
        };
    }
}
