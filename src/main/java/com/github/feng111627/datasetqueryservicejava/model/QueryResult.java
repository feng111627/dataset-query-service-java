package com.github.feng111627.datasetqueryservicejava.model;

import java.util.List;
import java.util.Map;

/** Result of a query: selected rows, aggregation values, page info and the
 * snapshot token (snapshotSeq) to pass back for stable multi-page reads.
 *
 * <p>{@code snapshotStale} indicates whether a data-modifying event (currently
 * a backfill) has been committed after the pinned {@code snapshotSeq}. Old
 * tokens never fail and never mix states: the page is still a consistent view
 * as of the token; callers that want the current state should drop the token
 * and restart pagination from page one.</p>
 */
public record QueryResult(
        List<Map<String, Object>> rows,
        Map<String, Object> aggregations,
        long total,
        int offset,
        int limit,
        long snapshotSeq,
        boolean snapshotStale) {
}
