package io.github.tendo505.simpan.engine;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.catalog.Catalog.Column;
import io.github.tendo505.simpan.catalog.Catalog.IndexDef;
import io.github.tendo505.simpan.catalog.Catalog.TableDef;
import io.github.tendo505.simpan.exec.ExecContext;
import io.github.tendo505.simpan.exec.Expr;
import io.github.tendo505.simpan.exec.IndexRanges;
import io.github.tendo505.simpan.exec.Operator;
import io.github.tendo505.simpan.exec.Operators;
import io.github.tendo505.simpan.plan.Binder;
import io.github.tendo505.simpan.plan.Planner;
import io.github.tendo505.simpan.plan.Scope;
import io.github.tendo505.simpan.sql.Ast;
import io.github.tendo505.simpan.sql.Ast.Isolation;
import io.github.tendo505.simpan.sql.Ast.Statement;
import io.github.tendo505.simpan.sql.Parser;
import io.github.tendo505.simpan.storage.Rid;
import io.github.tendo505.simpan.tx.Snapshot;
import io.github.tendo505.simpan.tx.Transaction;
import io.github.tendo505.simpan.types.Type;
import io.github.tendo505.simpan.types.Values;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

/**
 * One client's connection: runs statements, in autocommit or inside
 * BEGIN ... COMMIT. As in PostgreSQL, once a statement in a transaction
 * block fails, the block is aborted and every statement until ROLLBACK is
 * refused.
 */
public final class Session implements AutoCloseable {
    /** A statement's outcome: the command tag (like "INSERT 0 3"), and columns and rows for queries. */
    public record Result(String tag, List<String> columns, List<Type> types, List<Object[]> rows, List<String> notices) {
        static Result command(String tag) {
            return new Result(tag, List.of(), List.of(), List.of(), List.of());
        }

        static Result notice(String tag, String notice) {
            return new Result(tag, List.of(), List.of(), List.of(), List.of(notice));
        }

        public boolean hasRows() {
            return !columns.isEmpty();
        }
    }

    /**
     * A parsed statement with the parameter and result types it was planned
     * with. {@code known} holds the parameter types that were declared or
     * worked out; execution plans with them, so it gets the same types.
     */
    public record Prepared(String sql, Statement statement, List<Type> paramTypes, List<String> columns, List<Type> columnTypes,
                           Map<Integer, Type> known) {}

    private final Database db;
    private Transaction tx;
    private boolean failed;
    /** The open transaction was started implicitly (a multi-statement query, or extended-protocol messages up to Sync). */
    private boolean implicit;
    private Isolation defaultIsolation = Isolation.READ_COMMITTED;
    private long lockTimeoutMillis = Long.MAX_VALUE; // lock_timeout = 0: wait as long as it takes, as in PostgreSQL
    private Map<Integer, Type> presetParamTypes = Map.of();
    private final Map<String, String> settings = new LinkedHashMap<>();

    public Session(Database db) {
        this.db = db;
        settings.put("server_version", "16.0");
        settings.put("client_encoding", "UTF8");
        settings.put("datestyle", "ISO, MDY");
        settings.put("timezone", "UTC");
        settings.put("standard_conforming_strings", "on");
    }

    public Database db() {
        return db;
    }

    /** 'I' idle, 'T' in a transaction block, 'E' in a failed one: what the wire protocol reports after each command. */
    public char status() {
        return tx == null || implicit ? 'I' : failed ? 'E' : 'T';
    }

    /**
     * Runs one or more statements (the simple query protocol). As in
     * PostgreSQL, several statements sent together run as one implicit
     * transaction: the first error stops them and rolls back the ones
     * before it, unless the text itself uses BEGIN and COMMIT.
     */
    public List<Result> execute(String sql) {
        List<Statement> statements;
        try {
            statements = Parser.parseScript(sql);
        } catch (RuntimeException e) {
            noteError();
            throw e;
        }
        List<Result> out = new ArrayList<>();
        boolean batch = statements.size() > 1;
        if (batch) beginImplicit();
        boolean ok = false;
        try {
            for (Statement s : statements) out.add(execute(s, List.of()));
            ok = true;
        } finally {
            if (batch) endImplicit(ok);
        }
        return out;
    }

    /** Runs exactly one statement and returns its result. */
    public Result run(String sql, Object... params) {
        Statement s;
        try {
            s = Parser.parse(sql);
        } catch (RuntimeException e) {
            noteError();
            throw e;
        }
        return execute(s, Arrays.asList(params));
    }

    /** An error outside a statement (a syntax error, a bad Bind) still aborts an open transaction block. */
    public void noteError() {
        if (tx != null) failed = true;
    }

    /** Opens an implicit transaction if none is open, for statements that should succeed or fail together. */
    public void beginImplicit() {
        if (tx != null) return;
        tx = begin(defaultIsolation);
        implicit = true;
        failed = false;
    }

    /** Ends an implicit transaction: commits it if everything worked, rolls it back otherwise. */
    public void endImplicit(boolean ok) {
        if (!implicit || tx == null) return;
        Transaction t = tx;
        tx = null;
        implicit = false;
        boolean commit = ok && !failed;
        failed = false;
        if (commit) db.commit(t);
        else db.abort(t);
    }

