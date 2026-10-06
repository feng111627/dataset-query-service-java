package com.github.feng111627.datasetqueryservicejava.model;

import java.util.List;
import java.util.Map;

/** Result of a query: selected rows, aggregation values, page info and the
 * snapshot token (snapshotSeq) to pass back for stable multi-page reads. */
public record QueryResult(
        List<Map<String, Object>> rows,
        Map<String, Object> aggregations,
        long total,
        int offset,
        int limit,
        long snapshotSeq) {
}
