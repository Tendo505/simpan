package io.github.tendo505.simpan.catalog;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.types.Type;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The schema: tables, their columns and indexes, and the statistics the
 * planner uses. It's written whole into the WAL on every DDL change and into
 * each checkpoint, so recovery always finds the schema that matches the data.
 */
public final class Catalog {
    public record Column(String name, Type type, boolean notNull, Object defaultValue) {}

    /** {@code columns} are positions in the table's column list. */
    public record IndexDef(String name, int fileId, List<Integer> columns, boolean unique, boolean primary) {}

    public record TableDef(String name, int fileId, List<Column> columns, List<IndexDef> indexes) {
        public int columnIndex(String column) {
            for (int i = 0; i < columns.size(); i++) if (columns.get(i).name().equals(column)) return i;
            return -1;
        }

        public Type[] types() {
            Type[] t = new Type[columns.size()];
            for (int i = 0; i < t.length; i++) t[i] = columns.get(i).type();
            return t;
        }
    }

    /** Planner statistics: live rows (approximate) and, after ANALYZE, distinct values per column. */
    public record Stats(long rows, long[] distinct) {}

    private final Map<String, TableDef> tables = new LinkedHashMap<>();
    private final Map<String, Stats> stats = new LinkedHashMap<>();
    private int nextFileId = 1;

    public synchronized TableDef table(String name) {
        return tables.get(name);
    }

    public synchronized TableDef require(String name) {
        TableDef t = tables.get(name);
        if (t == null) throw new SqlException(SqlException.UNDEFINED_TABLE, "relation \"" + name + "\" does not exist");
        return t;
    }

    public synchronized Collection<TableDef> tables() {
        return List.copyOf(tables.values());
    }

    public synchronized int allocateFileId() {
        return nextFileId++;
    }

    public synchronized void put(TableDef t) {
        tables.put(t.name(), t);
    }

    public synchronized void remove(String name) {
        tables.remove(name);
        stats.remove(name);
    }

    /** The table that owns the index, or null. */
    public synchronized TableDef tableOfIndex(String indexName) {
        for (TableDef t : tables.values()) for (IndexDef i : t.indexes()) if (i.name().equals(indexName)) return t;
        return null;
    }

    public synchronized boolean nameInUse(String name) {
        return tables.containsKey(name) || tableOfIndex(name) != null;
    }

    public synchronized Stats stats(String table) {
        return stats.get(table);
    }

    public synchronized void setStats(String table, Stats s) {
        stats.put(table, s);
    }

    // ---------------- serialization ----------------

    public synchronized byte[] serialize() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(1); // format version
            out.writeInt(nextFileId);
            out.writeInt(tables.size());
            for (TableDef t : tables.values()) {
                out.writeUTF(t.name());
                out.writeInt(t.fileId());
                out.writeInt(t.columns().size());
                for (Column c : t.columns()) {
                    out.writeUTF(c.name());
                    out.writeByte(c.type().ordinal());
                    out.writeBoolean(c.notNull());
                    writeValue(out, c.defaultValue());
                }
                out.writeInt(t.indexes().size());
                for (IndexDef i : t.indexes()) {
                    out.writeUTF(i.name());
                    out.writeInt(i.fileId());
                    out.writeInt(i.columns().size());
                    for (int c : i.columns()) out.writeInt(c);
                    out.writeBoolean(i.unique());
                    out.writeBoolean(i.primary());
                }
                Stats s = stats.get(t.name());
                out.writeBoolean(s != null);
                if (s != null) {
                    out.writeLong(s.rows());
                    out.writeInt(s.distinct() == null ? -1 : s.distinct().length);
                    if (s.distinct() != null) for (long d : s.distinct()) out.writeLong(d);
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Catalog deserialize(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != 1) throw new IOException("unknown catalog format");
            Catalog c = new Catalog();
            c.nextFileId = in.readInt();
            int tableCount = in.readInt();
            for (int ti = 0; ti < tableCount; ti++) {
                String name = in.readUTF();
                int fileId = in.readInt();
                int columnCount = in.readInt();
                List<Column> columns = new ArrayList<>();
                for (int ci = 0; ci < columnCount; ci++) {
                    columns.add(new Column(in.readUTF(), Type.values()[in.readByte()], in.readBoolean(), readValue(in)));
                }
                int indexCount = in.readInt();
                List<IndexDef> indexes = new ArrayList<>();
                for (int ii = 0; ii < indexCount; ii++) {
                    String iname = in.readUTF();
                    int ifile = in.readInt();
                    int n = in.readInt();
                    List<Integer> cols = new ArrayList<>();
                    for (int k = 0; k < n; k++) cols.add(in.readInt());
                    indexes.add(new IndexDef(iname, ifile, List.copyOf(cols), in.readBoolean(), in.readBoolean()));
                }
                c.tables.put(name, new TableDef(name, fileId, List.copyOf(columns), List.copyOf(indexes)));
                if (in.readBoolean()) {
                    long rows = in.readLong();
                    int n = in.readInt();
                    long[] distinct = n < 0 ? null : new long[n];
                    for (int k = 0; k < n; k++) distinct[k] = in.readLong();
                    c.stats.put(name, new Stats(rows, distinct));
                }
            }
            return c;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeValue(DataOutputStream out, Object v) throws IOException {
        switch (v) {
            case null -> out.writeByte(0);
            case Long l -> {
                out.writeByte(1);
                out.writeLong(l);
            }
            case Double d -> {
                out.writeByte(2);
                out.writeDouble(d);
            }
            case String s -> {
                out.writeByte(3);
                out.writeUTF(s);
            }
            case Boolean b -> {
                out.writeByte(4);
                out.writeBoolean(b);
            }
            default -> throw new IllegalArgumentException(v.getClass().toString());
        }
    }

    private static Object readValue(DataInputStream in) throws IOException {
        return switch (in.readByte()) {
            case 0 -> null;
            case 1 -> in.readLong();
            case 2 -> in.readDouble();
            case 3 -> in.readUTF();
            case 4 -> in.readBoolean();
            default -> throw new IOException("bad value tag");
        };
    }
}
