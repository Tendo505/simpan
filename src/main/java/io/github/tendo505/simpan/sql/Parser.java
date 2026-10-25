package io.github.tendo505.simpan.sql;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.sql.Ast.*;
import io.github.tendo505.simpan.sql.Lexer.Kind;
import io.github.tendo505.simpan.sql.Lexer.Token;
import io.github.tendo505.simpan.types.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A recursive-descent parser for statements, with Pratt parsing for
 * expressions using PostgreSQL's operator precedence (loosest first): OR,
 * AND, NOT, IS, comparisons, BETWEEN/IN/LIKE, {@code ||}, {@code + -},
 * {@code * / %}, unary minus, {@code ::}.
 */
public final class Parser {
    /** Words that end an expression or a list, so they can't be bare aliases. */
    private static final Set<String> RESERVED = Set.of(
            "select", "from", "where", "group", "having", "order", "limit", "offset", "union", "intersect", "except",
            "join", "inner", "left", "right", "full", "cross", "outer", "on", "as", "and", "or", "not", "into", "values",
            "set", "returning", "when", "then", "else", "end", "is", "in", "like", "ilike", "between", "asc", "desc",
            "nulls", "using", "natural", "fetch", "for", "case", "null", "true", "false", "distinct", "all", "default");

    private final List<Token> tokens;
    private int pos;

    private Parser(String sql) {
        this.tokens = Lexer.tokenize(sql);
    }

    /** Parses one or more statements separated by semicolons. */
    public static List<Statement> parseScript(String sql) {
        Parser p = new Parser(sql);
        List<Statement> out = new ArrayList<>();
        while (true) {
            while (p.accept(";")) {
                // empty statements are fine
            }
            if (p.peek().kind() == Kind.EOF) return out;
            out.add(p.statement());
            if (p.peek().kind() != Kind.EOF) p.expect(";");
        }
    }

    /** Parses exactly one statement (a trailing semicolon is allowed). */
    public static Statement parse(String sql) {
        List<Statement> all = parseScript(sql);
        if (all.size() != 1) throw SqlException.syntax(all.isEmpty() ? "empty query" : "expected one statement, got " + all.size());
        return all.get(0);
    }

