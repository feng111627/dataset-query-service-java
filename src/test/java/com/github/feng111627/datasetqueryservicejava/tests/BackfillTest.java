package com.github.feng111627.datasetqueryservicejava.tests;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.BackfillRequest;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@CoversScenario("backfill: atomic default-value fill of existing records, ordered against concurrent writes, idempotent retries, snapshot invalidation")
@ExtendWith(BusinessCoverageLogger.class)
class BackfillTest {

    private static final Logger log = LoggerFactory.getLogger(BackfillTest.class);

    private static final DatasetSchema SCHEMA = new DatasetSchema("events", List.of(
            new FieldDef("id", FieldType.INTEGER, true),
            new FieldDef("score", FieldType.DOUBLE, true),
            new FieldDef("tag", FieldType.STRING, false)
    ));

    private DatasetService newService() {
        return new DatasetService(new DatasetStore(), new SchemaValidator(),
                new SchemaCompatibility(), new QueryEngine());
    }

    /** Writes ids [from, to]; even ids carry an explicit tag, odd ids none. */
    private void write(DatasetService service, int from, int to) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            Map<String, Object> record = new HashMap<>();
            record.put("id", i);
            record.put("score", i * 1.0);
            if (i % 2 == 0) {
                record.put("tag", "explicit-" + i);
            }
            records.add(record);
        }
        service.writeRecords("events", records);
    }

    private QueryResult queryAll(DatasetService service, List<QueryRequest.Filter> filters) {
        return service.query("events", new QueryRequest(filters, List.of("id", "tag"), null,
                List.of(new QueryRequest.SortField("id", true)), 0, 1000, null));
    }

    @Test
    @DisplayName("backfill fills every existing record that lacks the field and leaves explicit values untouched")
    void backfillAppliesToAllExistingRecords() {
        DatasetService service = newService();
        service.registerSchema(SCHEMA);
        write(service, 1, 10); // 5 odd ids without tag, 5 even with explicit tag

        Map<String, Object> outcome = service.backfill("events",
                new BackfillRequest("tag", "default", "bf-1"));
        assertEquals(5, outcome.get("backfilled"));
        assertEquals(10L, outcome.get("highWaterMark"));
        assertEquals(false, outcome.get("replayed"));

        QueryResult all = queryAll(service, null);
        for (Map<String, Object> row : all.rows()) {
            int id = ((Number) row.get("id")).intValue();
            Object expected = id % 2 == 0 ? "explicit-" + id : "default";
            assertEquals(expected, row.get("tag"), "record " + id);
        }

        // filtering and aggregation on the backfilled field behave exactly as
        // if every historical record had been written with the default
        QueryResult filtered = queryAll(service,
                List.of(new QueryRequest.Filter("tag", QueryRequest.Op.EQ, "default")));
        assertEquals(5, filtered.total());
        QueryResult aggregated = service.query("events", new QueryRequest(null, null,
                List.of(new QueryRequest.Aggregation(QueryRequest.AggFunc.COUNT, null, "n"),
                        new QueryRequest.Aggregation(QueryRequest.AggFunc.MIN, "tag", "min_tag")),
                null, null, null, null));
        assertEquals(10L, ((Number) aggregated.aggregations().get("n")).longValue());
        assertEquals("default", aggregated.aggregations().get("min_tag"));
        log.info("[COVERAGE] backfill filled 5/10 records; explicit values kept;"
                + " filter/aggregation see the default on all historical records");
    }

    @Test
    @DisplayName("records written after the backfill are never modified by it or by its retries")
    void backfillDoesNotTouchLaterWrites() {
        DatasetService service = newService();
        service.registerSchema(SCHEMA);
        write(service, 1, 4);

        service.backfill("events", new BackfillRequest("tag", "default", "bf-1"));
        // new writes after the backfill may still omit the optional field
        write(service, 5, 6); // id 5 has no tag

        Map<String, Object> retry = service.backfill("events",
                new BackfillRequest("tag", "default", "bf-1"));
        assertEquals(true, retry.get("replayed"), "same requestId must replay, not re-apply");
        assertEquals(2, retry.get("backfilled"), "replay reports the original count");

        QueryResult all = queryAll(service, null);
        Map<Integer, Object> tags = new HashMap<>();
        all.rows().forEach(r -> tags.put(((Number) r.get("id")).intValue(), r.get("tag")));
        assertEquals("default", tags.get(1));
        assertEquals("explicit-2", tags.get(2));
        assertNull(tags.get(5), "record written after the backfill must stay untouched, even across retries");
    }

    @Test
    @DisplayName("a failed backfill changes nothing and can be retried with the same requestId")
    void failedBackfillIsRetryable() {
        DatasetService service = newService();
        service.registerSchema(SCHEMA);
        write(service, 1, 4);

        // type mismatch: rejected before anything is applied
        ClientException ex = assertThrows(ClientException.class, () -> service.backfill("events",
                new BackfillRequest("tag", 42, "bf-1")));
        assertEquals(ErrorCode.TYPE_MISMATCH, ex.code());
        QueryResult before = queryAll(service, null);
        assertEquals(2, before.rows().stream().filter(r -> r.get("tag") == null).count());

        // retry with the same requestId and a valid value succeeds cleanly
        Map<String, Object> outcome = service.backfill("events",
                new BackfillRequest("tag", "default", "bf-1"));
        assertEquals(2, outcome.get("backfilled"));
        assertEquals(false, outcome.get("replayed"));

        // reusing the same requestId with different parameters is rejected
        ClientException conflict = assertThrows(ClientException.class, () -> service.backfill("events",
                new BackfillRequest("tag", "other", "bf-1")));
        assertEquals(ErrorCode.VALIDATION_ERROR, conflict.code());
    }

    @Test
    @DisplayName("backfill is atomic against concurrent writes: every record up to the mark is filled, none after it")
    void backfillIsAtomicAgainstConcurrentWrites() throws Exception {
        DatasetService service = newService();
        service.registerSchema(SCHEMA);
        write(service, 1, 50);

        AtomicInteger nextId = new AtomicInteger(51);
        CountDownLatch keepWriting = new CountDownLatch(1);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        writer.submit(() -> {
            try {
                while (!keepWriting.await(1, TimeUnit.MILLISECONDS)) {
                    int id = nextId.getAndIncrement();
                    if (id > 250) {
                        return;
                    }
                    write(service, id, id);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Map<String, Object> outcome = service.backfill("events",
                new BackfillRequest("tag", "default", "bf-atomic"));
        keepWriting.countDown();
        writer.shutdown();
        assertTrue(writer.awaitTermination(10, TimeUnit.SECONDS));

        long mark = ((Number) outcome.get("highWaterMark")).longValue();
        // every record at or below the backfill mark carries a non-null tag;
        // the read state is consistent: no half-filled boundary
        QueryResult all = service.query("events", new QueryRequest(null, List.of("id", "tag"),
                null, null, 0, 1000, null));
        assertTrue(all.snapshotSeq() > 0);
        int filled = 0;
        for (Map<String, Object> row : all.rows()) {
            // rows don't expose seq; re-derive via snapshot of the store instead
        }
        // verify via filters: no record may have a null tag up to the mark.
        // ids are contiguous from 1, so id == seq here.
        QueryResult nullTagged = queryAll(service,
                List.of(new QueryRequest.Filter("tag", QueryRequest.Op.IS_NULL, null)));
        for (Map<String, Object> row : nullTagged.rows()) {
            int id = ((Number) row.get("id")).intValue();
            assertTrue(id > mark, "record " + id + " predates the backfill mark " + mark
                    + " but was not backfilled");
        }
        QueryResult defaulted = queryAll(service,
                List.of(new QueryRequest.Filter("tag", QueryRequest.Op.EQ, "default")));
        assertEquals(outcome.get("backfilled"), (int) ((Number) defaulted.total()).intValue()
                + 0, "filled count must match the outcome");
        filled = ((Number) defaulted.total()).intValue();
        log.info("[COVERAGE] backfill under concurrent writes: mark={}, filled={}, "
                + "all records up to the mark carry a value, later writes untouched", mark, filled);
    }

    @Test
    @DisplayName("a data-changing backfill invalidates previously issued snapshot tokens (SNAPSHOT_EXPIRED)")
    void backfillInvalidatesPriorSnapshotTokens() {
        DatasetService service = newService();
        service.registerSchema(SCHEMA);
        write(service, 1, 10);

        QueryResult firstPage = service.query("events",
                new QueryRequest(null, List.of("id"), null, null, 0, 5, null));
        long token = firstPage.snapshotSeq();

        service.backfill("events", new BackfillRequest("tag", "default", "bf-1"));

        ClientException ex = assertThrows(ClientException.class, () -> service.query("events",
                new QueryRequest(null, List.of("id"), null, null, 5, 5, token)));
        assertEquals(ErrorCode.SNAPSHOT_EXPIRED, ex.code(),
                "pre-backfill tokens must be rejected, never silently mixed with post-backfill data");

        // a fresh snapshot reads the post-backfill state consistently
        QueryResult fresh = queryAll(service,
                List.of(new QueryRequest.Filter("tag", QueryRequest.Op.EQ, "default")));
        assertEquals(5, fresh.total());
        log.info("[COVERAGE] pre-backfill token rejected with SNAPSHOT_EXPIRED; fresh snapshot sees backfilled data");
    }

    @Test
    @DisplayName("a backfill that changes nothing keeps existing snapshot tokens valid")
    void noOpBackfillKeepsSnapshotTokens() {
        DatasetService service = newService();
        service.registerSchema(SCHEMA);
        // every record already carries an explicit tag: the backfill is a no-op
        write(service, 2, 2);
        write(service, 4, 4);
        write(service, 6, 6);
        write(service, 8, 8);
        write(service, 10, 10);

        QueryResult firstPage = service.query("events",
                new QueryRequest(null, List.of("id"), null, null, 0, 3, null));
        long token = firstPage.snapshotSeq();

        Map<String, Object> outcome = service.backfill("events",
                new BackfillRequest("tag", "default", "bf-1"));
        assertEquals(0, outcome.get("backfilled"));

        QueryResult secondPage = service.query("events",
                new QueryRequest(null, List.of("id"), null, null, 3, 3, token));
        assertEquals(2, secondPage.rows().size(), "no-op backfill must not invalidate snapshots");
    }

    @Test
    @DisplayName("invalid backfill requests are rejected with distinguishable client errors")
    void invalidBackfillRequests() {
        DatasetService service = newService();
        service.registerSchema(SCHEMA);
        write(service, 1, 2);

        ClientException unknownField = assertThrows(ClientException.class, () ->
                service.backfill("events", new BackfillRequest("nope", "x", "bf-1")));
        assertEquals(ErrorCode.VALIDATION_ERROR, unknownField.code());

        ClientException nullValue = assertThrows(ClientException.class, () ->
                service.backfill("events", new BackfillRequest("tag", null, "bf-1")));
        assertEquals(ErrorCode.VALIDATION_ERROR, nullValue.code());

        ClientException noRequestId = assertThrows(ClientException.class, () ->
                service.backfill("events", new BackfillRequest("tag", "x", null)));
        assertEquals(ErrorCode.VALIDATION_ERROR, noRequestId.code());

        ClientException unknownDataset = assertThrows(ClientException.class, () ->
                service.backfill("missing", new BackfillRequest("tag", "x", "bf-1")));
        assertEquals(ErrorCode.SCHEMA_NOT_FOUND, unknownDataset.code());
    }
}