    private Transaction begin(Isolation isolation) {
        Transaction t = db.begin(isolation);
        t.setLockTimeoutMillis(lockTimeoutMillis);
        return t;
    }

    public Result execute(Statement s, List<Object> params) {
        if (failed && !(s instanceof Ast.Commit) && !(s instanceof Ast.Rollback)) {
            throw new SqlException(SqlException.IN_FAILED_TRANSACTION, "current transaction is aborted, commands ignored until end of transaction block");
        }
        try {
            return dispatch(s, params);
        } catch (RuntimeException | Error e) {
            noteError(); // any error inside a block aborts it, as in PostgreSQL
            throw translate(e);
        }
    }

    /** Errors the JVM throws become SQL errors, as PostgreSQL reports them; the session carries on. */
    private static RuntimeException translate(Throwable e) {
        return switch (e) {
            case RuntimeException r -> r;
            case StackOverflowError so -> new SqlException("54001", "stack depth limit exceeded (the statement is nested too deeply)");
            case OutOfMemoryError oom -> new SqlException("53200", "out of memory");
            case Error err -> throw err;
            default -> new SqlException(SqlException.INTERNAL, String.valueOf(e));
        };
    }

    private Result dispatch(Statement s, List<Object> params) {
        return switch (s) {
            case Ast.Begin b -> begin(b);
            case Ast.Commit c -> commit();
            case Ast.Rollback r -> rollback();
            case Ast.Query q -> inTransaction(t -> query(q, params, t));
            case Ast.Insert i -> inTransaction(t -> insert(i, params, t));
            case Ast.Update u -> inTransaction(t -> update(u, params, t));
            case Ast.Delete d -> inTransaction(t -> delete(d, params, t));
            case Ast.Explain e -> inTransaction(t -> explain(e, params, t));
            case Ast.CreateTable c -> ddl(() -> createTable(c));
            case Ast.DropTable d -> ddl(() -> dropTable(d));
            case Ast.CreateIndex c -> ddl(() -> createIndex(c));
            case Ast.DropIndex d -> ddl(() -> dropIndex(d));
            case Ast.SetVar v -> set(v);
            case Ast.Show sh -> show(sh);
            case Ast.Checkpoint c -> {
                db.checkpoint();
                yield Result.command("CHECKPOINT");
            }
            case Ast.Vacuum v -> ddl(() -> {
                for (String name : v.table() == null ? allTables() : List.of(v.table())) db.vacuum(name);
                return Result.command("VACUUM");
            });
            case Ast.Analyze a -> ddl(() -> {
                for (String name : a.table() == null ? allTables() : List.of(a.table())) db.analyze(name);
                return Result.command("ANALYZE");
            });
        };
    }

    private List<String> allTables() {
        List<String> names = new ArrayList<>();
        for (TableDef t : db.catalog().tables()) names.add(t.name());
        return names;
    }

    // ---------------- transactions ----------------

    private Result begin(Ast.Begin b) {
        if (tx != null && implicit) {
            // BEGIN inside a batch turns its implicit transaction into an explicit block, as in PostgreSQL.
            implicit = false;
            return Result.command("BEGIN");
        }
        if (tx != null) return Result.notice("BEGIN", "there is already a transaction in progress");
        tx = begin(b.isolation() != null ? b.isolation() : defaultIsolation);
        failed = false;
        return Result.command("BEGIN");
    }

    private Result commit() {
        if (tx == null) return Result.notice("COMMIT", "there is no transaction in progress");
        Transaction t = tx;
        tx = null;
        implicit = false;
        if (failed) {
            failed = false;
            db.abort(t);
            return Result.command("ROLLBACK");
        }
        db.commit(t);
        return Result.command("COMMIT");
    }

    private Result rollback() {
        if (tx == null) return Result.notice("ROLLBACK", "there is no transaction in progress");
        Transaction t = tx;
        tx = null;
        implicit = false;
        failed = false;
        db.abort(t);
        return Result.command("ROLLBACK");
    }

    /** Runs work in the open transaction, or in its own if there's none (autocommit). */
    private Result inTransaction(Function<Transaction, Result> work) {
        if (tx != null) {
            try {
                return work.apply(tx);
            } catch (RuntimeException | Error e) {
                failed = true; // no savepoints: the whole block is now aborted
                throw e;
            }
        }
        Transaction t = begin(defaultIsolation);
        Result r;
        try {
            r = work.apply(t);
        } catch (RuntimeException | Error e) {
            db.abort(t); // whatever went wrong, never leave the transaction (and its row locks) behind
            throw e;
        }
        db.commit(t);
        return r;
    }

    /**
     * Schema changes take effect at once and can't be rolled back, so they
     * can't run inside an explicit transaction block. Inside an implicit one
     * (a batch of statements), the work so far is committed first.
     */
    private Result ddl(java.util.function.Supplier<Result> work) {
        if (tx != null && !implicit) {
            failed = true;
            throw new SqlException(SqlException.ACTIVE_TRANSACTION, "schema changes, VACUUM and ANALYZE can't run inside a transaction block in Simpan");
        }
        if (tx != null) {
            endImplicit(true);
            try {
                return work.get();
            } finally {
                beginImplicit();
            }
        }
        return work.get();
    }

