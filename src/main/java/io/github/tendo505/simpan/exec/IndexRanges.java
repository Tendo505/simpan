package io.github.tendo505.simpan.exec;

import io.github.tendo505.simpan.engine.Table;
import io.github.tendo505.simpan.engine.Table.Index;
import java.util.List;

/** The key ranges an index scan reads, for code outside the plan (UPDATE and DELETE find their rows this way). */
public final class IndexRanges {
    private IndexRanges() {}

    public static List<IndexProbe.Range> of(ExecContext ctx, Index index, Table table, List<IndexProbe> probes) {
        return Operators.ranges(ctx, index, table, probes);
    }
}
