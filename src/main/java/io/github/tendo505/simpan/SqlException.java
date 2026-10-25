package io.github.tendo505.simpan;

import java.io.Serial;

/**
 * An error to report to the client, with a PostgreSQL SQLSTATE code so
 * drivers and tools can tell what kind of error it is.
 */
public final class SqlException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String SYNTAX = "42601";
    public static final String UNDEFINED_TABLE = "42P01";
    public static final String UNDEFINED_COLUMN = "42703";
    public static final String UNDEFINED_FUNCTION = "42883";
    public static final String UNDEFINED_OBJECT_TYPE = "42704";
    public static final String UNDEFINED_PARAMETER = "42P02";
    public static final String INVALID_COLUMN_REFERENCE = "42P10";
    public static final String DUPLICATE_TABLE = "42P07";
    public static final String DUPLICATE_OBJECT = "42710";
    public static final String DUPLICATE_COLUMN = "42701";
    public static final String AMBIGUOUS_COLUMN = "42702";
    public static final String DATATYPE_MISMATCH = "42804";
    public static final String CANNOT_COERCE = "42846";
    public static final String GROUPING = "42803";
    public static final String INVALID_TABLE_DEFINITION = "42P16";
    public static final String DATA_CORRUPTED = "XX001";
    public static final String INVALID_TEXT = "22P02";
    public static final String INVALID_PARAMETER_VALUE = "22023";
    public static final String DIVISION_BY_ZERO = "22012";
    public static final String OUT_OF_RANGE = "22003";
    public static final String NOT_NULL = "23502";
    public static final String UNIQUE = "23505";
    public static final String SERIALIZATION = "40001";
    public static final String LOCK_TIMEOUT = "55P03";
    public static final String DEADLOCK = "40P01";
    public static final String DEPENDENT_OBJECTS = "2BP01";
    public static final String PROGRAM_LIMIT = "54000";
    public static final String IN_FAILED_TRANSACTION = "25P02";
    public static final String ACTIVE_TRANSACTION = "25001";
    public static final String NO_ACTIVE_TRANSACTION = "25P01";
    public static final String FEATURE_NOT_SUPPORTED = "0A000";
    public static final String CARDINALITY = "21000";
    public static final String PROTOCOL = "08P01";
    public static final String INTERNAL = "XX000";

    private final String sqlState;

    public SqlException(String sqlState, String message) {
        super(message);
        this.sqlState = sqlState;
    }

    public String sqlState() {
        return sqlState;
    }

    public static SqlException syntax(String message) {
        return new SqlException(SYNTAX, message);
    }

    public static SqlException unsupported(String what) {
        return new SqlException(FEATURE_NOT_SUPPORTED, what + " is not supported");
    }
}
