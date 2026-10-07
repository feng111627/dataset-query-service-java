package com.github.feng111627.datasetqueryservicejava.query;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldType;
import com.github.feng111627.datasetqueryservicejava.model.QueryRequest;
import com.github.feng111627.datasetqueryservicejava.model.QueryResult;
import com.github.feng111627.datasetqueryservicejava.model.Snapshot;
import com.github.feng111627.datasetqueryservicejava.model.StoredRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes filter / projection / aggregation / sort / pagination over a
 * consistent snapshot.
 *
 * <p>Null semantics (explicit {@code null} and absent fields are identical):</p>
 * <ul>
 *   <li>{@code IS_NULL}/{@code IS_NOT_NULL} distinguish nulls from values;</li>
 *   <li>{@code EQ}/{@code NE} against null behave as IS NULL / IS NOT NULL;</li>
 *   <li>range comparisons ({@code GT} etc.) never fail on a null operand and
 *       simply do not match — other records are returned normally;</li>
 *   <li>nulls always sort last, ascending or descending;</li>
 *   <li>{@code COUNT} counts all matched rows; {@code SUM}/{@code AVG}/
 *       {@code MIN}/{@code MAX} skip nulls and return null when no non-null
 *       value exists.</li>
 * </ul>
 *
 * <p>Result order is stable and reproducible: requested sort fields are
 * applied first, and the record sequence number is always appended as the
 * final tie-breaker, so the total order is deterministic.</p>
 */
