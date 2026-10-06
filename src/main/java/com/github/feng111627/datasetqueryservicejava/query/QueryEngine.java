package com.github.feng111627.datasetqueryservicejava.query;

import com.github.feng111627.datasetqueryservicejava.error.ApiException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetRecord;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.FieldType;
import com.github.feng111627.datasetqueryservicejava.storage.DatasetStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 查询执行器：快照 -> 过滤 -> 聚合或选列 -> 稳定排序 -> 分页。
 *
 * <p>稳定性保证：先在固定水位快照上完成过滤，再以“排序键 + 记录 id”排序，
 * 因此同一份数据的查询结果顺序确定、可复现；续翻页回传 watermark 后，
 * 即使期间有并发写入，各页之间也不会重复或丢失（读到的始终是同一版本）。
 */
@Component
public class QueryEngine {

    /** 允许的最大页大小，防止一次拉全量。 */
    public static final int MAX_LIMIT = 1000;

    private final DatasetStore store;

    public QueryEngine(DatasetStore store) {
        this.store = store;
    }

    public QueryResult execute(String dataset, QueryRequest request) {
        DatasetSchema schema = store.schemaOf(dataset)
                .orElseThrow(() -> new ApiException(404, ErrorCode.DATASET_NOT_FOUND,
                        "数据集 '" + dataset + "' 不存在，请先注册"));
        long requestedWatermark = request.getWatermark() == null ? 0 : request.getWatermark();
        long pinned = requestedWatermark > 0
                ? Math.min(requestedWatermark, store.currentWatermark(dataset))
                : store.currentWatermark(dataset);
        List<DatasetRecord> records = store.snapshot(dataset, 1, pinned);
        return evaluate(schema, records, request, pinned);
    }

    /** 在给定记录集上执行查询（测试与内部复用）。 */
    public QueryResult evaluate(DatasetSchema schema, List<DatasetRecord> records, QueryRequest request) {
        return evaluate(schema, records, request, records.size());
    }

    private QueryResult evaluate(DatasetSchema schema, List<DatasetRecord> records,
                                 QueryRequest request, long watermark) {
        validatePaging(request);
        validateReferences(schema, request);

        // 1) 过滤
        List<DatasetRecord> matched = new ArrayList<>();
        for (DatasetRecord record : records) {
            if (matchesAll(record, request.getFilters())) {
                matched.add(record);
            }
        }

        boolean aggregated = !request.getAggregations().isEmpty() || !request.getGroupBy().isEmpty();
        List<Map<String, Object>> rows;
        if (aggregated) {
            rows = aggregate(schema, matched, request);
        } else {
            // 2a) 非聚合：稳定排序后选列、分页
            matched.sort(recordComparator(schema));
            rows = project(matched, request.getSelect());
            rows = paginateRows(rows, request);
            return new QueryResult(watermark, matched.size(), request.getLimit(), request.getOffset(),
                    request.getOffset() + rows.size() < matched.size(), false, rows);
        }
        return new QueryResult(watermark, rows.size(), request.getLimit(), request.getOffset(),
                false, true, rows);
    }

    // ---------------- 聚合 ----------------

