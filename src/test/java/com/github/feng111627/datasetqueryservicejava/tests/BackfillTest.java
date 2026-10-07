package com.github.feng111627.datasetqueryservicejava.tests;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.BackfillRequest;
import com.github.feng111627.datasetqueryservicejava.model.BackfillResult;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@CoversScenario("backfill: atomic all-or-nothing fill of existing records, clear ordering vs concurrent writes, idempotent retry that never overwrites new values, explicit stale-snapshot behavior")
@ExtendWith(BusinessCoverageLogger.class)
class BackfillTest {

    private static final Logger log = LoggerFactory.getLogger(BackfillTest.class);

    private DatasetService newService() {
        return new DatasetService(new DatasetStore(), new SchemaValidator(),
                new SchemaCompatibility(), new QueryEngine());
    }

    private DatasetSchema evolvedSchema() {
        return new DatasetSchema("events", List.of(
                new FieldDef("id", FieldType.INTEGER, true),
                new FieldDef("price", FieldType.DOUBLE, true),
                new FieldDef("tier", FieldType.INTEGER, false)
        ));
    }

    private static List<Map<String, Object>> records(int from, int to) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            records.add(Map.of("id", i, "price", i * 1.0));
        }
        return records;
    }

    @Test
    @DisplayName("backfill fills exactly the existing records missing the field; post-backfill filters/aggs match the default-value world")
    void backfillFillsExistingRows() {
        DatasetService service = newService();
        service.registerSchema(new DatasetSchema("events", List.of(
                new FieldDef("id", FieldType.INTEGER, true),
                new FieldDef("price", FieldType.DOUBLE, true))));
        service.writeRecords("events", records(1, 10));
        service.registerSchema(evolvedSchema());

        BackfillResult result = service.backfill("events",
                new BackfillRequest("tier", 0, null));
        assertEquals(10, result.backfilled());
        assertTrue(result.seq() > 0);
        assertFalse(result.replayed());

        // every old row now carries the default; a row explicitly written with a value keeps it
        service.writeRecords("events", List.of(Map.of("id", 11, "price", 11.0, "tier", 7)));

        QueryResult zeros = service.query("events",
                new QueryRequest(List.of(new QueryRequest.Filter("tier", QueryRequest.Op.EQ, 0)),
                        null, null, null, 0, 100, null));
        assertEquals(10, zeros.total(), "filter must behave as if every historical record had the default");
        QueryResult sevens = service.query("events",
                new QueryRequest(List.of(new QueryRequest.Filter("tier", QueryRequest.Op.EQ, 7)),
                        null, null, null, 0, 100, null));
        assertEquals(1, sevens.total());

        QueryResult agg = service.query("events", new QueryRequest(null, null, List.of(
                new QueryRequest.Aggregation(QueryRequest.AggFunc.COUNT, null, "cnt"),
                new QueryRequest.Aggregation(QueryRequest.AggFunc.SUM, "tier", "sum_tier"),
                new QueryRequest.Aggregation(QueryRequest.AggFunc.AVG, "tier", "avg_tier"),
                new QueryRequest.Aggregation(QueryRequest.AggFunc.MIN, "tier", "min_tier"),
                new QueryRequest.Aggregation(QueryRequest.AggFunc.MAX, "tier", "max_tier")),
                null, 0, 1, null));
        Map<String, Object> a = agg.aggregations();
        assertEquals(11L, a.get("cnt"));
        assertEquals(7.0, ((Number) a.get("sum_tier")).doubleValue());
        assertEquals(7.0 / 11, ((Number) a.get("avg_tier")).doubleValue(), 1e-9);
        assertEquals(0, ((Number) a.get("min_tier")).intValue());
        assertEquals(7, ((Number) a.get("max_tier")).intValue());
        log.info("[COVERAGE] backfilled tier=0 onto 10 historical rows; filter EQ 0 -> {}, SUM tier -> {}",
                zeros.total(), a.get("sum_tier"));
    }

    @Test
    @DisplayName("backfill vs concurrent writes: reads are never half-filled; writes are ordered before or after")
    void backfillIsAtomicAgainstConcurrentWriters() throws Exception {
        DatasetService service = newService();
        service.registerSchema(new DatasetSchema("events", List.of(
                new FieldDef("id", FieldType.INTEGER, true),
                new FieldDef("price", FieldType.DOUBLE, true))));
        service.writeRecords("events", records(1, 50));
        service.registerSchema(evolvedSchema());

        ExecutorService writers = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger nextId = new AtomicInteger(51);
        AtomicBoolean stop = new AtomicBoolean(false);
        for (int w = 0; w < 4; w++) {
            writers.submit(() -> {
                await(start);
                while (!stop.get()) {
                    int id = nextId.getAndIncrement();
                    if (id > 300) {
                        return;
                    }
                    // alternating explicit tier vs absent tier
                    Map<String, Object> row = id % 2 == 0
                            ? Map.of("id", id, "price", id * 1.0, "tier", 9)
                            : Map.of("id", id, "price", id * 1.0);
                    service.writeRecords("events", List.of(row));
                }
            });
        }
        start.countDown();

        // backfill concurrently with the writers; it must commit at exactly one seq point
        BackfillResult result = service.backfill("events", new BackfillRequest("tier", 0, null));
        stop.set(true);
        writers.shutdown();
        assertTrue(writers.awaitTermination(10, TimeUnit.SECONDS));

        QueryResult all = service.query("events",
                new QueryRequest(null, null, null, null, 0, 1000, null));
        long total = all.total();

        // Invariant: every row either has tier (0 from backfill, 9 explicit or
        // absent-but-filled). Rows written strictly before the backfill without
        // tier must be 0; rows written after without tier stay null. Verify the
        // count identity: tier==0 count == records missing-at-commit == result.backfilled
        QueryResult zeros = service.query("events",
                new QueryRequest(List.of(new QueryRequest.Filter("tier", QueryRequest.Op.EQ, 0)),
                        null, null, null, 0, 1000, null));
        QueryResult nulls = service.query("events",
                new QueryRequest(List.of(new QueryRequest.Filter("tier", QueryRequest.Op.IS_NULL, null)),
                        null, null, null, 0, 1000, null));
        QueryResult nines = service.query("events",
                new QueryRequest(List.of(new QueryRequest.Filter("tier", QueryRequest.Op.EQ, 9)),
                        null, null, null, 0, 1000, null));
        assertEquals(result.backfilled(), zeros.total(),
                "every pre-backfill missing row is filled, no post-backfill row is");
        assertEquals(total, zeros.total() + nulls.total() + nines.total(),
                "every row must be in exactly one state — no half-filled view");
        log.info("[COVERAGE] concurrent backfill at seq {} filled {} rows; post-run states: {} zero, {} null, {} nine, total {}",
                result.seq(), result.backfilled(), zeros.total(), nulls.total(), nines.total(), total);
    }

    @Test
    @DisplayName("a failed/blocked backfill changes nothing and can be retried; keyed retry never double-fills or overwrites new values")
    void failedBackfillRetryableAndIdempotent() {
        DatasetService service = newService();
        service.registerSchema(new DatasetSchema("events", List.of(
                new FieldDef("id", FieldType.INTEGER, true),
                new FieldDef("price", FieldType.DOUBLE, true))));
        service.writeRecords("events", records(1, 5));

        // backfilling before the field exists -> rejected, data untouched
        ClientException unknown = assertThrows(ClientException.class,
                () -> service.backfill("events", new BackfillRequest("tier", 0, null)));
        assertEquals(ErrorCode.UNKNOWN_FIELD, unknown.code());

        // wrong type -> rejected, data untouched, retryable
        service.registerSchema(evolvedSchema());
        ClientException mismatch = assertThrows(ClientException.class,
                () -> service.backfill("events", new BackfillRequest("tier", "zero", "k1")));
        assertEquals(ErrorCode.TYPE_MISMATCH, mismatch.code());

        // first real attempt succeeds
        BackfillResult first = service.backfill("events", new BackfillRequest("tier", 0, "k1"));
        assertEquals(5, first.backfilled());
        assertFalse(first.replayed());

        // new rows written after the backfill with their own tier value
        service.writeRecords("events", List.of(
                Map.of("id", 6, "price", 6.0, "tier", 3),
                Map.of("id", 7, "price", 7.0)));

        // retry of the same failed-delivery request: replays, changes nothing
        BackfillResult retry = service.backfill("events", new BackfillRequest("tier", 0, "k1"));
        assertTrue(retry.replayed(), "same idempotency key must replay the previous outcome");
        assertEquals(first.seq(), retry.seq(), "replay must not consume a new seq");
        assertEquals(5, retry.backfilled());

        // explicit value is intact and the post-backfill row is still missing (not re-filled)
        QueryResult threes = service.query("events",
                new QueryRequest(List.of(new QueryRequest.Filter("id", QueryRequest.Op.EQ, 6)),
                        List.of("tier"), null, null, 0, 1, null));
        assertEquals(3, threes.rows().get(0).get("tier"));
        QueryResult nulls = service.query("events",
                new QueryRequest(List.of(new QueryRequest.Filter("id", QueryRequest.Op.EQ, 7)),
                        List.of("tier"), null, null, 0, 1, null));
        assertNull(nulls.rows().get(0).get("tier"), "retry must not fill rows written after the first backfill");
    }

    @Test
    @DisplayName("a pinned snapshot taken before backfill keeps the consistent old view and is flagged stale afterwards")
    void snapshotBeforeBackfillStaysConsistentAndMarkedStale() {
        DatasetService service = newService();
        service.registerSchema(new DatasetSchema("events", List.of(
                new FieldDef("id", FieldType.INTEGER, true),
                new FieldDef("price", FieldType.DOUBLE, true))));
        service.writeRecords("events", records(1, 10));
        service.registerSchema(evolvedSchema());

        QueryResult before = service.query("events",
                new QueryRequest(null, List.of("id", "tier"), null, null, 0, 5, null));
        long token = before.snapshotSeq();
        assertFalse(before.snapshotStale());
        for (Map<String, Object> row : before.rows()) {
            assertNull(row.get("tier"));
        }

        BackfillResult bf = service.backfill("events", new BackfillRequest("tier", 0, null));
        assertTrue(bf.seq() > token);

        // next page on the OLD token: still the pre-backfill view (all nulls), no mixing,
        // but explicitly flagged so the caller knows a newer state exists
        QueryResult nextPage = service.query("events",
                new QueryRequest(null, List.of("id", "tier"), null, null, 5, 5, token));
        assertTrue(nextPage.snapshotStale(), "a token predating a backfill must be flagged stale");
        assertEquals(token, nextPage.snapshotSeq(), "token must be preserved, not rewritten");
        assertEquals(10, nextPage.total(), "the old view's total must stay stable");
        for (Map<String, Object> row : nextPage.rows()) {
            assertNull(row.get("tier"), "old snapshot pages must not mix in post-backfill values");
        }

        // a fresh snapshot shows the post-backfill world and is not stale
        QueryResult fresh = service.query("events",
                new QueryRequest(null, List.of("id", "tier"), null, null, 0, 10, null));
        assertFalse(fresh.snapshotStale());
        for (Map<String, Object> row : fresh.rows()) {
            assertEquals(0, row.get("tier"));
        }
        log.info("[COVERAGE] old token {} stayed on the null view with snapshotStale=true; fresh token {} shows tier=0",
                token, fresh.snapshotSeq());
    }

    @Test
    @DisplayName("backfilling a null value is rejected with a clear reason")
    void nullBackfillRejected() {
        DatasetService service = newService();
        service.registerSchema(evolvedSchema());
        ClientException ex = assertThrows(ClientException.class,
                () -> service.backfill("events", new BackfillRequest("tier", null, null)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.code());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
