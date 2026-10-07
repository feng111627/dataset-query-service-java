package com.github.feng111627.datasetqueryservicejava.model;

import java.util.List;

/** Query: filters, column selection, aggregations, sort and pagination.
 *
 * <p>snapshotSeq is a pagination token: the first page of a result carries
 * the snapshot's high-water mark; passing it back on subsequent pages makes
 * every page read the same consistent snapshot.</p>
 */
public record QueryRequest(
        List<Filter> filters,
        List<String> columns,
        List<Aggregation> aggregations,
        List<SortField> sort,
        Integer offset,
        Integer limit,
        Long snapshotSeq) {

    public record Filter(String field, Op op, Object value) {
    }

    public enum Op {
        EQ, NE, GT, GTE, LT, LTE,
        /** Matches records whose field value is null or absent (value must be null). */
        IS_NULL,
        /** Matches records whose field value is present and non-null (value must be null). */
        IS_NOT_NULL
    }

    public record Aggregation(AggFunc func, String field, String alias) {
    }

    public enum AggFunc { COUNT, SUM, AVG, MIN, MAX }

    public record SortField(String field, boolean ascending) {
    }
}
