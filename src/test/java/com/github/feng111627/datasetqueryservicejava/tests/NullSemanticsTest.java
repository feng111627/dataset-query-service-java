package com.github.feng111627.datasetqueryservicejava.tests;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.FieldType;
import com.github.feng111627.datasetqueryservicejava.model.QueryRequest;
import com.github.feng111627.datasetqueryservicejava.model.QueryResult;
import com.github.feng111627.datasetqueryservicejava.query.QueryEngine;
import com.github.feng111627.datasetqueryservicejava.schema.SchemaCompatibility;
import com.github.feng111627.datasetqueryservicejava.schema.SchemaValidator;
import com.github.feng111627.datasetqueryservicejava.service.DatasetService;
import com.github.feng111627.datasetqueryservicejava.store.DatasetStore;
import com.github.feng111627.datasetqueryservicejava.tests.support.BusinessCoverageLogger;
import com.github.feng111627.datasetqueryservicejava.tests.support.CoversScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@CoversScenario("null semantics: null-safe range filters, explicit IS_NULL/IS_NOT_NULL, uniform aggregation and sort rules for nulls")
@ExtendWith(BusinessCoverageLogger.class)
class NullSemanticsTest {

    private static final Logger log = LoggerFactory.getLogger(NullSemanticsTest.class);

    private static final DatasetSchema SCHEMA = new DatasetSchema("metrics", List.of(
            new FieldDef("id", FieldType.INTEGER, true),
            new FieldDef("value", FieldType.DOUBLE, false),
            new FieldDef("label", FieldType.STRING, false)
    ));

    private DatasetService newService() {
        return new DatasetService(new DatasetStore(), new SchemaValidator(),
                new SchemaCompatibility(), new QueryEngine());
    }

    /**
     * Writes 6 records: value/label present on some, explicitly null on some,
     * absent on others. Explicit null and absent are both "empty".
     */
    private DatasetService serviceWithData() {
        DatasetService service = newService();
        service.registerSchema(SCHEMA);
        List<Map<String, Object>> records = new ArrayList<>();
        records.add(Map.of("id", 1, "value", 10.0, "label", "a"));
        records.add(Map.of("id", 2, "value", 20.0));
        records.add(Map.of("id", 3));                              // value+label absent
        records.add(Map.of("id", 4, "value", 40.0, "label", "d"));
        records.add(Map.of("id", 5, "label", "e"));                // value absent
        records.add(Map.of("id", 6, "value", 60.0, "label", "f"));
        service.writeRecords("metrics", records);
        return service;
    }

    private QueryResult query(DatasetService service, QueryRequest req) {
        return service.query("metrics", req);
    }

    @Test
    @DisplayName("range comparisons skip records with null values instead of failing the whole query")
    void rangeComparisonsSkipNulls() {
        DatasetService service = serviceWithData();

        QueryResult result = query(service, new QueryRequest(
                List.of(new QueryRequest.Filter("value", QueryRequest.Op.GTE, 20.0)),
                List.of("id"), null, List.of(new QueryRequest.SortField("id", true)),
                null, null, null));
        assertEquals(List.of(2, 4, 6),
                result.rows().stream().map(r -> ((Number) r.get("id")).intValue()).toList(),
                "records 3 and 5 have no value: skipped, not an error");

        QueryResult lt = query(service, new QueryRequest(
                List.of(new QueryRequest.Filter("value", QueryRequest.Op.LT, 50.0)),
                List.of("id"), null, List.of(new QueryRequest.SortField("id", true)),
                null, null, null));
        assertEquals(List.of(1, 2, 4),
                lt.rows().stream().map(r -> ((Number) r.get("id")).intValue()).toList());
        log.info("[COVERAGE] range filters over nulls: GTE 20 -> [2,4,6], LT 50 -> [1,2,4]; no query failure");
    }

