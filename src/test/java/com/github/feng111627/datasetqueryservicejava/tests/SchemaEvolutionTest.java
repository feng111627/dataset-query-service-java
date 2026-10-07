package com.github.feng111627.datasetqueryservicejava.tests;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.FieldType;
import com.github.feng111627.datasetqueryservicejava.model.QueryRequest;
import com.github.feng111627.datasetqueryservicejava.model.QueryResult;
import com.github.feng111627.datasetqueryservicejava.model.SchemaChange;
import com.github.feng111627.datasetqueryservicejava.model.SchemaView;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@CoversScenario("schema evolution: compatible changes take effect immediately for writes/filters/projection/aggregation, carry increasing versions and are traceable; concurrent evolutions are atomic")
@ExtendWith(BusinessCoverageLogger.class)
class SchemaEvolutionTest {

    private DatasetService newService() {
        DatasetStore store = new DatasetStore();
        return new DatasetService(store, new SchemaValidator(), new SchemaCompatibility(), new QueryEngine());
    }

    private static DatasetSchema v1() {
        return new DatasetSchema("events", List.of(
                new FieldDef("id", FieldType.INTEGER, true),
                new FieldDef("price", FieldType.DOUBLE, true)
        ));
    }

    private static DatasetSchema v2() {
        return new DatasetSchema("events", List.of(
                new FieldDef("id", FieldType.INTEGER, true),
                new FieldDef("price", FieldType.DOUBLE, true),
                new FieldDef("region", FieldType.STRING, false)
        ));
    }

    private static DatasetSchema v3() {
        return new DatasetSchema("events", List.of(
                new FieldDef("id", FieldType.INTEGER, true),
                new FieldDef("price", FieldType.DOUBLE, false),
                new FieldDef("region", FieldType.STRING, false)
        ));
    }

    @Test
    @DisplayName("adding an optional field bumps the version, is traced and immediately usable everywhere")
    void addedFieldTakesEffectImmediately() {
        DatasetService service = newService();
        assertEquals(1, service.registerSchema(v1()).version());

        // historical records written before the field existed
        service.writeRecords("events", List.of(
                Map.of("id", 1, "price", 10.0),
                Map.of("id", 2, "price", 20.0)));

        SchemaView view = service.registerSchema(v2());
        assertEquals(2, view.version(), "accepted evolution must bump the version");
        assertEquals(2, service.schemaView("events").version());

        // history: version 2 added 'region', effectiveAt is recorded
        List<SchemaChange> changes = view.changes();
        assertEquals(1, changes.size());
        SchemaChange change = changes.get(0);
        assertEquals(2, change.version());
        assertEquals(SchemaChange.ChangeType.FIELD_ADDED, change.changeType());
        assertEquals("region", change.fieldName());
        assertEquals(FieldType.STRING, change.fieldType());
        assertFalse(change.required());
        assertNotNull(change.effectiveAt());

        // new writes carrying the field validate
        assertDoesNotThrow(() -> service.writeRecords("events", List.of(
                Map.of("id", 3, "price", 30.0, "region", "cn"))));

        // schema query sees the new field; historical rows project it as null
        QueryResult result = service.query("events",
                new QueryRequest(null, List.of("id", "region"), null, null, 0, 10, null));
        assertEquals(3, result.total());
        assertNull(result.rows().get(0).get("region"), "old records behave as if the field has no value");
        assertEquals("cn", result.rows().get(2).get("region"));

        // filtering on the new field works (null old rows do not match EQ)
        QueryResult inCn = service.query("events",
                new QueryRequest(List.of(new QueryRequest.Filter("region", QueryRequest.Op.EQ, "cn")),
                        List.of("id"), null, null, 0, 10, null));
        assertEquals(1, inCn.total());
        assertEquals(3, inCn.rows().get(0).get("id"));

        // aggregation on the new field works and ignores nulls
        QueryResult agg = service.query("events",
                new QueryRequest(null, null, List.of(new QueryRequest.Aggregation(
                        QueryRequest.AggFunc.COUNT, null, "cnt")), null, 0, 1, null));
        assertEquals(3L, agg.aggregations().get("cnt"));
    }

