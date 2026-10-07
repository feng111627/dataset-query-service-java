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

    /**
     * Filter operators. Null semantics:
     * <ul>
     *   <li>{@code IS_NULL} / {@code IS_NOT_NULL} test explicitly whether a
     *       field is absent-or-null on a record (value is ignored);</li>
     *   <li>range operators ({@code GT} {@code GTE} {@code LT} {@code LTE})
     *       never fail on null field values: a record whose field is null
     *       simply does not match;</li>
     *   <li>{@code EQ} with a null value behaves like {@code IS_NULL},
     *       {@code NE} with a null value like {@code IS_NOT_NULL}.</li>
     * </ul>
     */
    public enum Op { EQ, NE, GT, GTE, LT, LTE, IS_NULL, IS_NOT_NULL }

    public record Aggregation(AggFunc func, String field, String alias) {
    }

    public enum AggFunc { COUNT, SUM, AVG, MIN, MAX }

    public record SortField(String field, boolean ascending) {
    }
}
