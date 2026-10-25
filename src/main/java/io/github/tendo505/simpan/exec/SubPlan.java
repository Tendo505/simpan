package io.github.tendo505.simpan.exec;

import java.util.List;
import java.util.Set;

/**
 * A subquery inside an expression. It doesn't depend on the outer row, so it
 * runs once per statement and its result is kept in the {@link ExecContext}.
 */
public record SubPlan(int id, Operator plan) {
    /** The rows, plus (for IN) the first column's values as hash keys and whether any was NULL. */
    public record Result(List<Object[]> rows, Set<Object> keys, boolean hasNull) {}

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