    private List<Map<String, Object>> aggregate(DatasetSchema schema,
                                                List<DatasetRecord> matched, QueryRequest request) {
        // 分组：保持 groupBy 声明顺序构造 key；最终分组结果按 key 排序保证稳定
        Map<List<Object>, List<DatasetRecord>> groups = new LinkedHashMap<>();
        for (DatasetRecord record : matched) {
            List<Object> key = new ArrayList<>(request.getGroupBy().size());
            for (String field : request.getGroupBy()) {
                key.add(record.values().get(field));
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<List<Object>, List<DatasetRecord>> entry : groups.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            List<Object> key = entry.getKey();
            for (int i = 0; i < request.getGroupBy().size(); i++) {
                row.put(request.getGroupBy().get(i), key.get(i));
            }
            for (Aggregation agg : request.getAggregations()) {
                row.put(agg.alias(), computeAgg(agg, entry.getValue()));
            }
            rows.add(row);
        }
        rows.sort(aggregatedRowComparator(request));
        return rows;
    }

    private Object computeAgg(Aggregation agg, List<DatasetRecord> groupRows) {
        return switch (agg.op()) {
            case COUNT -> agg.field() == null ? (long) groupRows.size()
                    : groupRows.stream().filter(r -> r.values().get(agg.field()) != null).count();
            case SUM -> sum(agg, groupRows);
            case AVG -> average(agg, groupRows);
            case MIN -> minMax(agg, groupRows, true);
            case MAX -> minMax(agg, groupRows, false);
        };
    }

    private Double sum(Aggregation agg, List<DatasetRecord> groupRows) {
        double sum = 0;
        for (DatasetRecord r : groupRows) {
            Object v = r.values().get(agg.field());
            if (v instanceof Number n) {
                sum += n.doubleValue();
            }
        }
        return sum;
    }

    private Double average(Aggregation agg, List<DatasetRecord> groupRows) {
        double sum = 0;
        long count = 0;
        for (DatasetRecord r : groupRows) {
            Object v = r.values().get(agg.field());
            if (v instanceof Number n) {
                sum += n.doubleValue();
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object minMax(Aggregation agg, List<DatasetRecord> groupRows, boolean min) {
        Comparable best = null;
        for (DatasetRecord r : groupRows) {
            Object v = r.values().get(agg.field());
            if (!(v instanceof Comparable<?> candidate)) {
                continue;
            }
            if (best == null) {
                best = (Comparable) candidate;
                continue;
            }
            int cmp = compareWithCoercion(candidate, best);
            boolean take = min ? cmp < 0 : cmp > 0;
            if (take) {
                best = (Comparable) candidate;
            }
        }
        return best;
    }

    // ---------------- 过滤 ----------------

    private boolean matchesAll(DatasetRecord record, List<Filter> filters) {
        for (Filter filter : filters) {
            Object actual = record.values().get(filter.field());
            if (!compare(filter, actual)) {
                return false;
            }
        }
        return true;
    }

    private boolean compare(Filter filter, Object actual) {
        Object expected = filter.value();
        // null 仅支持 EQ/NE；其他比较对 null 一律不匹配
        if (actual == null || expected == null) {
            return switch (filter.op()) {
                case EQ -> Objects.equals(actual, expected);
                case NE -> !Objects.equals(actual, expected);
                default -> false;
            };
        }
        int cmp = compareWithCoercion(actual, expected);
        if (cmp == Integer.MIN_VALUE) {
            // 跨类型（如字符串与数字）不可比较：除 NE 外都不匹配
            return filter.op() == Operator.NE;
        }
        return switch (filter.op()) {
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            case GT -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
        };
    }

    /**
     * 数值之间按数值大小比较（Integer 与 Double 也可比较）；
     * 同类型实现 Comparable 时直接比较；跨类型返回 MIN_VALUE 表示不可比较。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareWithCoercion(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a.getClass() == b.getClass() && a instanceof Comparable) {
            return ((Comparable) a).compareTo(b);
        }
        // 布尔之间
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return Boolean.compare(ba, bb);
        }
        return Integer.MIN_VALUE;
    }

    // ---------------- 排序 / 选列 / 分页 ----------------

    /**
     * 非聚合结果的稳定排序：按声明字段顺序里第一列升序作为默认键，
     * 并始终追加记录 id 作为最终决胜键，保证顺序全序、可复现。
     */
    private Comparator<DatasetRecord> recordComparator(DatasetSchema schema) {
        return (a, b) -> {
            String firstField = schema.fields().isEmpty() ? null : schema.fields().get(0).name();
            if (firstField != null) {
                int cmp = compareNullable(a.values().get(firstField), b.values().get(firstField));
                if (cmp != 0) {
                    return cmp;
                }
            }
            return Long.compare(idOf(a), idOf(b));
        };
    }

    private Comparator<Map<String, Object>> aggregatedRowComparator(QueryRequest request) {
        return (a, b) -> {
            for (String keyField : request.getGroupBy()) {
                int cmp = compareNullable(a.get(keyField), b.get(keyField));
                if (cmp != 0) {
                    return cmp;
                }
            }
            // 无 groupBy（全局聚合）时最多一行；不会走到这里
            return 0;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareNullable(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        int cmp = compareWithCoercion(a, b);
        if (cmp != Integer.MIN_VALUE) {
            return cmp;
        }
        return ((Comparable<Object>) a).compareTo(b);
    }

    private List<Map<String, Object>> project(List<DatasetRecord> records, List<String> select) {
        List<Map<String, Object>> rows = new ArrayList<>(records.size());
        for (DatasetRecord record : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (select == null || select.isEmpty()) {
                row.putAll(record.values());
            } else {
                for (String field : select) {
                    row.put(field, record.values().get(field));
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> paginateRows(List<Map<String, Object>> rows, QueryRequest request) {
        int from = Math.min(request.getOffset(), rows.size());
        int to = Math.min(from + request.getLimit(), rows.size());
        return new ArrayList<>(rows.subList(from, to));
    }

    // ---------------- 请求校验（调用方用错 -> INVALID_QUERY） ----------------

    private void validatePaging(QueryRequest request) {
        if (request.getLimit() < 1) {
            throw new ApiException(400, ErrorCode.INVALID_QUERY, "limit 必须为正整数");
        }
        if (request.getLimit() > MAX_LIMIT) {
            throw new ApiException(400, ErrorCode.INVALID_QUERY, "limit 最大为 " + MAX_LIMIT);
        }
        if (request.getOffset() < 0) {
            throw new ApiException(400, ErrorCode.INVALID_QUERY, "offset 不能为负");
        }
        if (request.getWatermark() != null && request.getWatermark() < 0) {
            throw new ApiException(400, ErrorCode.INVALID_QUERY, "watermark 不能为负");
        }
    }

    private void validateReferences(DatasetSchema schema, QueryRequest request) {
        Map<String, FieldDef> declared = new LinkedHashMap<>();
        for (FieldDef field : schema.fields()) {
            declared.put(field.name(), field);
        }
        for (Filter filter : request.getFilters()) {
            if (filter.field() == null || filter.op() == null) {
                throw new ApiException(400, ErrorCode.INVALID_QUERY, "过滤条件必须同时提供 field 和 op");
            }
            if (!declared.containsKey(filter.field())) {
                throw new ApiException(400, ErrorCode.INVALID_QUERY,
                        "过滤字段 '" + filter.field() + "' 不存在于 schema 中");
            }
            if (filter.value() != null
                    && !valueTypeMatches(declared.get(filter.field()).type(), filter.value())) {
                throw new ApiException(400, ErrorCode.INVALID_QUERY,
                        "过滤字段 '" + filter.field() + "' 的比较值类型与声明类型 "
                                + declared.get(filter.field()).type() + " 不符");
            }
        }
        if (request.getSelect() != null) {
            for (String field : request.getSelect()) {
                if (!declared.containsKey(field)) {
                    throw new ApiException(400, ErrorCode.INVALID_QUERY,
                            "选列字段 '" + field + "' 不存在于 schema 中");
                }
            }
        }
        for (String field : request.getGroupBy()) {
            if (!declared.containsKey(field)) {
                throw new ApiException(400, ErrorCode.INVALID_QUERY,
                        "分组字段 '" + field + "' 不存在于 schema 中");
            }
        }
        if (request.getAggregations().isEmpty() && !request.getGroupBy().isEmpty()) {
            throw new ApiException(400, ErrorCode.INVALID_QUERY, "使用 groupBy 时必须至少提供一个聚合");
        }
        TreeSet<String> aliases = new TreeSet<>();
        for (Aggregation agg : request.getAggregations()) {
            if (agg.op() == null) {
                throw new ApiException(400, ErrorCode.INVALID_QUERY, "聚合必须提供 op");
            }
            if (agg.op() != AggregationOp.COUNT || agg.field() != null) {
                if (agg.field() == null) {
                    throw new ApiException(400, ErrorCode.INVALID_QUERY,
                            agg.op() + " 聚合必须提供 field");
                }
                FieldDef fieldDef = declared.get(agg.field());
                if (fieldDef == null) {
                    throw new ApiException(400, ErrorCode.INVALID_QUERY,
                            "聚合字段 '" + agg.field() + "' 不存在于 schema 中");
                }
                if (agg.op() != AggregationOp.COUNT
                        && fieldDef.type() != FieldType.INTEGER && fieldDef.type() != FieldType.NUMBER) {
                    throw new ApiException(400, ErrorCode.INVALID_QUERY,
                            agg.op() + " 只能作用于数值字段，字段 '" + agg.field() + "' 类型为 "
                                    + fieldDef.type());
                }
            }
            if (agg.alias() == null || agg.alias().isBlank()) {
                throw new ApiException(400, ErrorCode.INVALID_QUERY, "聚合必须提供非空 alias");
            }
            if (!aliases.add(agg.alias())) {
                throw new ApiException(400, ErrorCode.INVALID_QUERY,
                        "聚合别名 '" + agg.alias() + "' 重复");
            }
            if (declared.containsKey(agg.alias())
                    && !request.getGroupBy().contains(agg.alias())) {
                throw new ApiException(400, ErrorCode.INVALID_QUERY,
                        "聚合别名 '" + agg.alias() + "' 与已有字段名冲突");
            }
        }
    }

    private boolean valueTypeMatches(FieldType type, Object value) {
        return switch (type) {
            case STRING -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
            case INTEGER -> value instanceof Integer || value instanceof Long;
            case NUMBER -> value instanceof Number;
        };
    }

    /** 记录 id：写入时由存储层分配的单调递增 id。 */
    private long idOf(DatasetRecord record) {
        return record.id();
    }
}