    @Test
    @DisplayName("relaxing required to optional bumps the version and leaves old required writes intact")
    void relaxationTakesEffect() {
        DatasetService service = newService();
        service.registerSchema(v1());
        service.writeRecords("events", List.of(Map.of("id", 1, "price", 10.0)));

        SchemaView view = service.registerSchema(v3());
        assertEquals(2, view.version());
        SchemaChange change = view.changes().get(0);
        assertEquals(SchemaChange.ChangeType.REQUIRED_TO_OPTIONAL, change.changeType());
        assertEquals("price", change.fieldName());

        // records without the now-optional field validate
        assertDoesNotThrow(() -> service.writeRecords("events", List.of(Map.of("id", 9))));
        QueryResult result = service.query("events",
                new QueryRequest(null, List.of("id", "price"), null, null, 0, 10, null));
        assertEquals(2, result.total());
        assertNull(result.rows().get(1).get("price"));
    }

    @Test
    @DisplayName("an identical re-registration does not bump the version or add history")
    void identicalRegistrationIsNoop() {
        DatasetService service = newService();
        service.registerSchema(v1());
        SchemaView again = service.registerSchema(v1());
        assertEquals(1, again.version());
        assertTrue(again.changes().isEmpty());
    }

    @Test
    @DisplayName("an incompatible evolution lists every distinguishable conflict at once and changes nothing")
    void incompatibleEvolutionListsAllConflicts() {
        DatasetService service = newService();
        service.registerSchema(v1());

        // remove 'id', change price type, add a new required field -> 3 conflicts
        DatasetSchema bad = new DatasetSchema("events", List.of(
                new FieldDef("price", FieldType.STRING, true),
                new FieldDef("region", FieldType.STRING, true)
        ));
        ClientException ex = assertThrows(ClientException.class,
                () -> service.registerSchema(bad));
        assertEquals(ErrorCode.INCOMPATIBLE_SCHEMA, ex.code());
        assertEquals(3, ex.details().size(), "all conflicts must be listed at once: " + ex.details());
        assertTrue(ex.details().stream().anyMatch(d -> d.contains("id") && d.contains("removed")));
        assertTrue(ex.details().stream().anyMatch(d -> d.contains("price") && d.contains("type")));
        assertTrue(ex.details().stream().anyMatch(d -> d.contains("region") && d.contains("required")));

        // nothing took effect: version and fields unchanged
        SchemaView view = service.schemaView("events");
        assertEquals(1, view.version());
        assertEquals(List.of("id", "price"), view.schema().fields().stream().map(FieldDef::name).toList());
        // new required field is not live: writing it stays rejected as unknown
        ClientException writeEx = assertThrows(ClientException.class,
                () -> service.writeRecords("events", List.of(
                        Map.of("id", 1, "price", 1.0, "region", "cn"))));
        assertEquals(ErrorCode.UNKNOWN_FIELD, writeEx.code());
    }

    @Test
    @DisplayName("concurrent compatible + incompatible evolutions: only one whole schema is ever visible")
    void concurrentEvolutionsAreAtomic() throws Exception {
        int runs = 20;
        for (int run = 0; run < runs; run++) {
            DatasetService service = newService();
            service.registerSchema(v1());

            // one good thread adds optional 'region'; several bad threads try to
            // change a type and add a required field.
            int threads = 6;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            List<Runnable> tasks = new ArrayList<>();
            tasks.add(() -> {
                await(start);
                try { service.registerSchema(v2()); accepted.incrementAndGet(); }
                catch (ClientException e) { rejected.incrementAndGet(); }
            });
            for (int t = 1; t < threads; t++) {
                DatasetSchema bad = new DatasetSchema("events", List.of(
                        new FieldDef("id", FieldType.STRING, true),
                        new FieldDef("price", FieldType.DOUBLE, true),
                        new FieldDef("r" + t, FieldType.STRING, true)
                ));
                tasks.add(() -> {
                    await(start);
                    try { service.registerSchema(bad); accepted.incrementAndGet(); }
                    catch (ClientException e) { rejected.incrementAndGet(); }
                });
            }
            for (Runnable task : tasks) {
                pool.submit(task);
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            // Either the good evolution committed (version 2 with region optional
            // and exactly 3 fields) or nothing changed (version 1) — never a mix.
            SchemaView view = service.schemaView("events");
            if (view.version() == 2) {
                assertEquals(1, accepted.get());
                assertEquals(threads - 1, rejected.get());
                assertEquals(List.of("id", "price", "region"),
                        view.schema().fields().stream().map(FieldDef::name).toList());
                assertDoesNotThrow(() -> service.writeRecords("events", List.of(
                        Map.of("id", 1, "price", 1.0))),
                        "committed schema must be internally consistent: id stays INTEGER");
            } else {
                assertEquals(1, view.version());
                assertEquals(0, accepted.get());
                assertEquals(threads - 1, rejected.get());
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
