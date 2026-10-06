package com.github.feng111627.datasetqueryservicejava.query;

import com.github.feng111627.datasetqueryservicejava.error.ApiException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetRecord;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.FieldType;
import com.github.feng111627.datasetqueryservicejava.storage.DatasetStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 查询引擎：条件过滤、选列、聚合、稳定排序、分页与无效查询拒绝。 */
class QueryEngineTest {

    private DatasetStore store;
    private QueryEngine engine;
    private static final String DS = "nums";

    private static final DatasetSchema SCHEMA = new DatasetSchema(List.of(
            new FieldDef("id", FieldType.INTEGER, true),
            new FieldDef("grp", FieldType.STRING, true),
            new FieldDef("score", FieldType.NUMBER, false),
            new FieldDef("active", FieldType.BOOLEAN, true)
    ));

    @BeforeEach
    void setUp() {
        store = new DatasetStore();
        engine = new QueryEngine(store);
        store.register(DS, SCHEMA, true);
        store.append(DS, List.of(
                rec(1, "a", 10.0, true),
                rec(2, "b", 20.0, false),
                rec(3, "a", 30.0, true),
                rec(4, "b", null, true),
                rec(5, "a", 10.0, false)
        ));
    }

    private static DatasetRecord rec(int id, String grp, Double score, boolean active) {
        LinkedHashMap<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("grp", grp);
        v.put("score", score);
        v.put("active", active);
        return DatasetRecord.input(v);
    }

    private QueryRequest request() {
        QueryRequest q = new QueryRequest();
        q.setLimit(100);
        return q;
    }

    @Test
    @DisplayName("无过滤查询：按首个声明字段(id)升序，顺序稳定可复现")
    void defaultOrderingIsStable() {
        QueryResult r1 = engine.execute("nums", request());
        QueryResult r2 = engine.execute("nums", request());
        assertThat(r1.rows()).extracting(row -> row.get("id")).containsExactly(1, 2, 3, 4, 5);
        assertThat(r1.rows()).isEqualTo(r2.rows());
        assertThat(r1.totalMatched()).isEqualTo(5);
    }

    @Test
    @DisplayName("等值过滤：active=false 只命中 id 2,5")
    void filterEqBoolean() {
        QueryRequest q = request();
        q.setFilters(List.of(new Filter("active", Operator.EQ, false)));
        QueryResult result = engine.execute("nums", q);
        assertThat(result.rows()).extracting(row -> row.get("id")).containsExactly(2, 5);
    }

    @Test
    @DisplayName("数值比较过滤：score >= 20 命中 id 2,3（null 不参与大小比较）")
    void filterGteNumber() {
        QueryRequest q = request();
        q.setFilters(List.of(new Filter("score", Operator.GTE, 20)));
        QueryResult result = engine.execute("nums", q);
        assertThat(result.rows()).extracting(row -> row.get("id")).containsExactly(2, 3);
    }

    @Test
    @DisplayName("字符串等值 + 布尔过滤可组合")
    void combinedFilters() {
        QueryRequest q = request();
        q.setFilters(List.of(
                new Filter("grp", Operator.EQ, "a"),
                new Filter("active", Operator.EQ, true)));
        QueryResult result = engine.execute("nums", q);
        assertThat(result.rows()).extracting(row -> row.get("id")).containsExactly(1, 3);
    }

    @Test
    @DisplayName("选列：只返回指定字段")
    void selectColumns() {
        QueryRequest q = request();
        q.setSelect(List.of("id", "grp"));
        QueryResult result = engine.execute("nums", q);
        assertThat(result.rows().get(0)).containsOnlyKeys("id", "grp");
    }

