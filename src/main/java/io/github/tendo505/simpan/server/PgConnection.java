package io.github.tendo505.simpan.server;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.engine.Database;
import io.github.tendo505.simpan.engine.Session;
import io.github.tendo505.simpan.sql.Parser;
import io.github.tendo505.simpan.types.Type;
import io.github.tendo505.simpan.types.Values;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One client speaking PostgreSQL's frontend/backend protocol (version 3):
 * the startup handshake, the simple query protocol ({@code psql}), and the
 * extended protocol (Parse, Bind, Describe, Execute, Sync) that drivers like
 * JDBC use for prepared statements, with text or binary values.
 */
final class PgConnection implements Runnable {
    private static final int PROTOCOL_3 = 196608;
    private static final int SSL_REQUEST = 80877103;
    private static final int GSS_REQUEST = 80877104;
    private static final int CANCEL_REQUEST = 80877102;

    private final Socket socket;
    private final Database db;
    private final int pid;
    private DataInputStream in;
    private DataOutputStream out;
    private Session session;

    private final Map<String, Session.Prepared> statements = new HashMap<>();
    private final Map<String, Portal> portals = new HashMap<>();
    private final Map<String, int[]> declaredOids = new HashMap<>();
    private boolean skipUntilSync;

    /** A bound statement: its parameters, the formats wanted for results, and rows not yet sent. */
    private static final class Portal {
        final Session.Prepared statement;
        final List<Object> params;
        final short[] resultFormats;
        Session.Result result;
        int sent;

        Portal(Session.Prepared statement, List<Object> params, short[] resultFormats) {
            this.statement = statement;
            this.params = params;
            this.resultFormats = resultFormats;
        }
    }

    PgConnection(Socket socket, Database db, int pid) {
        this.socket = socket;
        this.db = db;
        this.pid = pid;
    }

    @Override
    public void run() {
        try (socket) {
            socket.setTcpNoDelay(true);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            if (!startup()) return;
            session = new Session(db);
            loop();
        } catch (EOFException e) {
            // client went away
        } catch (IOException e) {
            // connection dropped
        } finally {
            if (session != null) session.close();
        }
    }

    // ---------------- startup ----------------

    private boolean startup() throws IOException {
        while (true) {
            int length = in.readInt();
            int code = in.readInt();
            if (code == SSL_REQUEST || code == GSS_REQUEST) {
                out.write('N'); // no encryption; the client carries on in plain text
                out.flush();
                continue;
            }
            if (code == CANCEL_REQUEST) return false;
            if (code != PROTOCOL_3) {
                sendError(new SqlException(SqlException.PROTOCOL, "unsupported frontend protocol " + (code >> 16) + "." + (code & 0xFFFF)));
                out.flush();
                return false;
            }
            byte[] rest = new byte[length - 8];
            in.readFully(rest);
            Map<String, String> params = new HashMap<>();
            List<String> parts = cstrings(rest);
            for (int i = 0; i + 1 < parts.size(); i += 2) params.put(parts.get(i), parts.get(i + 1));
            message('R', b -> b.putInt(0)); // AuthenticationOk: no password
            parameter("server_version", "16.0");
            parameter("server_encoding", "UTF8");
            parameter("client_encoding", "UTF8");
            parameter("DateStyle", "ISO, MDY");
            parameter("TimeZone", "UTC");
            parameter("integer_datetimes", "on");
            parameter("standard_conforming_strings", "on");
            parameter("is_superuser", "on");
            parameter("session_authorization", params.getOrDefault("user", "simpan"));
            parameter("application_name", params.getOrDefault("application_name", ""));
            message('K', b -> b.putInt(pid).putInt(pid * 31 + 7));
            ready();
            return true;
        }
    }

