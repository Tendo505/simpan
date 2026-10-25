package io.github.tendo505.simpan.sql;

import io.github.tendo505.simpan.types.Type;
import java.util.List;

/** The parsed form of SQL, before names are resolved. */
public final class Ast {
    private Ast() {}

    // ---------------- statements ----------------

    public sealed interface Statement permits CreateTable, DropTable, CreateIndex, DropIndex, Insert, Update, Delete,
            Query, Begin, Commit, Rollback, Explain, SetVar, Show, Checkpoint, Vacuum, Analyze {}

    public record ColumnDef(String name, Type type, boolean notNull, boolean primaryKey, boolean unique, Expr defaultValue) {}

    public record CreateTable(String name, boolean ifNotExists, List<ColumnDef> columns, List<String> primaryKey,
                              List<List<String>> uniques) implements Statement {}

    public record DropTable(String name, boolean ifExists) implements Statement {}

    public record CreateIndex(String name, String table, List<String> columns, boolean unique, boolean ifNotExists)
            implements Statement {}

    public record DropIndex(String name, boolean ifExists) implements Statement {}

    /** Either {@code rows} (VALUES) or {@code query} (INSERT ... SELECT) is set. */
    public record Insert(String table, List<String> columns, List<List<Expr>> rows, Query query, List<SelectItem> returning)
            implements Statement {}

    public record Assignment(String column, Expr value) {}

    public record Update(String table, String alias, List<Assignment> set, Expr where, List<SelectItem> returning)
            implements Statement {}

    public record Delete(String table, String alias, Expr where, List<SelectItem> returning) implements Statement {}

    public enum Isolation { READ_COMMITTED, REPEATABLE_READ }

    /** {@code isolation} is null for "whatever the session default is". */
    public record Begin(Isolation isolation) implements Statement {}

    public record Commit() implements Statement {}

    public record Rollback() implements Statement {}

    public record Explain(Statement statement, boolean analyze) implements Statement {}

    public record SetVar(String name, String value) implements Statement {}

    public record Show(String name) implements Statement {}

    public record Checkpoint() implements Statement {}

    public record Vacuum(String table) implements Statement {}

    public record Analyze(String table) implements Statement {}

    // ---------------- queries ----------------

    public record Query(QueryBody body, List<OrderItem> orderBy, Expr limit, Expr offset) implements Statement {}

    public sealed interface QueryBody permits SelectCore, SetOp {}

    public record SelectCore(boolean distinct, List<SelectItem> items, FromItem from, Expr where, List<Expr> groupBy,
                             Expr having) implements QueryBody {}

    public enum SetOpKind { UNION, INTERSECT, EXCEPT }

    public record SetOp(SetOpKind kind, boolean all, QueryBody left, QueryBody right) implements QueryBody {}

    public sealed interface SelectItem permits Star, ExprItem {}

    /** {@code *} or {@code t.*}; qualifier is null for a bare star. */
    public record Star(String qualifier) implements SelectItem {}

    public record ExprItem(Expr expr, String alias) implements SelectItem {}

    public record OrderItem(Expr expr, boolean descending, Boolean nullsFirst) {}

    public sealed interface FromItem permits TableRef, SubqueryRef, Join {}

    public record TableRef(String name, String alias) implements FromItem {}

    public record SubqueryRef(Query query, String alias) implements FromItem {}

    public enum JoinKind { INNER, LEFT, RIGHT, FULL, CROSS }

    public record Join(JoinKind kind, FromItem left, FromItem right, Expr on) implements FromItem {}

    // ---------------- expressions ----------------

    public sealed interface Expr permits Literal, Param, ColumnRef, Unary, Binary, IsNull, IsBool, IsDistinct, Between,
            InList, InSubquery, Exists, ScalarSubquery, Like, Cast, Case, FunctionCall, Default {}

    /** A constant; {@code type} is its SQL type (an untyped string literal is TEXT). */
    public record Literal(Object value, Type type) implements Expr {}

    /** {@code $1} is index 1. */
    public record Param(int index) implements Expr {}

    public record ColumnRef(String qualifier, String name) implements Expr {}

    public enum UnaryOp { NOT, NEGATE }

    public record Unary(UnaryOp op, Expr operand) implements Expr {}

    public enum BinaryOp {
        OR("OR"), AND("AND"), EQ("="), NE("<>"), LT("<"), LE("<="), GT(">"), GE(">="),
        ADD("+"), SUB("-"), MUL("*"), DIV("/"), MOD("%"), CONCAT("||");

        public final String symbol;

        BinaryOp(String symbol) {
            this.symbol = symbol;
        }

        public boolean isComparison() {
            return ordinal() >= EQ.ordinal() && ordinal() <= GE.ordinal();
        }

        /** {@code a < b} is {@code b > a}. */
        public BinaryOp flip() {
            return switch (this) {
                case LT -> GT;
                case LE -> GE;
                case GT -> LT;
                case GE -> LE;
                default -> this;
            };
        }
    }

    public record Binary(BinaryOp op, Expr left, Expr right) implements Expr {}

    public record IsNull(Expr operand, boolean not) implements Expr {}

    /** {@code x IS [NOT] TRUE/FALSE}. */
    public record IsBool(Expr operand, boolean value, boolean not) implements Expr {}

    public record IsDistinct(Expr left, Expr right, boolean not) implements Expr {}

    /** SYMMETRIC accepts the bounds in either order. */
    public record Between(Expr operand, Expr low, Expr high, boolean not, boolean symmetric) implements Expr {}

    public record InList(Expr operand, List<Expr> list, boolean not) implements Expr {}

    public record InSubquery(Expr operand, Query query, boolean not) implements Expr {}

    public record Exists(Query query, boolean not) implements Expr {}

    public record ScalarSubquery(Query query) implements Expr {}

    public record Like(Expr operand, Expr pattern, boolean not, boolean caseInsensitive) implements Expr {}

    public record Cast(Expr operand, Type type) implements Expr {}

    public record When(Expr condition, Expr result) {}

    /** {@code operand} is null for a searched CASE ({@code CASE WHEN cond THEN ...}). */
    public record Case(Expr operand, List<When> whens, Expr otherwise) implements Expr {}

    /** The DEFAULT keyword in an INSERT's VALUES list. */
    public record Default() implements Expr {}

    /** {@code count(*)} has {@code star} set and no arguments. */
    public record FunctionCall(String name, List<Expr> args, boolean distinct, boolean star) implements Expr {}
}
