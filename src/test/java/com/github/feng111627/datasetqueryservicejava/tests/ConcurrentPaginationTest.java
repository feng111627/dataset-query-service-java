package com.github.feng111627.datasetqueryservicejava.tests;

import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.FieldType;
import com.github.feng111627.datasetqueryservicejava.model.QueryRequest;
import com.github.feng111627.datasetqueryservicejava.model.QueryResult;
import com.github.feng111627.datasetqueryservicejava.model.Snapshot;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@CoversScenario("concurrent writes during pagination: snapshot-token pages are stable, duplicate-free and gap-free")
@ExtendWith(BusinessCoverageLogger.class)
class ConcurrentPaginationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentPaginationTest.class);

    private static final DatasetSchema SCHEMA = new DatasetSchema("events", List.of(
            new FieldDef("id", FieldType.INTEGER, true),
            new FieldDef("value", FieldType.DOUBLE, true)
    ));

    private DatasetService newService(DatasetStore store) {
        return new DatasetService(store, new SchemaValidator(), new SchemaCompatibility(), new QueryEngine());
    }

    private static List<Map<String, Object>> records(int from, int to) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            records.add(Map.of("id", i, "value", i * 1.0));
        }
        return records;
    }

    @Test
    @DisplayName("paginating with a snapshot token while a writer appends: no duplicates, no gaps, stable totals")
    void paginationIsStableUnderConcurrentWrites() throws Exception {
        DatasetStore store = new DatasetStore();
        DatasetService service = newService(store);
        service.registerSchema(SCHEMA).schema();
        service.writeRecords("events", records(1, 100));

        // Pin the snapshot BEFORE any concurrent write lands: the first page
        // fixes the token, then the writer starts appending.
        QueryResult firstPage = service.query("events",
                new QueryRequest(null, List.of("id"), null, null, 0, 10, null));
        final long token = firstPage.snapshotSeq();
        assertEquals(100, firstPage.total());

        // Writer appends 100 more records while the reader pages through.
        AtomicInteger written = new AtomicInteger(0);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        CountDownLatch done = new CountDownLatch(1);
        writer.submit(() -> {
            try {
                for (int i = 101; i <= 200; i++) {
                    service.writeRecords("events", records(i, i));
                    written.incrementAndGet();
                }
            } finally {
                done.countDown();
            }
        });

        // The pinned token is passed back on every page (including re-reading page 1).
        Set<Integer> seenIds = new HashSet<>();
        long expectedTotal = 100;
        int offset = 0;
        int pageSize = 10;
        int pages = 0;
        while (true) {
            QueryResult page = service.query("events",
                    new QueryRequest(null, List.of("id"), null, null, offset, pageSize, token));
            assertEquals(token, page.snapshotSeq(), "snapshot token must stay constant across pages");
            assertEquals(expectedTotal, page.total(), "total changed between pages");
            for (Map<String, Object> row : page.rows()) {
                Integer id = (Integer) row.get("id");
                assertTrue(id <= 100, "snapshot leaked a concurrently written record: " + id);
                assertTrue(seenIds.add(id), "duplicate row across pages: " + id);
            }
            pages++;
            offset += pageSize;
            if (page.rows().isEmpty()) {
                break;
            }
        }

        assertTrue(done.await(10, TimeUnit.SECONDS), "writer did not finish");
        writer.shutdown();

        assertEquals(100, expectedTotal);
        assertEquals(100, seenIds.size(), "paginated rows must cover the snapshot exactly once");
        assertEquals(11, pages, "100 rows in pages of 10 -> 10 full pages + 1 empty page");
        assertEquals(100, written.get());
        // A fresh query (no token) sees everything, including concurrent writes.
        QueryResult fresh = service.query("events",
                new QueryRequest(null, List.of("id"), null, null, 0, 1, null));
        assertEquals(200, fresh.total());
        log.info("[COVERAGE] paginated {} pages over a 100-record snapshot (token={}) while {} records "
                + "were written concurrently; no duplicates, no gaps, total stable at {}",
                pages, token, written.get(), expectedTotal);
    }

    @Test
    @DisplayName("default order is insertion order and reproducible across repeated queries")
    void defaultOrderIsStableAndReproducible() {
        DatasetStore store = new DatasetStore();
        DatasetService service = newService(store);
        service.registerSchema(SCHEMA).schema();
        service.writeRecords("events", records(1, 50));

        QueryRequest request = new QueryRequest(null, List.of("id"), null, null, 0, 50, null);
        QueryResult first = service.query("events", request);
        QueryResult second = service.query("events", request);
        assertEquals(first.rows(), second.rows(), "repeated queries must return identical rows");
        for (int i = 0; i < 50; i++) {
            assertEquals(i + 1, first.rows().get(i).get("id"), "default order must be insertion order");
        }
    }

    @Test
    @DisplayName("explicit sort with equal keys falls back to insertion order (total order)")
    void sortTieBreakIsDeterministic() {
        DatasetStore store = new DatasetStore();
        DatasetService service = newService(store);
        service.registerSchema(SCHEMA).schema();
        // 30 records, only 3 distinct 'value' keys -> heavy ties.
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            records.add(Map.of("id", i, "value", (double) (i % 3)));
        }
        service.writeRecords("events", records);

        QueryRequest request = new QueryRequest(null, List.of("id", "value"), null,
                List.of(new QueryRequest.SortField("value", true)), 0, 30, null);
        QueryResult first = service.query("events", request);
        QueryResult second = service.query("events", request);
        assertEquals(first.rows(), second.rows());

        // Within equal sort keys, ids must appear in insertion order.
        double previousValue = Double.NEGATIVE_INFINITY;
        int previousIdInGroup = 0;
        for (Map<String, Object> row : first.rows()) {
            double value = (Double) row.get("value");
            int id = (Integer) row.get("id");
            assertTrue(value >= previousValue, "rows must be sorted by value");
            if (value == previousValue) {
                assertTrue(id > previousIdInGroup, "ties must resolve by insertion order");
            } else {
                previousValue = value;
            }
            previousIdInGroup = id;
        }
    }

    @Test
    @DisplayName("re-reading a snapshot token after more writes yields identical records")
    void snapshotTokenIsReproducible() {
        DatasetStore store = new DatasetStore();
        store.registerSchema(SCHEMA);
        store.append("events", records(1, 20));
        Snapshot first = store.snapshot("events");
        store.append("events", records(21, 40));
        Snapshot again = store.snapshot("events", first.highWaterMark());
        assertEquals(first.records(), again.records());
        assertEquals(20, again.records().size());
        assertEquals(40, store.snapshot("events").records().size());
    }
}
