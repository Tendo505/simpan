package io.github.tendo505.simpan.types;

import io.github.tendo505.simpan.SqlException;
import java.util.Locale;

/**
 * The column types. Values are plain Java objects: {@code Long},
 * {@code Double}, {@code String}, {@code Boolean}, and {@code null} for SQL
 * NULL. {@link #UNKNOWN} is the type of a bare NULL literal or an untyped
 * parameter until context gives it one.
 */
public enum Type {
    BIGINT("bigint", 20, 8),
    DOUBLE("double precision", 701, 8),
    TEXT("text", 25, -1),
    BOOLEAN("boolean", 16, 1),
    UNKNOWN("unknown", 705, -2);

    private final String sqlName;
    private final int oid;
    private final int wireSize;

    Type(String sqlName, int oid, int wireSize) {
        this.sqlName = sqlName;
        this.oid = oid;
        this.wireSize = wireSize;
    }

    /** As PostgreSQL names it, for error messages and the catalog. */
    public String sqlName() {
        return sqlName;
    }

    /** The PostgreSQL type OID, which clients use to decode values. */
    public int oid() {
        return oid;
    }

    public int wireSize() {
        return wireSize;
    }

    public boolean isNumeric() {
        return this == BIGINT || this == DOUBLE;
    }

    /** Parses a type as written in SQL: {@code int}, {@code varchar(20)}, {@code double precision}, ... */
    public static Type fromSql(String name) {
        String n = name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        int paren = n.indexOf('(');
        if (paren >= 0) n = n.substring(0, paren).trim();
        return switch (n) {
            case "int", "integer", "int4", "int8", "bigint", "smallint", "int2" -> BIGINT;
            case "double precision", "double", "float", "float8", "float4", "real", "numeric", "decimal" -> DOUBLE;
            case "text", "varchar", "character varying", "char", "character", "string" -> TEXT;
            case "boolean", "bool" -> BOOLEAN;
            default -> throw new SqlException(SqlException.UNDEFINED_OBJECT_TYPE, "type \"" + name + "\" does not exist");
        };
    }

    /** The type of a Java value, or UNKNOWN for null. */
    public static Type of(Object v) {
        if (v == null) return UNKNOWN;
        if (v instanceof Long) return BIGINT;
        if (v instanceof Double) return DOUBLE;
        if (v instanceof String) return TEXT;
        if (v instanceof Boolean) return BOOLEAN;
        throw new IllegalArgumentException("not a SQL value: " + v.getClass());
    }
}
