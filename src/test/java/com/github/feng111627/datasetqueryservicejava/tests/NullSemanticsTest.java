package com.github.feng111627.datasetqueryservicejava.tests;

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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@CoversScenario("null semantics: explicit null and absent are equal; range predicates skip nulls without failing; IS NULL/IS NOT NULL; COUNT vs SUM/AVG/MIN/MAX; consistent across filter+sort+agg+pagination and concurrent writes")
@ExtendWith(BusinessCoverageLogger.class)
class NullSemanticsTest {

    private static final Logger log = LoggerFactory.getLogger(NullSemanticsTest.class);

    private static final DatasetSchema SCHEMA = new DatasetSchema("events", List.of(
            new FieldDef("id", FieldType.INTEGER, true),
            new FieldDef("score", FieldType.DOUBLE, false),
            new FieldDef("tag", FieldType.STRING, false)
    ));

    private DatasetService newService() {
        return new DatasetService(new DatasetStore(), new SchemaValidator(),
                new SchemaCompatibility(), new QueryEngine());
    }

    private static Map<String, Object> row(int id, Double score, String tag) {
        // LinkedHashMap so we can express an explicit null tag distinctly from absence
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        if (score != null) {
            m.put("score", score);
        }
        if (tag != null || id % 3 == 0) {
            m.put("tag", tag);
        }
        return m;
    }

    @Test
    @DisplayName("range comparison with a null record value never fails the query and simply does not match")
    void rangeComparisonsSkipNulls() {
        DatasetService service = newService();
        service.registerSchema(SCHEMA).schema();
        // id 1: no score key; id 2: explicit null; id 3/4: values
        service.writeRecords("events", List.of(
                row(1, null, "a"),       // absent score
                explicitNullScore(2),    // explicit null score
                Map.of("id", 3, "score", 5.0),
                Map.of("id", 4, "score", 10.0)));

        for (QueryRequest.Op op : List.of(QueryRequest.Op.GT, QueryRequest.Op.GTE,
                QueryRequest.Op.LT, QueryRequest.Op.LTE)) {
            QueryResult result = assertDoesNotThrow(() -> service.query("events",
                    new QueryRequest(List.of(new QueryRequest.Filter("score", op, 5.0)),
                            List.of("id"), null, null, 0, 100, null)),
                    "null values must not make range queries fail: " + op);
            List<Object> ids = result.rows().stream().map(r -> r.get("id")).toList();
            assertFalse(ids.contains(1), "absent value must not match " + op);
            assertFalse(ids.contains(2), "explicit null must not match " + op);
            // non-null rows that satisfy the predicate must still come back
            for (Object id : ids) {
                assertTrue(id.equals(3) || id.equals(4), "only valued rows may match " + op);
            }
            switch (op) {
                case GT -> assertEquals(List.of(4), ids);
                case GTE -> assertEquals(List.of(3, 4), ids);
                case LT -> assertEquals(List.of(), ids);
                case LTE -> assertEquals(List.of(3), ids);
                default -> { }
            }
        }
        log.info("[COVERAGE] range predicates over absent/explicit-null scores matched only valued rows, query never failed");
    }

    private static Map<String, Object> explicitNullScore(int id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("score", null);
        return m;
    }

    @Test
    @DisplayName("IS NULL matches absent and explicit null identically; IS NOT NULL matches only values; EQ/NE null aliases them")
    void nullTestsDistinguishNullFromValues() {
        DatasetService service = newService();
        service.registerSchema(SCHEMA).schema();
        service.writeRecords("events", List.of(
                Map.of("id", 1, "score", 1.0),
                row(2, null, null),            // absent
                explicitNullScore(3)));        // explicit null

        QueryResult isNull = service.query("events",
                new QueryRequest(List.of(new QueryRequest.Filter("score", QueryRequest.Op.IS_NULL, null)),
                        List.of("id"), null, null, 0, 100, null));
        assertEquals(List.of(2, 3), isNull.rows().stream().map(r -> r.get("id")).sorted().toList());

        QueryResult isNotNull = service.query("events",
                new QueryRequest(List.of(new QueryRequest.Filter("score", QueryRequest.Op.IS_NOT_NULL, null)),
                        List.of("id"), null, null, 0, 100, null));
        assertEquals(List.of(1), isNotNull.rows().stream().map(r -> r.get("id")).toList());

        QueryResult eqNull = service.query("events",
                new QueryRequest(List.of(new QueryRequest.Filter("score", QueryRequest.Op.EQ, null)),
                        List.of("id"), null, null, 0, 100, null));
        assertEquals(isNull.total(), eqNull.total(), "EQ null behaves as IS NULL");

        QueryResult neNull = service.query("events",
                new QueryRequest(List.of(new QueryRequest.Filter("score", QueryRequest.Op.NE, null)),
                        List.of("id"), null, null, 0, 100, null));
        assertEquals(isNotNull.total(), neNull.total(), "NE null behaves as IS NOT NULL");
    }