@Component
public class QueryEngine {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    public QueryResult execute(DatasetSchema schema, long schemaVersion, Snapshot snapshot,
                               QueryRequest request) {
        QueryRequest req = request == null
                ? new QueryRequest(null, null, null, null, null, null, null)
                : request;

        validateRequest(schema, req);

        // 1. filter
        List<StoredRecord> matched = new ArrayList<>();
        for (StoredRecord record : snapshot.records()) {
            if (matchesAll(record.values(), req.filters())) {
                matched.add(record);
            }
        }

        // 2. aggregations are computed over the full filtered set (before paging)
        Map<String, Object> aggregations = computeAggregations(matched, req.aggregations());

        // 3. stable sort: requested keys, then seq as final tie-breaker
        Comparator<StoredRecord> comparator = buildComparator(req.sort());
        matched.sort(comparator);

        // 4. paginate
        int offset = req.offset() == null ? 0 : req.offset();
        int limit = req.limit() == null ? DEFAULT_LIMIT : req.limit();
        if (offset < 0) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR, "offset must be >= 0");
        }
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR,
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        int from = Math.min(offset, matched.size());
        int to = Math.min(from + limit, matched.size());
        List<StoredRecord> page = matched.subList(from, to);

        // 5. project columns
        List<Map<String, Object>> rows = new ArrayList<>(page.size());
        for (StoredRecord record : page) {
            rows.add(project(schema, record.values(), req.columns()));
        }

        return new QueryResult(rows, aggregations, matched.size(), offset, limit,
                snapshot.highWaterMark(), snapshot.stale());
    }

    private void validateRequest(DatasetSchema schema, QueryRequest req) {
        List<String> problems = new ArrayList<>();
        if (req.filters() != null) {
            for (QueryRequest.Filter f : req.filters()) {
                if (f.field() == null || schema.field(f.field()) == null) {
                    problems.add("unknown filter field: " + f.field());
                } else if (f.op() == null) {
                    problems.add("filter on '" + f.field() + "' is missing an operator");
                } else {
                    switch (f.op()) {
                        case IS_NULL, IS_NOT_NULL -> {
                            if (f.value() != null) {
                                problems.add(f.op() + " on '" + f.field()
                                        + "' must not carry a value");
                            }
                        }
                        case GT, GTE, LT, LTE -> {
                            if (f.value() == null) {
                                problems.add(f.op() + " on '" + f.field()
                                        + "' requires a non-null comparison value");
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
        }
        if (req.columns() != null) {
            for (String column : req.columns()) {
                if (schema.field(column) == null) {
                    problems.add("unknown column: " + column);
                }
            }
        }
        if (req.aggregations() != null) {
            for (QueryRequest.Aggregation agg : req.aggregations()) {
                if (agg.func() == null) {
                    problems.add("aggregation is missing a function");
                    continue;
                }
                if (agg.func() != QueryRequest.AggFunc.COUNT) {
                    if (agg.field() == null || schema.field(agg.field()) == null) {
                        problems.add("unknown aggregation field: " + agg.field());
                    } else if ((agg.func() == QueryRequest.AggFunc.SUM
                            || agg.func() == QueryRequest.AggFunc.AVG)
                            && !isNumeric(schema.field(agg.field()).type())) {
                        problems.add(agg.func() + " requires a numeric field: " + agg.field());
                    }
                }
            }
        }
        if (req.sort() != null) {
            for (QueryRequest.SortField s : req.sort()) {
                if (s.field() == null || schema.field(s.field()) == null) {
                    problems.add("unknown sort field: " + s.field());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR, "invalid query", problems);
        }
    }

    private boolean isNumeric(FieldType type) {
        return type == FieldType.INTEGER || type == FieldType.DOUBLE;
    }

    private boolean matchesAll(Map<String, Object> values, List<QueryRequest.Filter> filters) {
        if (filters == null) {
            return true;
        }
        for (QueryRequest.Filter filter : filters) {
            if (!matches(values.get(filter.field()), filter)) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(Object actual, QueryRequest.Filter filter) {
        Object expected = filter.value();
        return switch (filter.op()) {
            case IS_NULL -> actual == null;
            case IS_NOT_NULL -> actual != null;
            // EQ/NE against null are explicit null tests; absent and explicit
            // null are indistinguishable (Map.get returns null for both).
            case EQ -> expected == null
                    ? actual == null
                    : actual != null && (actual.equals(expected) || numericEquals(actual, expected));
            case NE -> expected == null
                    ? actual != null
                    : actual != null && !(actual.equals(expected) || numericEquals(actual, expected));
            // A null record value never matches a range predicate; the query
            // itself stays healthy and returns other matching records.
            case GT -> actual != null && compare(actual, expected) > 0;
            case GTE -> actual != null && compare(actual, expected) >= 0;
            case LT -> actual != null && compare(actual, expected) < 0;
            case LTE -> actual != null && compare(actual, expected) <= 0;
        };
    }

    private boolean numericEquals(Object actual, Object expected) {
        if (actual instanceof Number a && expected instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compare(Object actual, Object expected) {
        if (actual instanceof Number a && expected instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        if (actual instanceof Comparable c && actual.getClass().isInstance(expected)) {
            return c.compareTo(expected);
        }
        throw new ClientException(ErrorCode.VALIDATION_ERROR,
                "values are not comparable: " + actual.getClass().getSimpleName()
                        + " vs " + expected.getClass().getSimpleName());
    }

    private Comparator<StoredRecord> buildComparator(List<QueryRequest.SortField> sort) {
        Comparator<StoredRecord> comparator = null;
        if (sort != null) {
            for (QueryRequest.SortField s : sort) {
                // direction is applied only to value comparison; nulls stay last either way
                final int sign = s.ascending() ? 1 : -1;
                Comparator<StoredRecord> c = (r1, r2) -> {
                    Object v1 = r1.values().get(s.field());
                    Object v2 = r2.values().get(s.field());
                    if (v1 == null && v2 == null) {
                        return 0;
                    }
                    if (v1 == null) {
                        return 1;
                    }
                    if (v2 == null) {
                        return -1;
                    }
                    return sign * compareValues(v1, v2);
                };
                comparator = comparator == null ? c : comparator.thenComparing(c);
            }
        }
        // seq tie-breaker guarantees a stable, reproducible total order
        Comparator<StoredRecord> bySeq = Comparator.comparingLong(StoredRecord::seq);
        return comparator == null ? bySeq : comparator.thenComparing(bySeq);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object v1, Object v2) {
        if (v1 instanceof Number a && v2 instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        if (v1 instanceof Comparable c && v1.getClass().isInstance(v2)) {
            return c.compareTo(v2);
        }
        throw new ClientException(ErrorCode.VALIDATION_ERROR,
                "sort values are not comparable: " + v1.getClass().getSimpleName()
                        + " vs " + v2.getClass().getSimpleName());
    }

    private Map<String, Object> computeAggregations(List<StoredRecord> matched,
                                                    List<QueryRequest.Aggregation> aggregations) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (aggregations == null) {
            return result;
        }
        for (QueryRequest.Aggregation agg : aggregations) {
            String alias = agg.alias() != null ? agg.alias()
                    : agg.func().name().toLowerCase()
                    + (agg.field() == null ? "" : "_" + agg.field());
            result.put(alias, compute(matched, agg));
        }
        return result;
    }

    private Object compute(List<StoredRecord> matched, QueryRequest.Aggregation agg) {
        return switch (agg.func()) {
            // COUNT counts every matched row, null fields included.
            case COUNT -> (long) matched.size();
            // SUM/AVG/MIN/MAX ignore nulls; with no non-null value the result is null.
            case SUM -> {
                Double sum = matched.stream()
                        .map(r -> r.values().get(agg.field()))
                        .filter(Number.class::isInstance)
                        .map(v -> ((Number) v).doubleValue())
                        .reduce(Double::sum)
                        .orElse(null);
                yield sum;
            }
            case AVG -> matched.stream()
                    .map(r -> r.values().get(agg.field()))
                    .filter(Number.class::isInstance)
                    .mapToDouble(v -> ((Number) v).doubleValue())
                    .average().stream().boxed().findFirst().orElse(null);
            case MIN -> extremum(matched, agg.field(), true);
            case MAX -> extremum(matched, agg.field(), false);
        };
    }

    private Object extremum(List<StoredRecord> matched, String field, boolean min) {
        Object best = null;
        for (StoredRecord record : matched) {
            Object value = record.values().get(field);
            if (value == null) {
                continue;
            }
            if (best == null) {
                best = value;
                continue;
            }
            int cmp = compareValues(best, value);
            if ((min && cmp > 0) || (!min && cmp < 0)) {
                best = value;
            }
        }
        return best;
    }

    private Map<String, Object> project(DatasetSchema schema, Map<String, Object> values,
                                        List<String> columns) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (columns == null || columns.isEmpty()) {
            // default: all declared fields, in schema order; fields added after a
            // record was written simply project as null.
            schema.fields().forEach(f -> row.put(f.name(), values.get(f.name())));
        } else {
            for (String column : columns) {
                row.put(column, values.get(column));
            }
        }
        return row;
    }
}