    @Override
    public void close() {
        if (tx != null) {
            db.abort(tx);
            tx = null;
            implicit = false;
        }
    }

    /** Parameter types for planning: none known, or those a prepared statement was planned with. */
    private Map<Integer, Type> paramMap() {
        return new TreeMap<>(presetParamTypes);
    }

    // ---------------- locking ----------------

    /** Holds the tables shared for the statement; fails if one was dropped after planning. */
    private List<Lock> lock(Set<Table> tables) {
        List<Lock> held = new ArrayList<>();
        try {
            for (Table t : tables) {
                Lock l = t.lock().readLock();
                l.lock();
                held.add(l);
                if (db.tableOrNull(t.def().name()) != t) {
                    throw new SqlException(SqlException.UNDEFINED_TABLE, "relation \"" + t.def().name() + "\" was dropped");
                }
            }
        } catch (RuntimeException e) {
            unlock(held);
            throw e;
        }
        return held;
    }

    private static void unlock(List<Lock> locks) {
        for (Lock l : locks) l.unlock();
    }

    // ---------------- queries ----------------

    private Result query(Ast.Query q, List<Object> params, Transaction t) {
        Planner planner = new Planner(db, paramMap());
        Operator op = planner.planQuery(q);
        List<Lock> locks = lock(planner.tables());
        try {
            Snapshot snap = db.snapshot(t);
            ExecContext ctx = new ExecContext(db, t, snap, params, false);
            List<Object[]> rows = drain(op, ctx);
            return new Result("SELECT " + rows.size(), op.names(), resultTypes(op.types()), rows, List.of());
        } finally {
            unlock(locks);
        }
    }

    private static List<Type> resultTypes(List<Type> types) {
        List<Type> out = new ArrayList<>();
        for (Type t : types) out.add(t == Type.UNKNOWN ? Type.TEXT : t);
        return out;
    }

    private static List<Object[]> drain(Operator op, ExecContext ctx) {
        List<Object[]> rows = new ArrayList<>();
        op.open(ctx);
        try {
            for (Object[] r = op.next(); r != null; r = op.next()) rows.add(r);
        } finally {
            op.close();
        }
        return rows;
    }

    private Result explain(Ast.Explain e, List<Object> params, Transaction t) {
        List<String> lines;
        Statement inner = e.statement();
        if (inner instanceof Ast.Query q) {
            Planner planner = new Planner(db, paramMap());
            Operator op = planner.planQuery(q);
            if (e.analyze()) {
                List<Lock> locks = lock(planner.tables());
                long start = System.nanoTime();
                try {
                    drain(op, new ExecContext(db, t, db.snapshot(t), params, true));
                } finally {
                    unlock(locks);
                }
                lines = new ArrayList<>(op.explain(true));
                lines.add(String.format(Locale.ROOT, "Execution Time: %.3f ms", (System.nanoTime() - start) / 1e6));
            } else {
                lines = op.explain(false);
            }
        } else if (inner instanceof Ast.Update || inner instanceof Ast.Delete) {
            String table = inner instanceof Ast.Update u ? u.table() : ((Ast.Delete) inner).table();
            String alias = inner instanceof Ast.Update u ? u.alias() : ((Ast.Delete) inner).alias();
            Ast.Expr where = inner instanceof Ast.Update u ? u.where() : ((Ast.Delete) inner).where();
            Target target = target(table, alias, where);
            lines = new ArrayList<>();
            lines.add((inner instanceof Ast.Update ? "Update on " : "Delete on ") + table);
            for (String line : target.scan.explain(false)) lines.add("  ->  " + line.stripLeading().replaceFirst("^->  ", ""));
            if (e.analyze()) {
                Result r = execute(inner, params);
                lines.add("Rows affected: " + r.tag().replaceAll("\\D+", ""));
            }
        } else if (inner instanceof Ast.Insert ins) {
            lines = new ArrayList<>(List.of("Insert on " + ins.table()));
            if (ins.query() != null) {
                Operator op = new Planner(db, paramMap()).planQuery(ins.query());
                for (String line : op.explain(false)) lines.add("  ->  " + line.stripLeading());
            } else {
                lines.add("  ->  Values Scan  (rows=" + ins.rows().size() + ")");
            }
            if (e.analyze()) {
                Result r = execute(inner, params);
                lines.add("Rows affected: " + r.tag().replaceAll(".* ", ""));
            }
        } else {
            throw SqlException.unsupported("EXPLAIN of " + inner.getClass().getSimpleName());
        }
        List<Object[]> rows = new ArrayList<>();
        for (String l : lines) rows.add(new Object[] {l});
        return new Result("EXPLAIN", List.of("QUERY PLAN"), List.of(Type.TEXT), rows, List.of());
    }

    // ---------------- INSERT ----------------