    @Test
    @DisplayName("IS_NULL / IS_NOT_NULL select records by emptiness explicitly")
    void explicitNullPredicates() {
        DatasetService service = serviceWithData();

        QueryResult nulls = query(service, new QueryRequest(
                List.of(new QueryRequest.Filter("value", QueryRequest.Op.IS_NULL, null)),
                List.of("id"), null, List.of(new QueryRequest.SortField("id", true)),
                null, null, null));
        assertEquals(List.of(3, 5),
                nulls.rows().stream().map(r -> ((Number) r.get("id")).intValue()).toList());

        QueryResult nonNulls = query(service, new QueryRequest(
                List.of(new QueryRequest.Filter("value", QueryRequest.Op.IS_NOT_NULL, null)),
                List.of("id"), null, List.of(new QueryRequest.SortField("id", true)),
                null, null, null));
        assertEquals(List.of(1, 2, 4, 6),
                nonNulls.rows().stream().map(r -> ((Number) r.get("id")).intValue()).toList());

        // EQ null behaves like IS_NULL; NE null like IS_NOT_NULL
        QueryResult eqNull = query(service, new QueryRequest(
                List.of(new QueryRequest.Filter("label", QueryRequest.Op.EQ, null)),
                List.of("id"), null, List.of(new QueryRequest.SortField("id", true)),
                null, null, null));
        assertEquals(List.of(2, 3),
                eqNull.rows().stream().map(r -> ((Number) r.get("id")).intValue()).toList());
        QueryResult neNull = query(service, new QueryRequest(
                List.of(new QueryRequest.Filter("label", QueryRequest.Op.NE, null)),
                List.of("id"), null, List.of(new QueryRequest.SortField("id", true)),
                null, null, null));
        assertEquals(List.of(1, 4, 5, 6),
                neNull.rows().stream().map(r -> ((Number) r.get("id")).intValue()).toList());
    }

