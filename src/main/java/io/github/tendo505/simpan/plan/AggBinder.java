package io.github.tendo505.simpan.plan;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.exec.Expr;
import io.github.tendo505.simpan.exec.Functions;
import io.github.tendo505.simpan.exec.Operators.AggCall;
import io.github.tendo505.simpan.sql.Ast;
import io.github.tendo505.simpan.types.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Binds expressions that run after GROUP BY. The row they see is the
 * aggregation's output: the group keys, then the aggregates. So a group key
 * becomes a reference to its slot, an aggregate call is registered (once,
 * however often it's written) and becomes a reference to its slot, and any
 * other column is an error.
 */
final class AggBinder extends Binder {
    private final Binder input;     // binds in the FROM scope
    private final List<Expr> groups; // bound in the FROM scope
    private final List<AggCall> aggs;

    AggBinder(Binder input, List<Expr> groups, List<AggCall> aggs, String context) {
        super(input.scope(), input.subqueries, input.paramTypes, context);
        this.input = input;
        this.groups = groups;
        this.aggs = aggs;
    }

    @Override
    public Expr bind(Ast.Expr e) {
        if (e instanceof Ast.FunctionCall f && Functions.isAggregate(f.name())) return aggregate(f);
        if (!hasAggregate(e) && !(e instanceof Ast.Literal) && !(e instanceof Ast.Param)) {
            Expr b = input.bind(e);
            int g = groups.indexOf(b);
            if (g >= 0) return new Expr.Col(g, b.type(), b.sql());
            if (Expr.columns(b).isEmpty()) return b; // constants, parameters and uncorrelated subqueries are fine
            if (e instanceof Ast.ColumnRef c) {
                String name = c.qualifier() == null ? c.name() : c.qualifier() + "." + c.name();
                throw new SqlException(SqlException.GROUPING, "column \"" + name + "\" must appear in the GROUP BY clause or be used in an aggregate function");
            }
        }
        return bindNode(e);
    }

    private Expr aggregate(Ast.FunctionCall f) {
        for (Ast.Expr a : f.args()) {
            if (hasAggregate(a)) throw new SqlException(SqlException.GROUPING, "aggregate function calls cannot be nested");
        }
        String fn = f.name();
        Expr arg = null;
        Expr separator = null;
        Type type;
        if (f.star()) {
            if (!fn.equals("count")) throw new SqlException(SqlException.SYNTAX, fn + "(*) is not allowed; only count(*)");
            type = Type.BIGINT;
        } else {
            int want = fn.equals("string_agg") ? 2 : 1;
            if (f.args().size() != want) {
                throw new SqlException(SqlException.UNDEFINED_FUNCTION, "function " + fn + " takes " + want + " argument" + (want == 1 ? "" : "s"));
            }
            arg = input.bind(f.args().get(0));
            type = switch (fn) {
                case "count" -> Type.BIGINT;
                case "sum" -> {
                    arg = numeric(arg, fn);
                    yield arg.type();
                }
                case "avg" -> {
                    arg = numeric(arg, fn);
                    yield Type.DOUBLE;
                }
                case "min", "max" -> {
                    if (arg.type() == Type.UNKNOWN) arg = input.coerce(arg, Type.TEXT);
                    yield arg.type();
                }
                case "bool_and", "bool_or", "every" -> {
                    arg = input.coerce(arg, Type.BOOLEAN);
                    if (arg.type() != Type.BOOLEAN) throw new SqlException(SqlException.UNDEFINED_FUNCTION, "function " + fn + "(" + arg.type().sqlName() + ") does not exist");
                    yield Type.BOOLEAN;
                }
                case "string_agg" -> {
                    arg = input.coerce(arg, Type.TEXT);
                    separator = input.coerce(input.bind(f.args().get(1)), Type.TEXT);
                    if (arg.type() != Type.TEXT || separator.type() != Type.TEXT) {
                        throw new SqlException(SqlException.UNDEFINED_FUNCTION, "function string_agg(" + arg.type().sqlName() + ", " + separator.type().sqlName() + ") does not exist");
                    }
                    yield Type.TEXT;
                }
                default -> throw new IllegalStateException(fn);
            };
        }
        AggCall call = new AggCall(fn.equals("every") ? "bool_and" : fn, arg, f.distinct(), type, separator);
        int i = aggs.indexOf(call);
        if (i < 0) {
            aggs.add(call);
            i = aggs.size() - 1;
        }
        return new Expr.Col(groups.size() + i, type, call.sql());
    }

    private Expr numeric(Expr arg, String fn) {
        Expr a = arg.type() == Type.UNKNOWN ? input.coerce(arg, Type.DOUBLE) : arg;
        if (!a.type().isNumeric()) throw new SqlException(SqlException.UNDEFINED_FUNCTION, "function " + fn + "(" + a.type().sqlName() + ") does not exist");
        return a;
    }

    static List<AggCall> newList() {
        return new ArrayList<>();
    }
}