    private Result insert(Ast.Insert ins, List<Object> params, Transaction t) {
        Table table = db.table(ins.table());
        TableDef def = table.def();
        int[] targets = targetColumns(def, ins.columns());
        Map<Integer, Type> paramTypes = paramMap();
        Planner planner = new Planner(db, paramTypes);
        Binder constants = new Binder(Scope.empty(), planner, paramTypes, "VALUES");
        Set<Table> touched = new HashSet<>();
        touched.add(table);
        Operator source = null;
        List<List<Expr>> rows = new ArrayList<>();
        if (ins.query() != null) {
            source = planner.planQuery(ins.query());
            touched.addAll(planner.tables());
            if (source.width() != targets.length) {
                throw new SqlException(SqlException.SYNTAX, "INSERT has " + (source.width() > targets.length ? "more" : "fewer") + " expressions than target columns");
            }
            for (int i = 0; i < targets.length; i++) checkAssignable(source.types().get(i), def.columns().get(targets[i]));
        } else {
            for (List<Ast.Expr> row : ins.rows()) {
                if (row.size() > targets.length || (row.size() < targets.length && !row.isEmpty())) {
                    throw new SqlException(SqlException.SYNTAX, "INSERT has " + (row.size() > targets.length ? "more" : "fewer") + " expressions than target columns");
                }
                List<Expr> bound = new ArrayList<>();
                for (int i = 0; i < row.size(); i++) {
                    Column col = def.columns().get(targets[i]);
                    if (row.get(i) instanceof Ast.Default) {
                        bound.add(null);
                        continue;
                    }
                    Expr e = constants.coerce(constants.bind(row.get(i)), col.type());
                    checkAssignable(e.type(), col);
                    bound.add(e);
                }
                rows.add(bound);
            }
        }
        // Bind RETURNING before locking, so tables its subqueries read are locked too.
        Returning returning = ins.returning() == null ? null : returning(table, null, ins.returning(), planner);
        touched.addAll(planner.tables());
        List<Lock> locks = lock(touched);
        try {
            ExecContext ctx = new ExecContext(db, t, db.snapshot(t), params, false);
            List<Object[]> values = new ArrayList<>();
            if (source != null) {
                values.addAll(drain(source, ctx)); // all of it first: an INSERT ... SELECT from the same table can't see its own rows
            } else {
                for (List<Expr> row : rows) {
                    Object[] v = new Object[row.size()];
                    for (int i = 0; i < v.length; i++) v[i] = row.get(i) == null ? Default.INSTANCE : row.get(i).eval(new Object[0], ctx);
                    values.add(v);
                }
            }
            if (returning != null) ctx.prefetch(returning.exprs()); // RETURNING's subqueries see the table before this INSERT
            List<Object[]> out = new ArrayList<>();
            for (Object[] v : values) {
                Object[] full = new Object[def.columns().size()];
                for (int c = 0; c < full.length; c++) full[c] = def.columns().get(c).defaultValue();
                for (int i = 0; i < targets.length && i < v.length; i++) {
                    Column col = def.columns().get(targets[i]);
                    full[targets[i]] = v[i] == Default.INSTANCE ? col.defaultValue() : Values.coerceForColumn(v[i], col.type(), col.name());
                }
                table.insert(t, full);
                if (returning != null) out.add(returning.apply(full, ctx));
            }
            if (returning != null) return returning.result("INSERT 0 " + values.size(), out);
            return Result.command("INSERT 0 " + values.size());
        } finally {
            unlock(locks);
        }
    }

    /** Marks a DEFAULT in VALUES until the column's default is filled in. */
    private enum Default { INSTANCE }

