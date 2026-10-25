package io.github.tendo505.simpan.plan;

import io.github.tendo505.simpan.SqlException;
import io.github.tendo505.simpan.types.Type;
import java.util.ArrayList;
import java.util.List;

/** The columns a FROM clause makes visible, in order, each with the table name or alias it came from. */
public final class Scope {
    public record Entry(String qualifier, String name, Type type) {
        public String display() {
            return qualifier == null ? name : qualifier + "." + name;
        }
    }

    private final List<Entry> entries;

    public Scope(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static Scope empty() {
        return new Scope(List.of());
    }

    public List<Entry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public Entry get(int i) {
        return entries.get(i);
    }

    /** The position of {@code qualifier.name} (or just {@code name}), or an error PostgreSQL would also give. */
    public int resolve(String qualifier, String name) {
        int found = -1;
        boolean qualifierSeen = false;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (qualifier != null) {
                if (!qualifier.equals(e.qualifier())) continue;
                qualifierSeen = true;
            }
            if (!e.name().equals(name)) continue;
            if (found >= 0) {
                throw new SqlException(SqlException.AMBIGUOUS_COLUMN, "column reference \"" + name + "\" is ambiguous");
            }
            found = i;
        }
        if (found >= 0) return found;
        if (qualifier != null && !qualifierSeen) {
            throw new SqlException(SqlException.UNDEFINED_TABLE, "missing FROM-clause entry for table \"" + qualifier + "\"");
        }
        throw new SqlException(SqlException.UNDEFINED_COLUMN, "column " + (qualifier == null ? "" : qualifier + ".") + "\"" + name + "\" does not exist");
    }

    /** True if the name resolves (exactly once) here. */
    public boolean has(String qualifier, String name) {
        try {
            resolve(qualifier, name);
            return true;
        } catch (SqlException e) {
            return false;
        }
    }

    /** Positions of all columns, or all from one table, for {@code *} and {@code t.*}. */
    public List<Integer> star(String qualifier) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) if (qualifier == null || qualifier.equals(entries.get(i).qualifier())) out.add(i);
        if (qualifier != null && out.isEmpty()) {
            throw new SqlException(SqlException.UNDEFINED_TABLE, "missing FROM-clause entry for table \"" + qualifier + "\"");
        }
        return out;
    }
}
