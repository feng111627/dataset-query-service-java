package com.github.feng111627.datasetqueryservicejava.query;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
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
 * <p>Result order is stable and reproducible: requested sort fields are
 * applied first, and the record sequence number is always appended as the
 * final tie-breaker, so the total order is deterministic.</p>
 */
@Component
public class QueryEngine {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    public QueryResult execute(DatasetSchema schema, Snapshot snapshot, QueryRequest request) {
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
        Map<String, Object> aggregations = computeAggregations(schema, matched, req.aggregations());

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
                snapshot.highWaterMark());
    }

    private void validateRequest(DatasetSchema schema, QueryRequest req) {
        List<String> problems = new ArrayList<>();
        if (req.filters() != null) {
            for (QueryRequest.Filter f : req.filters()) {
                if (f.field() == null || schema.field(f.field()) == null) {
                    problems.add("unknown filter field: " + f.field());
                } else if (f.op() == null) {
                    problems.add("filter on '" + f.field() + "' is missing an operator");
                } else if (isRangeOp(f.op()) && f.value() == null) {
                    problems.add("range filter on '" + f.field() + "' requires a non-null value");
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

    private boolean isRangeOp(QueryRequest.Op op) {
        return switch (op) {
            case GT, GTE, LT, LTE -> true;
            default -> false;
        };
    }

    private boolean isNumeric(com.github.feng111627.datasetqueryservicejava.model.FieldType type) {
        return type == com.github.feng111627.datasetqueryservicejava.model.FieldType.INTEGER
                || type == com.github.feng111627.datasetqueryservicejava.model.FieldType.DOUBLE;
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean matches(Object actual, QueryRequest.Filter filter) {
        Object expected = filter.value();
        return switch (filter.op()) {
            case IS_NULL -> actual == null;
            case IS_NOT_NULL -> actual != null;
            case EQ -> actual == null ? expected == null : actual.equals(expected)
                    || numericEquals(actual, expected);
            case NE -> !matches(actual, new QueryRequest.Filter(filter.field(), QueryRequest.Op.EQ, expected));
            // Null field values never match a range comparison; they are
            // skipped instead of failing the whole query.
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
        if (actual == null || expected == null) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR,
                    "range comparisons require non-null values");
        }
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
                Comparator<StoredRecord> c =
                        (a, b) -> compareForSort(a.values().get(s.field()), b.values().get(s.field()),
                                s.ascending());
                comparator = comparator == null ? c : comparator.thenComparing(c);
            }
        }
        // seq tie-breaker guarantees a stable, reproducible total order
        Comparator<StoredRecord> bySeq = Comparator.comparingLong(StoredRecord::seq);
        return comparator == null ? bySeq : comparator.thenComparing(bySeq);
    }

    /**
     * Sort comparison with uniform null semantics: nulls always sort last,
     * in both ascending and descending order; numbers compare numerically
     * even across Integer/Long/Double representations.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareForSort(Object a, Object b, boolean ascending) {
        // nulls always sort last, independent of the sort direction
        if (a == null) {
            return b == null ? 0 : 1;
        }
        if (b == null) {
            return -1;
        }
        int cmp;
        if (a instanceof Number x && b instanceof Number y) {
            cmp = Double.compare(x.doubleValue(), y.doubleValue());
        } else if (a instanceof Comparable c && a.getClass().isInstance(b)) {
            cmp = c.compareTo(b);
        } else {
            throw new ClientException(ErrorCode.VALIDATION_ERROR,
                    "values are not comparable for sorting: " + a.getClass().getSimpleName()
                            + " vs " + b.getClass().getSimpleName());
        }
        if (ascending || cmp == 0) {
            return cmp;
        }
        return cmp < 0 ? 1 : -1;
    }

    /**
     * Aggregation null semantics (uniform across filter/sort/page combos,
     * since aggregations run over the full filtered set before paging):
     * <ul>
     *   <li>{@code COUNT} counts matched records, including records whose
     *       fields are null;</li>
     *   <li>{@code SUM} / {@code AVG} skip null values; {@code AVG} of an
     *       all-null (or empty) set is null, {@code SUM} of it is 0.0;</li>
     *   <li>{@code MIN} / {@code MAX} skip null values and are null when no
     *       non-null value exists.</li>
     * </ul>
     */
    private Map<String, Object> computeAggregations(DatasetSchema schema,
                                                    List<StoredRecord> matched,
                                                    List<QueryRequest.Aggregation> aggregations) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (aggregations == null) {
            return result;
        }
        for (QueryRequest.Aggregation agg : aggregations) {
            String alias = agg.alias() != null ? agg.alias()
                    : agg.func().name().toLowerCase()
                    + (agg.field() == null ? "" : "_" + agg.field());
            result.put(alias, compute(schema, matched, agg));
        }
        return result;
    }

    private Object compute(DatasetSchema schema, List<StoredRecord> matched, QueryRequest.Aggregation agg) {
        return switch (agg.func()) {
            case COUNT -> (long) matched.size();
            case SUM -> matched.stream()
                    .map(r -> r.values().get(agg.field()))
                    .filter(Number.class::isInstance)
                    .mapToDouble(v -> ((Number) v).doubleValue())
                    .sum();
            case AVG -> {
                var stats = matched.stream()
                        .map(r -> r.values().get(agg.field()))
                        .filter(Number.class::isInstance)
                        .mapToDouble(v -> ((Number) v).doubleValue())
                        .average();
                yield stats.isPresent() ? stats.getAsDouble() : null;
            }
            case MIN -> extremum(matched, agg.field(), true);
            case MAX -> extremum(matched, agg.field(), false);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
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
            int cmp;
            if (best instanceof Number a && value instanceof Number b) {
                cmp = Double.compare(a.doubleValue(), b.doubleValue());
            } else if (best instanceof Comparable c && best.getClass().isInstance(value)) {
                cmp = c.compareTo(value);
            } else {
                continue;
            }
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
            // default: all declared fields, in schema order
            schema.fields().forEach(f -> row.put(f.name(), values.get(f.name())));
        } else {
            for (String column : columns) {
                row.put(column, values.get(column));
            }
        }
        return row;
    }
}