    @Test
    @DisplayName("分页：limit/offset 切片，hasMore 正确")
    void paging() {
        QueryRequest q = request();
        q.setLimit(2);
        q.setOffset(2);
        QueryResult result = engine.execute("nums", q);
        assertThat(result.rows()).extracting(row -> row.get("id")).containsExactly(3, 4);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.limit()).isEqualTo(2);
        assertThat(result.offset()).isEqualTo(2);
    }

    @Test
    @DisplayName("钉住 watermark：遍历期间追加数据，钉旧水位的查询不变，新查询看到新数据")
    void pinnedWatermarkIsolation() {
        QueryRequest q = request();
        QueryResult first = engine.execute("nums", q);
        assertThat(first.watermark()).isEqualTo(5);

        QueryRequest paged = request();
        paged.setWatermark(first.watermark());
        QueryResult pinned = engine.execute("nums", paged);
        assertThat(pinned.rows()).hasSize(5);

        store.append("nums", List.of(rec(6, "a", 99.0, true)));
        QueryResult stillPinned = engine.execute("nums", paged);
        assertThat(stillPinned.rows()).as("钉住旧水位时不应看到新写入").hasSize(5);
        assertThat(stillPinned.watermark()).isEqualTo(5);

        QueryResult fresh = engine.execute("nums", request());
        assertThat(fresh.rows()).hasSize(6);
        assertThat(fresh.watermark()).isEqualTo(6);
    }

    @Test
    @DisplayName("分组聚合：COUNT/SUM/AVG/MIN/MAX，null 值不参与 SUM/AVG/MIN/MAX")
    void groupedAggregation() {
        QueryRequest q = request();
        q.setGroupBy(List.of("grp"));
        q.setAggregations(List.of(
                new Aggregation(AggregationOp.COUNT, "score", "cnt"),
                new Aggregation(AggregationOp.SUM, "score", "total"),
                new Aggregation(AggregationOp.AVG, "score", "avg_score"),
                new Aggregation(AggregationOp.MIN, "score", "min_score"),
                new Aggregation(AggregationOp.MAX, "score", "max_score")
        ));
        QueryResult result = engine.execute("nums", q);
        assertThat(result.aggregated()).isTrue();
        assertThat(result.rows()).hasSize(2);
        Map<String, Object> groupA = result.rows().get(0);
        assertThat(groupA.get("grp")).isEqualTo("a");
        assertThat(groupA.get("cnt")).isEqualTo(3L);
        assertThat(groupA.get("total")).isEqualTo(50.0);
        assertThat(groupA.get("avg_score")).isEqualTo(50.0 / 3);
        assertThat(groupA.get("min_score")).isEqualTo(10.0);
        assertThat(groupA.get("max_score")).isEqualTo(30.0);

        Map<String, Object> groupB = result.rows().get(1);
        assertThat(groupB.get("grp")).isEqualTo("b");
        assertThat(groupB.get("cnt")).isEqualTo(1L); // score 为 null 的记录不计入
        assertThat(groupB.get("total")).isEqualTo(20.0);
        assertThat(groupB.get("min_score")).isEqualTo(20.0);
        assertThat(groupB.get("max_score")).isEqualTo(20.0);
    }

    @Test
    @DisplayName("COUNT(*) 与无 groupBy 的全局聚合返回单行")
    void globalCount() {
        QueryRequest q = request();
        q.setAggregations(List.of(new Aggregation(AggregationOp.COUNT, null, "total")));
        QueryResult result = engine.execute("nums", q);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).get("total")).isEqualTo(5L);
    }

    @Test
    @DisplayName("非法查询：不存在的过滤字段 -> 400 INVALID_QUERY")
    void invalidFilterField() {
        QueryRequest q = request();
        q.setFilters(List.of(new Filter("nope", Operator.EQ, 1)));
        assertThatThrownBy(() -> engine.execute("nums", q))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(400);
                    assertThat(api.code()).isEqualTo(ErrorCode.INVALID_QUERY);
                });
    }

    @Test
    @DisplayName("非法查询：比较值类型不符 / limit 非法 / 对字符串求和 / 只分组不聚合 全部拒绝")
    void otherInvalidQueries() {
        QueryRequest badType = request();
        badType.setFilters(List.of(new Filter("id", Operator.EQ, "not-int")));
        assertThatThrownBy(() -> engine.execute("nums", badType)).isInstanceOf(ApiException.class);

        QueryRequest badLimit = request();
        badLimit.setLimit(0);
        assertThatThrownBy(() -> engine.execute("nums", badLimit)).isInstanceOf(ApiException.class);

        QueryRequest sumString = request();
        sumString.setAggregations(List.of(new Aggregation(AggregationOp.SUM, "grp", "s")));
        assertThatThrownBy(() -> engine.execute("nums", sumString)).isInstanceOf(ApiException.class);

        QueryRequest groupOnly = request();
        groupOnly.setGroupBy(List.of("grp"));
        assertThatThrownBy(() -> engine.execute("nums", groupOnly)).isInstanceOf(ApiException.class);
    }
}