    private static int[] targetColumns(TableDef def, List<String> names) {
        if (names == null) {
            int[] all = new int[def.columns().size()];
            for (int i = 0; i < all.length; i++) all[i] = i;
            return all;
        }
        int[] out = new int[names.size()];
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < out.length; i++) {
            int c = def.columnIndex(names.get(i));
            if (c < 0) throw new SqlException(SqlException.UNDEFINED_COLUMN, "column \"" + names.get(i) + "\" of relation \"" + def.name() + "\" does not exist");
            if (!seen.add(names.get(i))) throw new SqlException(SqlException.DUPLICATE_COLUMN, "column \"" + names.get(i) + "\" specified more than once");
            out[i] = c;
        }
        return out;
    }

    /** What PostgreSQL allows to be stored without a cast: same type, numbers into numbers, untyped literals. */
    private static void checkAssignable(Type from, Column col) {
        if (from == col.type() || from == Type.UNKNOWN || (from.isNumeric() && col.type().isNumeric())) return;
        throw new SqlException(SqlException.DATATYPE_MISMATCH, "column \"" + col.name() + "\" is of type " + col.type().sqlName()
                + " but expression is of type " + from.sqlName());
    }

    // ---------------- UPDATE and DELETE ----------------

    /** The rows an UPDATE or DELETE starts from: its table, scope, condition and how to find them. */
    private record Target(Table table, Scope scope, Expr where, Planner.AccessPath path, Operator scan, Planner planner,
                          Map<Integer, Type> paramTypes) {}

    private Target target(String tableName, String alias, Ast.Expr where) {
        Table table = db.table(tableName);
        String q = alias != null ? alias : tableName;
        List<Scope.Entry> entries = new ArrayList<>();
        for (Column c : table.def().columns()) entries.add(new Scope.Entry(q, c.name(), c.type()));
        Scope scope = new Scope(entries);
        Map<Integer, Type> paramTypes = paramMap();
        Planner planner = new Planner(db, paramTypes);
        Expr cond = where == null ? null : new Binder(scope, planner, paramTypes, "WHERE").bindCondition(where);
        Planner.AccessPath path = planner.accessPath(table, Expr.conjuncts(cond), planner.estimatedRows(table));
        List<String> names = new ArrayList<>();
        for (Column c : table.def().columns()) names.add(c.name());
        Expr residual = path.residual().isEmpty() ? null : Expr.and(path.residual());
        Operator scan = path.index() == null
                ? new Operators.SeqScan(table, alias, residual, names)
                : new Operators.IndexScan(table, alias, path.index(), path.probes(), residual, names);
        return new Target(table, scope, cond, path, scan, planner, paramTypes);
    }

    /** Finds the target rows first, then changes them, so a statement never sees (or re-updates) its own new versions. */
    private List<Table.Row> targets(Target target, ExecContext ctx) {
        List<Table.Row> rows = new ArrayList<>();
        Iterator<Table.Row> it;
        if (target.path.index() == null) {
            it = target.table.scan(ctx.snapshot());
        } else {
            List<Table.Row> found = new ArrayList<>();
            for (var r : IndexRanges.of(ctx, target.path.index(), target.table, target.path.probes())) {
                target.table.indexScan(target.path.index(), r.from(), r.to(), ctx.snapshot()).forEachRemaining(found::add);
            }
            it = found.iterator();
        }
        while (it.hasNext()) {
            Table.Row r = it.next();
            if (target.where == null || Expr.isTrue(target.where.eval(r.values(), ctx))) rows.add(r);
        }
        return rows;
    }

    private Result update(Ast.Update u, List<Object> params, Transaction t) {
        Target target = target(u.table(), u.alias(), u.where());
        Table table = target.table;
        TableDef def = table.def();
        Binder binder = new Binder(target.scope, target.planner, target.paramTypes, "UPDATE");
        int[] columns = new int[u.set().size()];
        Expr[] values = new Expr[u.set().size()];
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < columns.length; i++) {
            Ast.Assignment a = u.set().get(i);
            int c = def.columnIndex(a.column());
            if (c < 0) throw new SqlException(SqlException.UNDEFINED_COLUMN, "column \"" + a.column() + "\" of relation \"" + def.name() + "\" does not exist");
            if (!seen.add(a.column())) throw new SqlException(SqlException.DUPLICATE_COLUMN, "multiple assignments to same column \"" + a.column() + "\"");
            columns[i] = c;
            Column col = def.columns().get(c);
            if (a.value() instanceof Ast.Default) {
                values[i] = new Expr.Const(col.defaultValue(), col.type());
            } else {
                values[i] = binder.coerce(binder.bind(a.value()), col.type());
                checkAssignable(values[i].type(), col);
            }
        }
        Returning returning = u.returning() == null ? null : returning(table, u.alias(), u.returning(), target.planner);
        Set<Table> touched = new HashSet<>(target.planner.tables());
        touched.add(table);
        List<Lock> locks = lock(touched);
        try {
            ExecContext ctx = new ExecContext(db, t, db.snapshot(t), params, false);
            List<Table.Row> found = targets(target, ctx);
            // Subqueries in SET and RETURNING run now, before any row changes, so they never see this statement's own work.
            ctx.prefetch(Arrays.asList(values));
            if (returning != null) ctx.prefetch(returning.exprs());
            List<Object[]> out = new ArrayList<>();
            int count = 0;
            for (Table.Row candidate : found) {
                Table.Row row = table.lockForWrite(t, candidate.rid(), v -> target.where == null || Expr.isTrue(target.where.eval(v, ctx)));
                if (row == null) continue;
                Object[] next = row.values().clone();
                for (int i = 0; i < columns.length; i++) {
                    Column col = def.columns().get(columns[i]);
                    next[columns[i]] = Values.coerceForColumn(values[i].eval(row.values(), ctx), col.type(), col.name());
                }
                Rid newRid = table.insert(t, next);
                table.linkReplacement(t, row.rid(), newRid);
                table.rowsRemoved(1);
                count++;
                if (returning != null) out.add(returning.apply(next, ctx));
            }
            if (returning != null) return returning.result("UPDATE " + count, out);
            return Result.command("UPDATE " + count);
        } finally {
            unlock(locks);
        }
    }

    private Result delete(Ast.Delete d, List<Object> params, Transaction t) {
        Target target = target(d.table(), d.alias(), d.where());
        Table table = target.table;
        Returning returning = d.returning() == null ? null : returning(table, d.alias(), d.returning(), target.planner);
        Set<Table> touched = new HashSet<>(target.planner.tables());
        touched.add(table);
        List<Lock> locks = lock(touched);
        try {
            ExecContext ctx = new ExecContext(db, t, db.snapshot(t), params, false);
            List<Table.Row> found = targets(target, ctx);
            if (returning != null) ctx.prefetch(returning.exprs());
            List<Object[]> out = new ArrayList<>();
            int count = 0;
            for (Table.Row candidate : found) {
                Table.Row row = table.lockForWrite(t, candidate.rid(), v -> target.where == null || Expr.isTrue(target.where.eval(v, ctx)));
                if (row == null) continue;
                count++;
                if (returning != null) out.add(returning.apply(row.values(), ctx));
            }
            table.rowsRemoved(count);
            if (returning != null) return returning.result("DELETE " + count, out);
            return Result.command("DELETE " + count);
        } finally {
            unlock(locks);
        }
    }

    /** RETURNING items, bound to the table's row. */
    private record Returning(List<Expr> exprs, List<String> names) {
        Object[] apply(Object[] row, ExecContext ctx) {
            Object[] out = new Object[exprs.size()];
            for (int i = 0; i < out.length; i++) out[i] = exprs.get(i).eval(row, ctx);
            return out;
        }

        Result result(String tag, List<Object[]> rows) {
            List<Type> types = new ArrayList<>();
            for (Expr e : exprs) types.add(e.type() == Type.UNKNOWN ? Type.TEXT : e.type());
            return new Result(tag, names, types, rows, List.of());
        }
    }

    private Returning returning(Table table, String alias, List<Ast.SelectItem> items, Planner planner) {
        String q = alias != null ? alias : table.def().name();
        List<Scope.Entry> entries = new ArrayList<>();
        for (Column c : table.def().columns()) entries.add(new Scope.Entry(q, c.name(), c.type()));
        Scope scope = new Scope(entries);
        Binder b = new Binder(scope, planner, planner.paramTypes(), "RETURNING");
        List<Expr> exprs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Ast.SelectItem item : items) {
            if (item instanceof Ast.Star s) {
                for (int i : scope.star(s.qualifier())) {
                    exprs.add(new Expr.Col(i, scope.get(i).type(), scope.get(i).name()));
                    names.add(scope.get(i).name());
                }
            } else {
                Ast.ExprItem ei = (Ast.ExprItem) item;
                exprs.add(b.bind(ei.expr()));
                names.add(ei.alias() != null ? ei.alias() : ei.expr() instanceof Ast.ColumnRef c ? c.name() : "?column?");
            }
        }
        return new Returning(exprs, names);
    }

    // ---------------- DDL ----------------

    private Result createTable(Ast.CreateTable c) {
        if (db.tableOrNull(c.name()) != null && c.ifNotExists()) {
            return Result.notice("CREATE TABLE", "relation \"" + c.name() + "\" already exists, skipping");
        }
        if (c.columns().isEmpty()) throw new SqlException(SqlException.SYNTAX, "a table needs at least one column");
        List<Column> columns = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> notNull = new HashSet<>(c.primaryKey() == null ? List.of() : c.primaryKey());
        for (Ast.ColumnDef cd : c.columns()) {
            if (!names.add(cd.name())) throw new SqlException(SqlException.DUPLICATE_COLUMN, "column \"" + cd.name() + "\" specified more than once");
        }
        for (Ast.ColumnDef cd : c.columns()) {
            Object def = null;
            if (cd.defaultValue() != null) {
                Map<Integer, Type> none = new HashMap<>();
                Binder b = new Binder(Scope.empty(), new Planner(db, none), none, "DEFAULT");
                Expr e = b.coerce(b.bind(cd.defaultValue()), cd.type());
                if (!Expr.columns(e).isEmpty() || e instanceof Expr.Param) throw new SqlException(SqlException.SYNTAX, "DEFAULT must be a constant");
                def = Values.coerceForColumn(e.eval(new Object[0], new ExecContext(db, null, null, List.of(), false)), cd.type(), cd.name());
            }
            columns.add(new Column(cd.name(), cd.type(), cd.notNull() || notNull.contains(cd.name()), def));
        }
        List<IndexDef> indexes = new ArrayList<>();
        Set<String> indexNames = new HashSet<>();
        if (c.primaryKey() != null) indexes.add(indexDef(c.name() + "_pkey", c.name(), columns, c.primaryKey(), true, true, indexNames));
        for (List<String> u : c.uniques()) {
            if (c.primaryKey() != null && u.equals(c.primaryKey())) continue;
            indexes.add(indexDef(c.name() + "_" + String.join("_", u) + "_key", c.name(), columns, u, true, false, indexNames));
        }
        db.createTable(new TableDef(c.name(), db.allocateFileId(), List.copyOf(columns), List.copyOf(indexes)));
        return Result.command("CREATE TABLE");
    }

    private IndexDef indexDef(String name, String table, List<Column> columns, List<String> cols, boolean unique, boolean primary, Set<String> used) {
        List<Integer> positions = new ArrayList<>();
        for (String col : cols) {
            int found = -1;
            for (int i = 0; i < columns.size(); i++) if (columns.get(i).name().equals(col)) found = i;
            if (found < 0) throw new SqlException(SqlException.UNDEFINED_COLUMN, "column \"" + col + "\" named in key does not exist");
            positions.add(found);
        }
        String unique1 = name;
        for (int n = 1; !used.add(unique1) || db.catalog().nameInUse(unique1); n++) unique1 = name + n;
        return new IndexDef(unique1, db.allocateFileId(), List.copyOf(positions), unique, primary);
    }

    private Result dropTable(Ast.DropTable d) {
        if (db.tableOrNull(d.name()) == null) {
            if (d.ifExists()) return Result.notice("DROP TABLE", "table \"" + d.name() + "\" does not exist, skipping");
            throw new SqlException(SqlException.UNDEFINED_TABLE, "table \"" + d.name() + "\" does not exist");
        }
        db.dropTable(d.name());
        return Result.command("DROP TABLE");
    }

    private Result createIndex(Ast.CreateIndex c) {
        Table t = db.table(c.table());
        if (db.catalog().nameInUse(c.name())) {
            if (c.ifNotExists()) return Result.notice("CREATE INDEX", "relation \"" + c.name() + "\" already exists, skipping");
            throw new SqlException(SqlException.DUPLICATE_TABLE, "relation \"" + c.name() + "\" already exists");
        }
        List<Integer> positions = new ArrayList<>();
        for (String col : c.columns()) {
            int i = t.def().columnIndex(col);
            if (i < 0) throw new SqlException(SqlException.UNDEFINED_COLUMN, "column \"" + col + "\" does not exist");
            positions.add(i);
        }
        db.createIndex(c.table(), new IndexDef(c.name(), db.allocateFileId(), List.copyOf(positions), c.unique(), false));
        return Result.command("CREATE INDEX");
    }

    private Result dropIndex(Ast.DropIndex d) {
        if (db.catalog().tableOfIndex(d.name()) == null) {
            if (d.ifExists()) return Result.notice("DROP INDEX", "index \"" + d.name() + "\" does not exist, skipping");
            throw new SqlException(SqlException.UNDEFINED_TABLE, "index \"" + d.name() + "\" does not exist");
        }
        db.dropIndex(d.name());
        return Result.command("DROP INDEX");
    }

    // ---------------- SET and SHOW ----------------

    private Result set(Ast.SetVar v) {
        String name = v.name().toLowerCase(Locale.ROOT);
        String value = v.value().replaceAll("^'(.*)'$", "$1");
        switch (name) {
            case "transaction_isolation", "default_transaction_isolation" -> {
                Isolation iso = switch (value.toLowerCase(Locale.ROOT)) {
                    case "read committed", "read uncommitted" -> Isolation.READ_COMMITTED;
                    case "repeatable read" -> Isolation.REPEATABLE_READ;
                    case "serializable" -> throw SqlException.unsupported("SERIALIZABLE isolation (use REPEATABLE READ, which is snapshot isolation)");
                    default -> throw new SqlException(SqlException.INVALID_PARAMETER_VALUE, "invalid value for parameter \"" + name + "\": \"" + value + "\"");
                };
                if (name.equals("default_transaction_isolation") || tx == null) {
                    defaultIsolation = iso;
                } else if (tx.snapshot() == null) {
                    // Nothing has run in this transaction yet, so it can still change level.
                    db.abort(tx);
                    tx = begin(iso);
                } else {
                    throw new SqlException(SqlException.ACTIVE_TRANSACTION, "SET TRANSACTION ISOLATION LEVEL must be called before any query");
                }
            }
            case "lock_timeout" -> {
                lockTimeoutMillis = parseDuration(value);
                if (tx != null) tx.setLockTimeoutMillis(lockTimeoutMillis);
            }
            default -> { } // accepted and kept for SHOW (client_encoding, application_name, extra_float_digits, ...)
        }
        settings.put(name, value);
        return Result.command("SET");
    }

    /** A lock_timeout value: milliseconds by default, or with a unit (ms, s, min); 0 means no timeout. */
    private static long parseDuration(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*(ms|s|min|h)?").matcher(v);
        if (!m.matches()) throw new SqlException(SqlException.INVALID_PARAMETER_VALUE, "invalid value for parameter \"lock_timeout\": \"" + value + "\"");
        long n = Long.parseLong(m.group(1));
        long ms = switch (m.group(2) == null ? "ms" : m.group(2)) {
            case "s" -> n * 1000;
            case "min" -> n * 60_000;
            case "h" -> n * 3_600_000;
            default -> n;
        };
        return ms == 0 ? Long.MAX_VALUE : ms;
    }

    private Result show(Ast.Show s) {
        String name = s.name().toLowerCase(Locale.ROOT).replace(" ", "_");
        String value = switch (name) {
            case "transaction_isolation", "transaction_isolation_level" -> {
                Isolation iso = tx != null ? tx.isolation() : defaultIsolation;
                yield iso == Isolation.REPEATABLE_READ ? "repeatable read" : "read committed";
            }
            case "default_transaction_isolation" -> defaultIsolation == Isolation.REPEATABLE_READ ? "repeatable read" : "read committed";
            default -> {
                String v = settings.get(name);
                if (v == null) throw new SqlException(SqlException.UNDEFINED_OBJECT_TYPE, "unrecognized configuration parameter \"" + s.name() + "\"");
                yield v;
            }
        };
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {value});
        return new Result("SHOW", List.of(name), List.of(Type.TEXT), rows, List.of());
    }

    // ---------------- prepared statements (extended query protocol) ----------------

    /**
     * Parses and plans once to learn the parameter and result types, without
     * running anything. As in PostgreSQL, an error here aborts an open
     * transaction block too.
     */
    public Prepared prepare(String sql, List<Type> declared) {
        Map<Integer, Type> declaredTypes = new TreeMap<>();
        for (int i = 0; i < declared.size(); i++) if (declared.get(i) != null && declared.get(i) != Type.UNKNOWN) declaredTypes.put(i + 1, declared.get(i));
        Map<Integer, Type> saved = presetParamTypes;
        presetParamTypes = declaredTypes; // what the client declared wins over what the statement suggests
        try {
            return prepareUnchecked(sql, declared);
        } catch (RuntimeException | Error e) {
            if (tx != null) failed = true;
            throw translate(e);
        } finally {
            presetParamTypes = saved;
        }
    }

    private Prepared prepareUnchecked(String sql, List<Type> declared) {
        List<Statement> all = Parser.parseScript(sql);
        if (all.size() > 1) throw new SqlException(SqlException.SYNTAX, "cannot insert multiple commands into a prepared statement");
        Statement s = all.isEmpty() ? null : all.get(0);
        if (failed && !(s instanceof Ast.Commit) && !(s instanceof Ast.Rollback)) {
            throw new SqlException(SqlException.IN_FAILED_TRANSACTION, "current transaction is aborted, commands ignored until end of transaction block");
        }
        Map<Integer, Type> paramTypes = paramMap();
        List<String> columns = List.of();
        List<Type> types = List.of();
        switch (s) {
            case null -> { }
            case Ast.Query q -> {
                Planner p = new Planner(db, paramTypes);
                Operator op = p.planQuery(q);
                columns = op.names();
                types = resultTypes(op.types());
            }
            case Ast.Insert i -> describeInsert(i, paramTypes);
            case Ast.Update u -> {
                Target t = target(u.table(), u.alias(), u.where());
                paramTypes.putAll(t.paramTypes);
                Binder b = new Binder(t.scope, t.planner, paramTypes, "UPDATE");
                for (Ast.Assignment a : u.set()) {
                    if (a.value() instanceof Ast.Default) continue;
                    int c = t.table.def().columnIndex(a.column());
                    if (c >= 0) b.coerce(b.bind(a.value()), t.table.def().columns().get(c).type());
                }
            }
            case Ast.Delete d -> paramTypes.putAll(target(d.table(), d.alias(), d.where()).paramTypes);
            case Ast.Explain e -> {
                columns = List.of("QUERY PLAN");
                types = List.of(Type.TEXT);
            }
            case Ast.Show sh -> {
                columns = List.of(sh.name());
                types = List.of(Type.TEXT);
            }
            default -> { }
        }
        if (s instanceof Ast.Insert i && i.returning() != null) {
            Table t = db.table(i.table());
            Returning r = returning(t, null, i.returning(), new Planner(db, paramTypes));
            columns = r.names();
            types = r.result("", List.of()).types();
        } else if (s instanceof Ast.Update u && u.returning() != null) {
            Returning r = returning(db.table(u.table()), u.alias(), u.returning(), new Planner(db, paramTypes));
            columns = r.names();
            types = r.result("", List.of()).types();
        } else if (s instanceof Ast.Delete d && d.returning() != null) {
            Returning r = returning(db.table(d.table()), d.alias(), d.returning(), new Planner(db, paramTypes));
            columns = r.names();
            types = r.result("", List.of()).types();
        }
        int count = Math.max(declared.size(), paramTypes.isEmpty() ? 0 : ((TreeMap<Integer, Type>) paramTypes).lastKey());
        List<Type> params = new ArrayList<>();
        for (int i = 1; i <= count; i++) params.add(paramTypes.getOrDefault(i, Type.TEXT));
        return new Prepared(sql, s, params, columns, types, Map.copyOf(paramTypes));
    }

    /** Learns parameter types from the columns an INSERT's values go into. */
    private void describeInsert(Ast.Insert ins, Map<Integer, Type> paramTypes) {
        Table t = db.table(ins.table());
        Planner planner = new Planner(db, paramTypes);
        if (ins.query() != null) {
            planner.planQuery(ins.query());
            return;
        }
        int[] targets = targetColumns(t.def(), ins.columns());
        Binder b = new Binder(Scope.empty(), planner, paramTypes, "VALUES");
        for (List<Ast.Expr> row : ins.rows()) {
            for (int i = 0; i < row.size() && i < targets.length; i++) {
                if (!(row.get(i) instanceof Ast.Default)) b.coerce(b.bind(row.get(i)), t.def().columns().get(targets[i]).type());
            }
        }
    }

    /** Runs a prepared statement, planning it with the parameter types it was prepared with. */
    public Result execute(Prepared p, List<Object> params) {
        if (p.statement() == null) return Result.command("");
        Map<Integer, Type> saved = presetParamTypes;
        presetParamTypes = p.known();
        try {
            return execute(p.statement(), params);
        } finally {
            presetParamTypes = saved;
        }
    }
}