    // ---------------- token helpers ----------------

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(pos + ahead, tokens.size() - 1));
    }

    private Token next() {
        Token t = tokens.get(pos);
        if (t.kind() != Kind.EOF) pos++;
        return t;
    }

    private boolean at(String keywordOrOp) {
        return peek().is(keywordOrOp);
    }

    private boolean accept(String keywordOrOp) {
        if (at(keywordOrOp)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(String keywordOrOp) {
        if (!accept(keywordOrOp)) throw error("expected " + keywordOrOp.toUpperCase());
    }

    private SqlException error(String what) {
        Token t = peek();
        return SqlException.syntax("syntax error at or near " + t + (what == null ? "" : ": " + what) + " (position " + t.pos() + ")");
    }

    /** An identifier: unquoted (not a reserved word) or "quoted". */
    private String identifier() {
        Token t = peek();
        if (t.kind() == Kind.QUOTED_IDENT || (t.kind() == Kind.IDENT && !RESERVED.contains(t.text()))) {
            pos++;
            return t.text();
        }
        throw error("expected a name");
    }

    private boolean atIdentifier() {
        Token t = peek();
        return t.kind() == Kind.QUOTED_IDENT || (t.kind() == Kind.IDENT && !RESERVED.contains(t.text()));
    }

    private List<String> identifierList() {
        expect("(");
        List<String> names = new ArrayList<>();
        do {
            names.add(identifier());
        } while (accept(","));
        expect(")");
        return names;
    }

    // ---------------- statements ----------------

    private Statement statement() {
        Token t = peek();
        if (t.is("select") || t.is("(")) return query();
        if (t.kind() != Kind.IDENT) throw error(null);
        return switch (t.text()) {
            case "create" -> create();
            case "drop" -> drop();
            case "insert" -> insert();
            case "update" -> update();
            case "delete" -> delete();
            case "begin", "start" -> begin();
            case "commit", "end" -> {
                next();
                if (!accept("work")) accept("transaction");
                yield new Commit();
            }
            case "rollback", "abort" -> {
                next();
                if (!accept("work")) accept("transaction");
                yield new Rollback();
            }
            case "explain" -> {
                next();
                boolean analyze = accept("analyze");
                yield new Explain(statement(), analyze);
            }
            case "set" -> set();
            case "show" -> {
                next();
                yield new Show(String.join(" ", words()));
            }
            case "checkpoint" -> {
                next();
                yield new Checkpoint();
            }
            case "vacuum" -> {
                next();
                yield new Vacuum(atIdentifier() ? identifier() : null);
            }
            case "analyze" -> {
                next();
                yield new Analyze(atIdentifier() ? identifier() : null);
            }
            case "with" -> throw SqlException.unsupported("WITH (common table expressions)");
            case "alter" -> throw SqlException.unsupported("ALTER");
            default -> throw error(null);
        };
    }

    private List<String> words() {
        List<String> words = new ArrayList<>();
        while (peek().kind() == Kind.IDENT || peek().kind() == Kind.QUOTED_IDENT) words.add(next().text());
        if (words.isEmpty()) throw error("expected a name");
        return words;
    }

    private Statement create() {
        expect("create");
        boolean unique = accept("unique");
        if (accept("index")) return createIndex(unique);
        if (unique) throw error("expected INDEX");
        if (accept("table")) return createTable();
        if (at("view") || at("sequence") || at("schema") || at("database")) throw SqlException.unsupported("CREATE " + peek().text().toUpperCase());
        throw error("expected TABLE or INDEX");
    }

    private boolean ifNotExists() {
        if (accept("if")) {
            expect("not");
            expect("exists");
            return true;
        }
        return false;
    }

    private CreateTable createTable() {
        boolean ifNotExists = ifNotExists();
        String name = identifier();
        expect("(");
        List<ColumnDef> columns = new ArrayList<>();
        List<String> primaryKey = null;
        List<List<String>> uniques = new ArrayList<>();
        do {
            if (accept("constraint")) identifier(); // constraint names aren't kept
            if (accept("primary")) {
                expect("key");
                if (primaryKey != null) throw new SqlException(SqlException.INVALID_TABLE_DEFINITION, "multiple primary keys for table \"" + name + "\" are not allowed");
                primaryKey = identifierList();
            } else if (accept("unique")) {
                uniques.add(identifierList());
            } else if (at("foreign") || at("check") || at("exclude")) {
                throw SqlException.unsupported(peek().text().toUpperCase() + " constraints");
            } else {
                columns.add(columnDef());
            }
        } while (accept(","));
        expect(")");
        for (ColumnDef c : columns) {
            if (c.primaryKey()) {
                if (primaryKey != null) throw new SqlException(SqlException.INVALID_TABLE_DEFINITION, "multiple primary keys for table \"" + name + "\" are not allowed");
                primaryKey = List.of(c.name());
            }
            if (c.unique()) uniques.add(List.of(c.name()));
        }
        return new CreateTable(name, ifNotExists, columns, primaryKey, uniques);
    }

    private ColumnDef columnDef() {
        String name = identifier();
        Type type = typeName();
        boolean notNull = false;
        boolean primaryKey = false;
        boolean unique = false;
        Expr defaultValue = null;
        while (true) {
            if (accept("constraint")) identifier();
            if (accept("not")) {
                expect("null");
                notNull = true;
            } else if (accept("null")) {
                notNull = false;
            } else if (accept("primary")) {
                expect("key");
                primaryKey = true;
                notNull = true;
            } else if (accept("unique")) {
                unique = true;
            } else if (accept("default")) {
                defaultValue = expr(7); // tight enough that NOT NULL after it isn't swallowed
            } else if (at("references") || at("check") || at("generated")) {
                throw SqlException.unsupported(peek().text().toUpperCase());
            } else {
                return new ColumnDef(name, type, notNull, primaryKey, unique, defaultValue);
            }
        }
    }

    /** A type name, possibly two words ({@code double precision}) and with a length ({@code varchar(20)}). */
    private Type typeName() {
        Token t = next();
        if (t.kind() != Kind.IDENT && t.kind() != Kind.QUOTED_IDENT) {
            pos--;
            throw error("expected a type name");
        }
        String name = t.text();
        if ((name.equals("double") && accept("precision")) || (name.equals("character") && accept("varying"))) {
            name = name.equals("double") ? "double precision" : "character varying";
        }
        if (accept("(")) {
            do {
                if (peek().kind() != Kind.NUMBER) throw error("expected a number");
                next();
            } while (accept(","));
            expect(")");
        }
        if (at("[")) throw SqlException.unsupported("arrays");
        return Type.fromSql(name);
    }

    private CreateIndex createIndex(boolean unique) {
        boolean ifNotExists = ifNotExists();
        String name = identifier();
        expect("on");
        String table = identifier();
        if (accept("using")) {
            String method = identifier();
            if (!method.equals("btree")) throw SqlException.unsupported("index method " + method);
        }
        return new CreateIndex(name, table, identifierList(), unique, ifNotExists);
    }

    private Statement drop() {
        expect("drop");
        boolean table = accept("table");
        if (!table && !accept("index")) throw error("expected TABLE or INDEX");
        boolean ifExists = false;
        if (accept("if")) {
            expect("exists");
            ifExists = true;
        }
        String name = identifier();
        if (accept("cascade")) throw SqlException.unsupported("DROP ... CASCADE");
        return table ? new DropTable(name, ifExists) : new DropIndex(name, ifExists);
    }

    private Insert insert() {
        expect("insert");
        expect("into");
        String table = identifier();
        List<String> columns = null;
        if (at("(") && !peek(1).is("select")) columns = identifierList();
        List<List<Expr>> rows = null;
        Query query = null;
        if (accept("values")) {
            rows = new ArrayList<>();
            do {
                expect("(");
                List<Expr> row = new ArrayList<>();
                do {
                    row.add(accept("default") ? new Default() : expr());
                } while (accept(","));
                expect(")");
                rows.add(row);
            } while (accept(","));
        } else if (accept("default")) {
            expect("values");
            rows = List.of(List.of());
        } else {
            query = query();
        }
        if (at("on")) throw SqlException.unsupported("INSERT ... ON CONFLICT");
        return new Insert(table, columns, rows, query, returning());
    }

    private List<SelectItem> returning() {
        return accept("returning") ? selectItems() : null;
    }

    private String optionalAlias() {
        if (accept("as")) return identifier();
        return atIdentifier() ? identifier() : null;
    }

    private Update update() {
        expect("update");
        String table = identifier();
        String alias = at("set") ? null : optionalAlias();
        expect("set");
        List<Assignment> set = new ArrayList<>();
        do {
            String column = identifier();
            if (accept(".")) column = identifier(); // SET t.col = ... names the table we're updating
            expect("=");
            set.add(new Assignment(column, at("default") ? defaultMarker() : expr()));
        } while (accept(","));
        if (at("from")) throw SqlException.unsupported("UPDATE ... FROM");
        Expr where = accept("where") ? expr() : null;
        return new Update(table, alias, set, where, returning());
    }

    private Expr defaultMarker() {
        next();
        return new Default();
    }

    private Delete delete() {
        expect("delete");
        expect("from");
        String table = identifier();
        String alias = optionalAlias();
        if (at("using")) throw SqlException.unsupported("DELETE ... USING");
        Expr where = accept("where") ? expr() : null;
        return new Delete(table, alias, where, returning());
    }

    private Begin begin() {
        if (accept("start")) {
            expect("transaction");
        } else {
            expect("begin");
            if (!accept("work")) accept("transaction");
        }
        return new Begin(isolationClause());
    }

    private Isolation isolationClause() {
        Isolation isolation = null;
        while (accept("isolation")) {
            expect("level");
            isolation = isolationLevel();
            accept(",");
        }
        if (at("read") && (peek(1).is("write") || peek(1).is("only"))) {
            next();
            if (next().text().equals("only")) throw SqlException.unsupported("READ ONLY transactions");
        }
        return isolation;
    }

    private Isolation isolationLevel() {
        if (accept("read")) {
            if (accept("committed")) return Isolation.READ_COMMITTED;
            expect("uncommitted");
            return Isolation.READ_COMMITTED; // as in PostgreSQL, READ UNCOMMITTED behaves as READ COMMITTED
        }
        if (accept("repeatable")) {
            expect("read");
            return Isolation.REPEATABLE_READ;
        }
        if (accept("serializable")) throw SqlException.unsupported("SERIALIZABLE isolation (use REPEATABLE READ, which is snapshot isolation)");
        throw error("expected an isolation level");
    }

    private Statement set() {
        expect("set");
        if (!accept("session")) accept("local");
        if (accept("transaction")) {
            expect("isolation");
            expect("level");
            return new SetVar("transaction_isolation", isolationLevel() == Isolation.REPEATABLE_READ ? "repeatable read" : "read committed");
        }
        String name = String.join(".", identifierPath());
        if (!accept("=")) expect("to");
        StringBuilder value = new StringBuilder();
        while (peek().kind() != Kind.EOF && !at(";")) {
            Token t = next();
            if (!value.isEmpty() && !t.is(",")) value.append(' ');
            value.append(t.text());
        }
        return new SetVar(name, value.toString());
    }

    private List<String> identifierPath() {
        List<String> parts = new ArrayList<>();
        parts.add(next().text());
        while (accept(".")) parts.add(next().text());
        return parts;
    }

    // ---------------- queries ----------------

    private Query query() {
        QueryBody body = setOps();
        List<OrderItem> orderBy = List.of();
        if (accept("order")) {
            expect("by");
            orderBy = orderItems();
        }
        Expr limit = null;
        Expr offset = null;
        while (true) {
            if (limit == null && accept("limit")) {
                limit = accept("all") ? new Literal(null, Type.BIGINT) : expr();
            } else if (offset == null && accept("offset")) {
                offset = expr();
                if (!accept("rows")) accept("row");
            } else if (limit == null && accept("fetch")) {
                if (!accept("first")) expect("next");
                limit = at("row") || at("rows") ? new Literal(1L, Type.BIGINT) : expr();
                if (!accept("rows")) expect("row");
                expect("only");
            } else {
                break;
            }
        }
        if (at("for")) throw SqlException.unsupported("SELECT ... FOR UPDATE");
        return new Query(body, orderBy, limit, offset);
    }

    /** UNION and EXCEPT are left-associative and bind looser than INTERSECT. */
    private QueryBody setOps() {
        QueryBody left = intersects();
        while (at("union") || at("except")) {
            SetOpKind kind = next().text().equals("union") ? SetOpKind.UNION : SetOpKind.EXCEPT;
            boolean all = accept("all");
            if (!all) accept("distinct");
            left = new SetOp(kind, all, left, intersects());
        }
        return left;
    }

    private QueryBody intersects() {
        QueryBody left = queryTerm();
        while (accept("intersect")) {
            boolean all = accept("all");
            if (!all) accept("distinct");
            left = new SetOp(SetOpKind.INTERSECT, all, left, queryTerm());
        }
        return left;
    }

    private QueryBody queryTerm() {
        if (accept("(")) {
            Query inner = query();
            expect(")");
            if (!inner.orderBy().isEmpty() || inner.limit() != null || inner.offset() != null) {
                throw SqlException.unsupported("ORDER BY or LIMIT inside a parenthesized set operation");
            }
            return inner.body();
        }
        return selectCore();
    }

    private SelectCore selectCore() {
        expect("select");
        boolean distinct = accept("distinct");
        if (!distinct) accept("all");
        if (distinct && at("on")) throw SqlException.unsupported("DISTINCT ON");
        List<SelectItem> items = selectItems();
        FromItem from = null;
        if (accept("from")) {
            from = fromItem();
            while (accept(",")) from = new Join(JoinKind.CROSS, from, fromItem(), null);
        }
        Expr where = accept("where") ? expr() : null;
        List<Expr> groupBy = List.of();
        if (accept("group")) {
            expect("by");
            groupBy = new ArrayList<>();
            do {
                groupBy.add(expr());
            } while (accept(","));
        }
        Expr having = accept("having") ? expr() : null;
        if (at("window")) throw SqlException.unsupported("WINDOW");
        return new SelectCore(distinct, items, from, where, groupBy, having);
    }

    private List<SelectItem> selectItems() {
        List<SelectItem> items = new ArrayList<>();
        do {
            if (accept("*")) {
                items.add(new Star(null));
            } else if (atIdentifier() && peek(1).is(".") && peek(2).is("*")) {
                String qualifier = identifier();
                next();
                next();
                items.add(new Star(qualifier));
            } else {
                Expr e = expr();
                items.add(new ExprItem(e, optionalAlias()));
            }
        } while (accept(","));
        return items;
    }

    private List<OrderItem> orderItems() {
        List<OrderItem> items = new ArrayList<>();
        do {
            Expr e = expr();
            boolean desc = false;
            if (accept("desc")) {
                desc = true;
            } else {
                accept("asc");
            }
            Boolean nullsFirst = null;
            if (accept("nulls")) {
                if (accept("first")) {
                    nullsFirst = true;
                } else {
                    expect("last");
                    nullsFirst = false;
                }
            }
            items.add(new OrderItem(e, desc, nullsFirst));
        } while (accept(","));
        return items;
    }

    private FromItem fromItem() {
        FromItem left = fromPrimary();
        while (true) {
            JoinKind kind;
            if (accept("cross")) {
                expect("join");
                left = new Join(JoinKind.CROSS, left, fromPrimary(), null);
                continue;
            } else if (accept("join")) {
                kind = JoinKind.INNER;
            } else if (accept("inner")) {
                expect("join");
                kind = JoinKind.INNER;
            } else if (at("left") || at("right") || at("full")) {
                String word = next().text();
                accept("outer");
                expect("join");
                kind = word.equals("left") ? JoinKind.LEFT : word.equals("right") ? JoinKind.RIGHT : JoinKind.FULL;
            } else if (at("natural")) {
                throw SqlException.unsupported("NATURAL JOIN");
            } else {
                return left;
            }
            FromItem right = fromPrimary();
            if (at("using")) throw SqlException.unsupported("JOIN ... USING (write ON a.col = b.col)");
            expect("on");
            left = new Join(kind, left, right, expr());
        }
    }

    private FromItem fromPrimary() {
        if (accept("(")) {
            if (at("(")) {
                // Either a nested join, ((a JOIN b) JOIN c), or a parenthesized query: try the join first.
                int save = pos;
                try {
                    FromItem inner = fromItem();
                    expect(")");
                    return inner;
                } catch (SqlException e) {
                    pos = save;
                }
            }
            if (at("select") || at("(")) {
                Query q = query();
                expect(")");
                String alias = optionalAlias();
                if (alias == null) throw new SqlException(SqlException.SYNTAX, "subquery in FROM must have an alias");
                return new SubqueryRef(q, alias);
            }
            FromItem inner = fromItem();
            expect(")");
            return inner;
        }
        String name = identifier();
        if (at("(")) throw SqlException.unsupported("table functions");
        return new TableRef(name, optionalAlias());
    }

    // ---------------- expressions ----------------

    public Expr expr() {
        return expr(0);
    }

    private Expr expr(int minBp) {
        Expr left = prefix();
        while (true) {
            Token t = peek();
            int lbp = infixPower(t);
            if (lbp <= minBp) return left;
            left = infix(left, lbp);
        }
    }

    /** How tightly an infix operator binds, or 0 if the token doesn't continue an expression. */
    private int infixPower(Token t) {
        if (t.kind() == Kind.OP) {
            return switch (t.text()) {
                case "=", "<>", "<", "<=", ">", ">=" -> 5;
                case "||" -> 7;
                case "+", "-" -> 8;
                case "*", "/", "%" -> 9;
                case "::" -> 11;
                default -> 0;
            };
        }
        if (t.kind() != Kind.IDENT) return 0;
        return switch (t.text()) {
            case "or" -> 1;
            case "and" -> 2;
            case "is", "isnull", "notnull" -> 4;
            case "between", "in", "like", "ilike" -> 6;
            case "not" -> {
                Token n = peek(1);
                yield n.is("between") || n.is("in") || n.is("like") || n.is("ilike") ? 6 : 0;
            }
            default -> 0;
        };
    }

    private Expr infix(Expr left, int lbp) {
        Token t = next();
        String op = t.text();
        switch (op) {
            case "or":
                return new Binary(BinaryOp.OR, left, expr(1));
            case "and":
                return new Binary(BinaryOp.AND, left, expr(2));
            case "isnull":
                return new IsNull(left, false);
            case "notnull":
                return new IsNull(left, true);
            case "is": {
                boolean not = accept("not");
                if (accept("null")) return new IsNull(left, not);
                if (accept("true")) return new IsBool(left, true, not);
                if (accept("false")) return new IsBool(left, false, not);
                if (accept("unknown")) return new IsNull(left, not);
                if (accept("distinct")) {
                    expect("from");
                    return new IsDistinct(left, expr(5), not);
                }
                throw error("expected NULL, TRUE, FALSE or DISTINCT FROM after IS");
            }
            case "::":
                return new Cast(left, typeName());
            case "||":
                return new Binary(BinaryOp.CONCAT, left, expr(lbp));
            default:
                break;
        }
        if (t.kind() == Kind.OP) {
            BinaryOp bop = switch (op) {
                case "=" -> BinaryOp.EQ;
                case "<>" -> BinaryOp.NE;
                case "<" -> BinaryOp.LT;
                case "<=" -> BinaryOp.LE;
                case ">" -> BinaryOp.GT;
                case ">=" -> BinaryOp.GE;
                case "+" -> BinaryOp.ADD;
                case "-" -> BinaryOp.SUB;
                case "*" -> BinaryOp.MUL;
                case "/" -> BinaryOp.DIV;
                case "%" -> BinaryOp.MOD;
                default -> throw error(null);
            };
            if (bop.isComparison() && (at("any") || at("some") || at("all"))) throw SqlException.unsupported("ANY/ALL");
            Expr right = expr(lbp);
            if (bop.isComparison() && right instanceof Binary b && b.op().isComparison()) {
                throw SqlException.syntax("comparison operators can't be chained: write a < b AND b < c");
            }
            return new Binary(bop, left, right);
        }
        boolean not = false;
        if (op.equals("not")) {
            not = true;
            op = next().text();
        }
        switch (op) {
            case "between": {
                boolean symmetric = accept("symmetric");
                if (!symmetric) accept("asymmetric");
                Expr low = expr(6);
                expect("and");
                Expr high = expr(6);
                return new Between(left, low, high, not, symmetric);
            }
            case "in": {
                expect("(");
                if (at("select")) {
                    Query q = query();
                    expect(")");
                    return new InSubquery(left, q, not);
                }
                List<Expr> list = new ArrayList<>();
                do {
                    list.add(expr());
                } while (accept(","));
                expect(")");
                return new InList(left, list, not);
            }
            case "like":
            case "ilike": {
                Expr pattern = expr(6);
                if (at("escape")) throw SqlException.unsupported("LIKE ... ESCAPE");
                return new Like(left, pattern, not, op.equals("ilike"));
            }
            default:
                throw error(null);
        }
    }

    private Expr prefix() {
        Token t = next();
        switch (t.kind()) {
            case NUMBER:
                return number(t.text());
            case STRING:
                return new Literal(t.text(), Type.UNKNOWN);
            case PARAM:
                return new Param(Integer.parseInt(t.text()));
            case QUOTED_IDENT:
                return columnOrFunction(t.text());
            case OP:
                switch (t.text()) {
                    case "-": {
                        Expr operand = expr(10);
                        if (operand instanceof Literal l && l.value() instanceof Long v) return new Literal(-v, Type.BIGINT);
                        if (operand instanceof Literal l && l.value() instanceof Double v) return new Literal(-v, Type.DOUBLE);
                        return new Unary(UnaryOp.NEGATE, operand);
                    }
                    case "+":
                        return expr(10);
                    case "(": {
                        if (at("select")) {
                            Query q = query();
                            expect(")");
                            return new ScalarSubquery(q);
                        }
                        Expr inner = expr();
                        if (at(",")) throw SqlException.unsupported("row constructors");
                        expect(")");
                        return inner;
                    }
                    default:
                        pos--;
                        throw error("expected an expression");
                }
            case IDENT:
                break;
            default:
                pos--;
                throw error("expected an expression");
        }
        switch (t.text()) {
            case "null":
                return new Literal(null, Type.UNKNOWN);
            case "true":
                return new Literal(true, Type.BOOLEAN);
            case "false":
                return new Literal(false, Type.BOOLEAN);
            case "not":
                return new Unary(UnaryOp.NOT, expr(3));
            case "case":
                return caseExpr();
            case "cast": {
                expect("(");
                Expr e = expr();
                expect("as");
                Type type = typeName();
                expect(")");
                return new Cast(e, type);
            }
            case "exists": {
                expect("(");
                Query q = query();
                expect(")");
                return new Exists(q, false);
            }
            default:
                break;
        }
        // A typed literal like  double precision '1.5'  or  int '3'
        if (peek().kind() == Kind.STRING && isTypeWord(t.text())) {
            String text = next().text();
            return new Cast(new Literal(text, Type.UNKNOWN), Type.fromSql(t.text()));
        }
        if ((t.text().equals("left") || t.text().equals("right")) && at("(")) return columnOrFunction(t.text()); // left(s, n)
        if (RESERVED.contains(t.text())) {
            pos--;
            throw error("expected an expression");
        }
        return columnOrFunction(t.text());
    }

    private static boolean isTypeWord(String w) {
        return switch (w) {
            case "int", "integer", "bigint", "text", "boolean", "bool", "float", "float8", "real", "varchar" -> true;
            default -> false;
        };
    }

    private Expr number(String text) {
        if (text.contains(".") || text.contains("e") || text.contains("E")) return new Literal(Double.parseDouble(text), Type.DOUBLE);
        try {
            return new Literal(Long.parseLong(text), Type.BIGINT);
        } catch (NumberFormatException e) {
            return new Literal(Double.parseDouble(text), Type.DOUBLE); // too big for bigint
        }
    }

    private Expr columnOrFunction(String first) {
        if (accept("(")) {
            if (accept("*")) {
                expect(")");
                return filterCheck(new FunctionCall(first, List.of(), false, true));
            }
            boolean distinct = accept("distinct");
            if (!distinct) accept("all");
            List<Expr> args = new ArrayList<>();
            if (!at(")")) {
                do {
                    args.add(expr());
                } while (accept(","));
            }
            if (at("order")) throw SqlException.unsupported("ORDER BY inside an aggregate");
            expect(")");
            return filterCheck(new FunctionCall(first, args, distinct, false));
        }
        if (accept(".")) {
            Token t = next();
            if (t.kind() != Kind.IDENT && t.kind() != Kind.QUOTED_IDENT) {
                pos--;
                throw error("expected a column name");
            }
            if (at(".")) throw SqlException.unsupported("schema-qualified names");
            return new ColumnRef(first, t.text());
        }
        return new ColumnRef(null, first);
    }

    private Expr filterCheck(FunctionCall call) {
        if (at("over")) throw SqlException.unsupported("window functions");
        if (at("filter")) throw SqlException.unsupported("FILTER");
        return call;
    }

    private Expr caseExpr() {
        Expr operand = at("when") ? null : expr();
        List<When> whens = new ArrayList<>();
        while (accept("when")) {
            Expr cond = expr();
            expect("then");
            whens.add(new When(cond, expr()));
        }
        if (whens.isEmpty()) throw error("expected WHEN");
        Expr otherwise = accept("else") ? expr() : null;
        expect("end");
        return new Case(operand, whens, otherwise);
    }
}