    private static List<String> cstrings(byte[] bytes) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                if (i > start) out.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        return out;
    }

    // ---------------- the message loop ----------------

    private void loop() throws IOException {
        while (true) {
            int type = in.read();
            if (type < 0) return;
            int length = in.readInt();
            byte[] body = new byte[length - 4];
            in.readFully(body);
            ByteBuffer msg = ByteBuffer.wrap(body);
            if (type == 'X') return;
            if (skipUntilSync && type != 'S') continue; // after an error in the extended protocol, ignore until Sync
            try {
                switch (type) {
                    case 'Q' -> simpleQuery(cstring(msg));
                    case 'P' -> parse(msg);
                    case 'B' -> bind(msg);
                    case 'D' -> describe(msg);
                    case 'E' -> execute(msg);
                    case 'C' -> close(msg);
                    case 'S' -> {
                        // Everything since the last Sync ran as one implicit transaction: commit it, or roll it back after an error.
                        session.endImplicit(!skipUntilSync);
                        skipUntilSync = false;
                        ready();
                    }
                    case 'H' -> out.flush();
                    default -> throw new SqlException(SqlException.PROTOCOL, "unsupported message type '" + (char) type + "'");
                }
            } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
                sendError(asSqlError(e));
                session.noteError();
                if (type == 'Q') ready();
                else skipUntilSync = true;
            }
            if (type == 'Q' || type == 'S' || type == 'H') out.flush();
        }
    }

    private static SqlException asSqlError(Throwable e) {
        return switch (e) {
            case SqlException sql -> sql;
            case StackOverflowError so -> new SqlException("54001", "stack depth limit exceeded (the statement is nested too deeply)");
            case OutOfMemoryError oom -> new SqlException("53200", "out of memory");
            default -> new SqlException(SqlException.INTERNAL, String.valueOf(e));
        };
    }

    private static String cstring(ByteBuffer b) {
        int start = b.position();
        while (b.get() != 0) {
            // scan to the terminator
        }
        return new String(b.array(), start, b.position() - start - 1, StandardCharsets.UTF_8);
    }

    // ---------------- simple query ----------------

    /**
     * Runs each statement in turn, sending its results as it goes. Several
     * statements in one query run as one implicit transaction: the first
     * error stops them and rolls back the ones before it. Always ends with
     * one ReadyForQuery.
     */
    private void simpleQuery(String sql) throws IOException {
        boolean batch = false;
        boolean ok = false;
        try {
            var statements = Parser.parseScript(sql);
            if (statements.isEmpty()) message('I', b -> { }); // EmptyQueryResponse
            batch = statements.size() > 1;
            if (batch) session.beginImplicit();
            for (var statement : statements) {
                Session.Result r = session.execute(statement, List.of());
                sendResult(r, null, 0, r.rows().size());
            }
            ok = true;
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            sendError(asSqlError(e));
            session.noteError();
        } finally {
            if (batch) session.endImplicit(ok);
        }
        ready();
    }

    // ---------------- extended query ----------------

    private void parse(ByteBuffer msg) throws IOException {
        String name = cstring(msg);
        String sql = cstring(msg);
        int n = msg.getShort() & 0xFFFF;
        List<Type> declared = new ArrayList<>();
        int[] oids = new int[n];
        for (int i = 0; i < n; i++) {
            oids[i] = msg.getInt();
            declared.add(typeOf(oids[i]));
        }
        statements.put(name, session.prepare(sql, declared));
        declaredOids.put(name, oids);
        message('1', b -> { });
    }

    private static Type typeOf(int oid) {
        return switch (oid) {
            case 20, 21, 23, 26 -> Type.BIGINT;
            case 700, 701, 1700 -> Type.DOUBLE;
            case 25, 1042, 1043, 19 -> Type.TEXT;
            case 16 -> Type.BOOLEAN;
            default -> Type.UNKNOWN;
        };
    }

    private void bind(ByteBuffer msg) throws IOException {
        String portal = cstring(msg);
        String name = cstring(msg);
        Session.Prepared stmt = statements.get(name);
        if (stmt == null) throw new SqlException("26000", "prepared statement \"" + name + "\" does not exist");
        short[] paramFormats = new short[msg.getShort() & 0xFFFF];
        for (int i = 0; i < paramFormats.length; i++) paramFormats[i] = msg.getShort();
        int n = msg.getShort() & 0xFFFF;
        int[] oids = declaredOids.getOrDefault(name, new int[0]);
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int len = msg.getInt();
            if (len < 0) {
                params.add(null);
                continue;
            }
            byte[] value = new byte[len];
            msg.get(value);
            short format = paramFormats.length == 0 ? 0 : paramFormats.length == 1 ? paramFormats[0] : paramFormats[i];
            Type t = i < stmt.paramTypes().size() ? stmt.paramTypes().get(i) : Type.TEXT;
            int oid = i < oids.length ? oids[i] : 0;
            params.add(format == 0 ? new String(value, StandardCharsets.UTF_8) : decodeBinary(value, oid, t));
        }
        short[] resultFormats = new short[msg.getShort() & 0xFFFF];
        for (int i = 0; i < resultFormats.length; i++) resultFormats[i] = msg.getShort();
        portals.put(portal, new Portal(stmt, params, resultFormats));
        message('2', b -> { });
    }

    /** A binary parameter, read as the type the client declared (or, if it declared none, the one inferred). */
    private static Object decodeBinary(byte[] v, int oid, Type inferred) {
        ByteBuffer b = ByteBuffer.wrap(v);
        return switch (oid) {
            case 20 -> b.getLong();
            case 23 -> (long) b.getInt();
            case 21 -> (long) b.getShort();
            case 701 -> b.getDouble();
            case 700 -> (double) b.getFloat();
            case 16 -> v[0] != 0;
            case 1700 -> numeric(b);
            case 25, 1043, 1042, 19 -> new String(v, StandardCharsets.UTF_8);
            case 0 -> switch (inferred) {
                case BIGINT -> v.length == 2 ? (long) b.getShort() : v.length == 4 ? (long) b.getInt() : b.getLong();
                case DOUBLE -> v.length == 4 ? (double) b.getFloat() : b.getDouble();
                case BOOLEAN -> v[0] != 0;
                default -> new String(v, StandardCharsets.UTF_8);
            };
            default -> throw new SqlException("22P03", "binary format for type OID " + oid + " is not supported; send it as text");
        };
    }

    /** PostgreSQL's binary numeric: digit count, weight, sign, scale, then base-10000 digits. */
    private static Object numeric(ByteBuffer b) {
        int ndigits = b.getShort() & 0xFFFF;
        int weight = b.getShort();
        int sign = b.getShort() & 0xFFFF;
        b.getShort(); // display scale
        if (sign == 0xC000) return Double.NaN;
        if (sign == 0xD000) return Double.POSITIVE_INFINITY;
        if (sign == 0xF000) return Double.NEGATIVE_INFINITY;
        java.math.BigDecimal value = java.math.BigDecimal.ZERO;
        for (int i = 0; i < ndigits; i++) {
            int digit = b.getShort();
            value = value.add(java.math.BigDecimal.valueOf(digit).scaleByPowerOfTen(4 * (weight - i)));
        }
        return (sign == 0x4000 ? value.negate() : value).doubleValue();
    }

    private void describe(ByteBuffer msg) throws IOException {
        byte kind = msg.get();
        String name = cstring(msg);
        if (kind == 'S') {
            Session.Prepared stmt = statements.get(name);
            if (stmt == null) throw new SqlException("26000", "prepared statement \"" + name + "\" does not exist");
            ByteBuffer desc = ByteBuffer.allocate(2 + 4 * stmt.paramTypes().size());
            desc.putShort((short) stmt.paramTypes().size());
            for (Type t : stmt.paramTypes()) desc.putInt(t.oid());
            raw('t', desc.array());
            rowDescription(stmt.columns(), stmt.columnTypes(), null);
        } else {
            Portal p = portals.get(name);
            if (p == null) throw new SqlException("34000", "portal \"" + name + "\" does not exist");
            rowDescription(p.statement.columns(), p.statement.columnTypes(), p.resultFormats);
        }
    }

    private void execute(ByteBuffer msg) throws IOException {
        String name = cstring(msg);
        int maxRows = msg.getInt();
        Portal p = portals.get(name);
        if (p == null) throw new SqlException("34000", "portal \"" + name + "\" does not exist");
        if (p.statement.statement() == null) {
            message('I', b -> { });
            return;
        }
        if (p.result == null) {
            session.beginImplicit();
            p.result = session.execute(p.statement, p.params);
        }
        Session.Result r = p.result;
        int end = maxRows <= 0 ? r.rows().size() : Math.min(r.rows().size(), p.sent + maxRows);
        for (String n : r.notices()) notice(n);
        for (int i = p.sent; i < end; i++) dataRow(r.rows().get(i), r.types(), p.resultFormats);
        p.sent = end;
        if (end < r.rows().size()) {
            message('s', b -> { }); // PortalSuspended: the client will ask for more
        } else {
            commandComplete(r.tag());
        }
    }

    private void close(ByteBuffer msg) throws IOException {
        byte kind = msg.get();
        String name = cstring(msg);
        if (kind == 'S') statements.remove(name);
        else portals.remove(name);
        message('3', b -> { });
    }

    // ---------------- responses ----------------

    private void sendResult(Session.Result r, short[] formats, int from, int to) throws IOException {
        for (String n : r.notices()) notice(n);
        if (r.hasRows()) {
            rowDescription(r.columns(), r.types(), formats);
            for (int i = from; i < to; i++) dataRow(r.rows().get(i), r.types(), formats);
        }
        commandComplete(r.tag());
    }

    private void rowDescription(List<String> names, List<Type> types, short[] formats) throws IOException {
        if (names.isEmpty()) {
            message('n', b -> { }); // NoData
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bytes);
        d.writeShort(names.size());
        for (int i = 0; i < names.size(); i++) {
            d.write(names.get(i).getBytes(StandardCharsets.UTF_8));
            d.write(0);
            d.writeInt(0);   // table oid
            d.writeShort(0); // column number
            Type t = types.get(i);
            d.writeInt(t.oid());
            d.writeShort(t.wireSize());
            d.writeInt(-1);  // type modifier
            d.writeShort(format(formats, i));
        }
        raw('T', bytes.toByteArray());
    }

    private static int format(short[] formats, int column) {
        if (formats == null || formats.length == 0) return 0;
        return formats.length == 1 ? formats[0] : formats[column];
    }

    private void dataRow(Object[] row, List<Type> types, short[] formats) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bytes);
        d.writeShort(row.length);
        for (int i = 0; i < row.length; i++) {
            Object v = row[i];
            if (v == null) {
                d.writeInt(-1);
                continue;
            }
            byte[] value;
            if (format(formats, i) == 1) {
                switch (types.get(i)) {
                    case BIGINT -> value = ByteBuffer.allocate(8).putLong(((Number) v).longValue()).array();
                    case DOUBLE -> value = ByteBuffer.allocate(8).putDouble(((Number) v).doubleValue()).array();
                    case BOOLEAN -> value = new byte[] {(byte) ((Boolean) v ? 1 : 0)};
                    default -> value = Values.format(v).getBytes(StandardCharsets.UTF_8);
                }
            } else {
                value = Values.format(v).getBytes(StandardCharsets.UTF_8);
            }
            d.writeInt(value.length);
            d.write(value);
        }
        raw('D', bytes.toByteArray());
    }

    private void commandComplete(String tag) throws IOException {
        byte[] t = tag.getBytes(StandardCharsets.UTF_8);
        message('C', b -> b.put(t).put((byte) 0));
    }

    private void ready() throws IOException {
        char status = session == null ? 'I' : session.status();
        message('Z', b -> b.put((byte) status));
        out.flush();
    }

    private void parameter(String name, String value) throws IOException {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        message('S', b -> b.put(n).put((byte) 0).put(v).put((byte) 0));
    }

    private void sendError(SqlException e) throws IOException {
        fields('E', "ERROR", e.sqlState(), e.getMessage());
    }

    private void notice(String text) throws IOException {
        fields('N', "NOTICE", "00000", text);
    }

    private void fields(char type, String severity, String code, String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (String[] f : new String[][] {{"S", severity}, {"V", severity}, {"C", code}, {"M", text == null ? "" : text}}) {
            bytes.write(f[0].charAt(0));
            bytes.write(f[1].getBytes(StandardCharsets.UTF_8));
            bytes.write(0);
        }
        bytes.write(0);
        raw(type, bytes.toByteArray());
    }

    private interface Body {
        void write(ByteBuffer b);
    }

    private void message(char type, Body body) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(1 << 16); // only small fixed messages are built this way
        body.write(b);
        byte[] bytes = new byte[b.position()];
        b.flip();
        b.get(bytes);
        raw(type, bytes);
    }

    private void raw(char type, byte[] body) throws IOException {
        out.write(type);
        out.writeInt(body.length + 4);
        out.write(body);
    }
}