    @Test
    @DisplayName("aggregation null policy: COUNT counts all; SUM/AVG/MIN/MAX skip nulls and are null over only-nulls")
    void aggregationNullPolicy() {
        DatasetService service = newService();
        service.registerSchema(SCHEMA).schema();
        service.writeRecords("events", List.of(
                row(2, null, null), explicitNullScore(3),
                Map.of("id", 4, "score", 4.0),
                Map.of("id", 5, "score", 8.0)));

        QueryResult agg = service.query("events", new QueryRequest(null, null, List.of(
                new QueryRequest.Aggregation(QueryRequest.AggFunc.COUNT, null, "cnt"),
                new QueryRequest.Aggregation(QueryRequest.AggFunc.SUM, "score", "sum"),
                new QueryRequest.Aggregation(QueryRequest.AggFunc.AVG, "score", "avg"),
                new QueryRequest.Aggregation(QueryRequest.AggFunc.MIN, "score", "min"),
                new QueryRequest.Aggregation(QueryRequest.AggFunc.MAX, "score", "max"),
                new QueryRequest.Aggregation(QueryRequest.AggFunc.COUNT, "score", "cnt_score")),
                null, 0, 1, null));
        Map<String, Object> a = agg.aggregations();
        assertEquals(4L, a.get("cnt"), "COUNT(*) counts every matched row including nulls");
        assertEquals(4L, a.get("cnt_score"), "COUNT(field) in this service also counts rows (documented policy)");
        assertEquals(12.0, ((Number) a.get("sum")).doubleValue());
        assertEquals(6.0, ((Number) a.get("avg")).doubleValue());
        assertEquals(4.0, ((Number) a.get("min")).doubleValue());
        assertEquals(8.0, ((Number) a.get("max")).doubleValue());

        // all-null field set -> SUM/AVG/MIN/MAX null, COUNT still counts
        DatasetService empty2 = newService();
        empty2.registerSchema(SCHEMA).schema();
        empty2.writeRecords("events", List.of(row(2, null, null), explicitNullScore(3)));
        QueryResult allNull = empty2.query("events", new QueryRequest(null, null, List.of(
                new QueryRequest.Aggregation(QueryRequest.AggFunc.COUNT, null, "cnt"),
                new QueryRequest.Aggregation(QueryRequest.AggFunc.SUM, "score", "sum"),
                new QueryRequest.Aggregation(QueryRequest.AggFunc.AVG, "score", "avg"),
                new QueryRequest.Aggregation(QueryRequest.AggFunc.MIN, "score", "min"),
                new QueryRequest.Aggregation(QueryRequest.AggFunc.MAX, "score", "max")),
                null, 0, 1, null));
        Map<String, Object> b = allNull.aggregations();
        assertEquals(2L, b.get("cnt"));
        assertNull(b.get("sum"));
        assertNull(b.get("avg"));
        assertNull(b.get("min"));
        assertNull(b.get("max"));
    }