    @Test
    @DisplayName("a range filter with a null comparison value is a caller error (400), not a query-time failure")
    void rangeFilterWithNullValueIsRejected() {
        DatasetService service = serviceWithData();
        ClientException ex = assertThrows(ClientException.class, () -> query(service, new QueryRequest(
                List.of(new QueryRequest.Filter("value", QueryRequest.Op.GT, null)),
                null, null, null, null, null, null)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.code());
    }

    @Test
    @DisplayName("aggregations use uniform null rules: COUNT counts records, SUM/AVG/MIN/MAX skip nulls")
    void aggregationNullRules() {
        DatasetService service = serviceWithData();

        QueryResult result = query(service, new QueryRequest(null, null,
                List.of(new QueryRequest.Aggregation(QueryRequest.AggFunc.COUNT, null, "n"),
                        new QueryRequest.Aggregation(QueryRequest.AggFunc.SUM, "value", "sum"),
                        new QueryRequest.Aggregation(QueryRequest.AggFunc.AVG, "value", "avg"),
                        new QueryRequest.Aggregation(QueryRequest.AggFunc.MIN, "value", "min"),
                        new QueryRequest.Aggregation(QueryRequest.AggFunc.MAX, "value", "max")),
                null, null, null, null));
        Map<String, Object> aggs = result.aggregations();
        assertEquals(6L, ((Number) aggs.get("n")).longValue(), "COUNT counts records, nulls included");
        assertEquals(130.0, ((Number) aggs.get("sum")).doubleValue(), "SUM skips nulls");
        assertEquals(32.5, ((Number) aggs.get("avg")).doubleValue(), "AVG averages non-null values only");
        assertEquals(10.0, ((Number) aggs.get("min")).doubleValue());
        assertEquals(60.0, ((Number) aggs.get("max")).doubleValue());

        // aggregations over an all-null set: AVG/MIN/MAX are null, SUM is 0, COUNT still counts
        QueryResult empty = query(service, new QueryRequest(
                List.of(new QueryRequest.Filter("value", QueryRequest.Op.IS_NULL, null)),
                null,
                List.of(new QueryRequest.Aggregation(QueryRequest.AggFunc.COUNT, null, "n"),
                        new QueryRequest.Aggregation(QueryRequest.AggFunc.SUM, "value", "sum"),
                        new QueryRequest.Aggregation(QueryRequest.AggFunc.AVG, "value", "avg"),
                        new QueryRequest.Aggregation(QueryRequest.AggFunc.MIN, "value", "min"),
                        new QueryRequest.Aggregation(QueryRequest.AggFunc.MAX, "value", "max")),
                null, null, null, null));
        Map<String, Object> emptyAggs = empty.aggregations();
        assertEquals(2L, ((Number) emptyAggs.get("n")).longValue());
        assertEquals(0.0, ((Number) emptyAggs.get("sum")).doubleValue());
        assertNull(emptyAggs.get("avg"), "AVG of an all-null set is null");
        assertNull(emptyAggs.get("min"), "MIN of an all-null set is null");
        assertNull(emptyAggs.get("max"), "MAX of an all-null set is null");
        log.info("[COVERAGE] aggregation null rules verified on full and all-null sets: {}", aggs);
    }

    @Test
    @DisplayName("nulls sort last in both directions, combined with filter, aggregation and pagination")
    void nullsSortLastAndComposeWithPaging() {
        DatasetService service = serviceWithData();

        for (boolean ascending : new boolean[]{true, false}) {
            QueryResult result = query(service, new QueryRequest(null, List.of("id", "value"), null,
                    List.of(new QueryRequest.SortField("value", ascending)), 0, 100, null));
            List<Integer> ids = result.rows().stream()
                    .map(r -> ((Number) r.get("id")).intValue()).toList();
            List<Integer> expectedNullTail = List.of(3, 5); // insertion order among nulls
            assertEquals(expectedNullTail, ids.subList(ids.size() - 2, ids.size()),
                    "nulls must sort last (ascending=" + ascending + "): " + ids);
            List<Integer> nonNullHead = ids.subList(0, ids.size() - 2);
            assertEquals(ascending ? List.of(1, 2, 4, 6) : List.of(6, 4, 2, 1), nonNullHead);
        }

        // filter + aggregation + sort + pagination in one request stay consistent
        QueryResult paged = query(service, new QueryRequest(
                List.of(new QueryRequest.Filter("value", QueryRequest.Op.IS_NOT_NULL, null)),
                List.of("id"),
                List.of(new QueryRequest.Aggregation(QueryRequest.AggFunc.SUM, "value", "sum")),
                List.of(new QueryRequest.SortField("value", true)), 1, 2, null));
        assertEquals(4, paged.total(), "total reflects the full filtered set, not the page");
        assertEquals(List.of(2, 4),
                paged.rows().stream().map(r -> ((Number) r.get("id")).intValue()).toList());
        assertEquals(130.0, ((Number) paged.aggregations().get("sum")).doubleValue(),
                "aggregation covers the full filtered set, not just the page");
    }

    @Test
    @DisplayName("null semantics are stable under concurrent writes within one snapshot")
    void nullSemanticsStableUnderConcurrentWrites() throws Exception {
        DatasetService service = serviceWithData();

        QueryResult firstPage = query(service, new QueryRequest(
                List.of(new QueryRequest.Filter("value", QueryRequest.Op.IS_NULL, null)),
                List.of("id"), null, null, 0, 1, null));
        long token = firstPage.snapshotSeq();
        assertEquals(2, firstPage.total());

        // concurrent writer adds more null-valued records after the snapshot
        service.writeRecords("metrics", List.of(Map.of("id", 7), Map.of("id", 8)));

        QueryResult secondPage = query(service, new QueryRequest(
                List.of(new QueryRequest.Filter("value", QueryRequest.Op.IS_NULL, null)),
                List.of("id"), null, null, 1, 1, token));
        assertEquals(2, secondPage.total(), "same snapshot: totals and null matches stay stable");
        assertEquals(5, ((Number) secondPage.rows().get(0).get("id")).intValue());

        // a fresh snapshot sees the new records with the same null rules
        QueryResult fresh = query(service, new QueryRequest(
                List.of(new QueryRequest.Filter("value", QueryRequest.Op.IS_NULL, null)),
                List.of("id"), null, null, 0, 10, null));
        assertEquals(4, fresh.total());
    }
}