    @Test
    @DisplayName("nulls sort last in both directions and stay consistent with filters, aggs and pagination")
    void nullsConsistentAcrossSortFilterPagination() {
        DatasetService service = newService();
        service.registerSchema(SCHEMA).schema();
        service.writeRecords("events", List.of(
                Map.of("id", 1, "score", 3.0),
                explicitNullScore(2),
                Map.of("id", 3, "score", 1.0),
                row(4, null, null),
                Map.of("id", 5, "score", 2.0)));

        QueryRequest asc = new QueryRequest(null, List.of("id", "score"), null,
                List.of(new QueryRequest.SortField("score", true)), 0, 5, null);
        QueryResult r1 = service.query("events", asc);
        List<Object> idsAsc = r1.rows().stream().map(r -> r.get("id")).toList();
        assertEquals(List.of(3, 5, 1, 2, 4), idsAsc, "ascending: values first, nulls (2,4) last in seq order");

        QueryRequest desc = new QueryRequest(null, List.of("id", "score"), null,
                List.of(new QueryRequest.SortField("score", false)), 0, 5, null);
        QueryResult r2 = service.query("events", desc);
        List<Object> idsDesc = r2.rows().stream().map(r -> r.get("id")).toList();
        assertEquals(List.of(1, 5, 3, 2, 4), idsDesc, "descending: values reversed, nulls still last");

        // combined: filter score > 0 + sort + aggregate + pages stay coherent
        QueryRequest page1 = new QueryRequest(
                List.of(new QueryRequest.Filter("score", QueryRequest.Op.GT, 0.0)),
                List.of("id"),
                List.of(new QueryRequest.Aggregation(QueryRequest.AggFunc.COUNT, null, "cnt"),
                        new QueryRequest.Aggregation(QueryRequest.AggFunc.SUM, "score", "sum")),
                List.of(new QueryRequest.SortField("score", true)), 0, 2, null);
        QueryResult p1 = service.query("events", page1);
        assertEquals(3, p1.total(), "nulls excluded by the range filter");
        assertEquals(List.of(3, 5), p1.rows().stream().map(r -> r.get("id")).toList());
        assertEquals(3L, p1.aggregations().get("cnt"));
        assertEquals(6.0, ((Number) p1.aggregations().get("sum")).doubleValue());

        QueryRequest page2 = new QueryRequest(
                List.of(new QueryRequest.Filter("score", QueryRequest.Op.GT, 0.0)),
                List.of("id"), null,
                List.of(new QueryRequest.SortField("score", true)), 2, 2, p1.snapshotSeq());
        QueryResult p2 = service.query("events", page2);
        assertEquals(List.of(1), p2.rows().stream().map(r -> r.get("id")).toList());
        assertEquals(3, p2.total());
    }

    @Test
    @DisplayName("null semantics hold under concurrent writes when re-reading a pinned snapshot")
    void nullsStableUnderConcurrentWrites() throws Exception {
        DatasetService service = newService();
        service.registerSchema(SCHEMA).schema();
        service.writeRecords("events", List.of(
                Map.of("id", 1, "score", 1.0), explicitNullScore(2)));

        QueryResult first = service.query("events",
                new QueryRequest(null, List.of("id"), null, null, 0, 10, null));
        long token = first.snapshotSeq();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        for (int w = 0; w < 3; w++) {
            final int base = w;
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < 50; i++) {
                    int id = 100 + base * 50 + i;
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", id);
                    if (i % 2 == 0) {
                        r.put("score", (double) i);
                    } // else absent
                    service.writeRecords("events", List.of(r));
                }
            });
        }
        start.countDown();

        // hammer the pinned token: IS NULL count over the snapshot must never move
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<Thread> readers = new ArrayList<>();
        AtomicReference<Long> nullCount = new AtomicReference<>();
        for (int r = 0; r < 4; r++) {
            Thread t = new Thread(() -> {
                try {
                    while (!pool.isTerminated()) {
                        QueryResult q = service.query("events", new QueryRequest(
                                List.of(new QueryRequest.Filter("score", QueryRequest.Op.IS_NULL, null)),
                                null, List.of(new QueryRequest.Aggregation(QueryRequest.AggFunc.COUNT, null, "c")),
                                null, 0, 1, token));
                        long c = (Long) q.aggregations().get("c");
                        Long prev = nullCount.getAndSet(c);
                        if (prev != null && prev != c) {
                            throw new AssertionError("snapshot IS NULL count changed: " + prev + " -> " + c);
                        }
                        assertEquals(1L, c, "pinned snapshot must keep seeing exactly the one null row");
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            });
            t.start();
            readers.add(t);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        for (Thread t : readers) {
            t.join(5000);
        }
        assertNull(error.get(), "concurrent readers saw inconsistent null results: " + error.get());

        // fresh snapshot sees all the new rows; null policy unchanged
        QueryResult fresh = service.query("events", new QueryRequest(
                List.of(new QueryRequest.Filter("score", QueryRequest.Op.IS_NULL, null)),
                null, null, null, 0, 1, null));
        assertEquals(76, fresh.total(), "1 original null + 75 absent-score concurrent rows");
        log.info("[COVERAGE] pinned snapshot kept IS_NULL=1 under 150 concurrent writes; fresh snapshot sees {} nulls",
                fresh.total());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
