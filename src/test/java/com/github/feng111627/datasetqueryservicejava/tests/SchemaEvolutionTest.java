package com.github.feng111627.datasetqueryservicejava.tests;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.FieldType;
import com.github.feng111627.datasetqueryservicejava.model.QueryRequest;
import com.github.feng111627.datasetqueryservicejava.model.QueryResult;
import com.github.feng111627.datasetqueryservicejava.model.SchemaVersion;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@CoversScenario("schema evolution: compatible changes take effect immediately, are versioned and traceable; concurrent evolutions are atomic")
@ExtendWith(BusinessCoverageLogger.class)
class SchemaEvolutionTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaEvolutionTest.class);

    private static final DatasetSchema V1 = new DatasetSchema("orders", List.of(
            new FieldDef("id", FieldType.INTEGER, true),
            new FieldDef("amount", FieldType.DOUBLE, true)
    ));

    private DatasetService newService() {
        return new DatasetService(new DatasetStore(), new SchemaValidator(),
                new SchemaCompatibility(), new QueryEngine());
    }

    @Test
    @DisplayName("accepted evolution takes effect immediately for writes, schema lookup, projection, filter and aggregation")
    void evolutionTakesEffectImmediately() {
        DatasetService service = newService();
        service.registerSchema(V1);
        service.writeRecords("orders", List.of(Map.of("id", 1, "amount", 10.0)));

        DatasetSchema v2 = new DatasetSchema("orders", List.of(
                new FieldDef("id", FieldType.INTEGER, true),
                new FieldDef("amount", FieldType.DOUBLE, true),
                new FieldDef("channel", FieldType.STRING, false)
        ));
        DatasetSchema effective = service.registerSchema(v2);
        assertEquals(v2.fields(), effective.fields(), "evolved schema must become the effective one");

        // schema lookup shows the new field right away
        assertNotNull(service.schemaOf("orders").field("channel"));

        // writes carrying the new field pass validation
        service.writeRecords("orders", List.of(Map.of("id", 2, "amount", 20.0, "channel", "web")));

        // the new field can be selected, filtered and aggregated
        QueryResult result = service.query("orders", new QueryRequest(
                List.of(new QueryRequest.Filter("channel", QueryRequest.Op.EQ, "web")),
                List.of("id", "channel"),
                List.of(new QueryRequest.Aggregation(QueryRequest.AggFunc.COUNT, null, "n")),
                null, null, null, null));
        assertEquals(1, result.total());
        assertEquals("web", result.rows().get(0).get("channel"));
        assertEquals(1L, ((Number) result.aggregations().get("n")).longValue());

        // pre-evolution records are unaffected: the new field reads as null
        QueryResult all = service.query("orders", new QueryRequest(
                null, List.of("id", "channel"), null,
                List.of(new QueryRequest.SortField("id", true)), null, null, null));
        assertEquals(2, all.total());
        assertNull(all.rows().get(0).get("channel"), "old record must read the new field as absent");
        assertEquals("web", all.rows().get(1).get("channel"));
        log.info("[COVERAGE] evolution applied: writes/schema/projection/filter/aggregation all see 'channel';"
                + " pre-evolution record reads it as null");
    }

    @Test
    @DisplayName("every accepted evolution gets an increasing version and a traceable history entry")
    void evolutionIsVersionedAndTraceable() {
        DatasetService service = newService();
        service.registerSchema(V1);

        // identical re-registration is a no-op: no version bump
        service.registerSchema(V1);
        assertEquals(1, service.schemaHistory("orders").size());

        // v2: add optional field; v3: relax required -> optional
        service.registerSchema(new DatasetSchema("orders", List.of(
                new FieldDef("id", FieldType.INTEGER, true),
                new FieldDef("amount", FieldType.DOUBLE, true),
                new FieldDef("channel", FieldType.STRING, false)
        )));
        service.registerSchema(new DatasetSchema("orders", List.of(
                new FieldDef("id", FieldType.INTEGER, true),
                new FieldDef("amount", FieldType.DOUBLE, false),
                new FieldDef("channel", FieldType.STRING, false)
        )));

        List<SchemaVersion> history = service.schemaHistory("orders");
        assertEquals(3, history.size());
        assertEquals(List.of(1L, 2L, 3L), history.stream().map(SchemaVersion::version).toList());
        assertEquals(List.of("id", "amount"), history.get(0).addedFields());
        assertEquals(List.of("channel"), history.get(1).addedFields());
        assertEquals(List.of(), history.get(1).relaxedFields());
        assertEquals(List.of("amount"), history.get(2).relaxedFields());
        assertEquals(List.of(), history.get(2).addedFields());
        history.forEach(v -> assertNotNull(v.effectiveAt(), "each version must record when it took effect"));
        log.info("[COVERAGE] schema history: {}", history);
    }

    @Test
    @DisplayName("incompatible evolution is rejected with all conflicts listed and leaves the effective schema unchanged")
    void incompatibleEvolutionIsRejectedAtomically() {
        DatasetService service = newService();
        service.registerSchema(V1);

        DatasetSchema bad = new DatasetSchema("orders", List.of(
                new FieldDef("id", FieldType.STRING, true),      // type change
                // amount removed                                        // removal
                new FieldDef("tenant", FieldType.STRING, true)   // new required field
        ));
        ClientException ex = assertThrows(ClientException.class, () -> service.registerSchema(bad));
        assertEquals(ErrorCode.INCOMPATIBLE_SCHEMA, ex.code());
        assertEquals(3, ex.details().size(), "all conflicts must be listed at once: " + ex.details());

        // the failed evolution changed nothing
        assertEquals(V1.fields(), service.schemaOf("orders").fields());
        assertEquals(1, service.schemaHistory("orders").size());
    }

    @Test
    @DisplayName("concurrent evolutions are serialized: each applies completely or fails cleanly, never half-visible")
    void concurrentEvolutionsAreAtomic() throws Exception {
        DatasetService service = newService();
        service.registerSchema(V1);

        // Two threads evolve concurrently: one adds optional 'channel', the
        // other adds optional 'region'. Each proposal omits the other's new
        // field, so exactly one wins; the loser is rejected wholesale
        // (INCOMPATIBLE_SCHEMA) instead of being half-applied. The loser can
        // then retry on top of the winner's schema and succeed.
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> succeeded = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ClientException> rejected = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (String field : List.of("channel", "region")) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    List<FieldDef> fields = new ArrayList<>(V1.fields());
                    fields.add(new FieldDef(field, FieldType.STRING, false));
                    service.registerSchema(new DatasetSchema("orders", fields));
                    succeeded.add(field);
                } catch (ClientException ex) {
                    rejected.add(ex);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, succeeded.size(), "exactly one of the conflicting evolutions may apply");
        assertEquals(1, rejected.size());
        assertEquals(ErrorCode.INCOMPATIBLE_SCHEMA, rejected.peek().code(),
                "the loser must be rejected wholesale, not partially applied");

        // the winner's schema is fully and immediately visible to everyone
        DatasetSchema afterFirst = service.schemaOf("orders");
        String winner = succeeded.peek();
        assertNotNull(afterFirst.field(winner));
        assertEquals(2, service.schemaHistory("orders").size());

        // the loser retries on top of the winner's schema: now compatible
        String loserField = winner.equals("channel") ? "region" : "channel";
        List<FieldDef> retryFields = new ArrayList<>(afterFirst.fields());
        retryFields.add(new FieldDef(loserField, FieldType.STRING, false));
        service.registerSchema(new DatasetSchema("orders", retryFields));

        DatasetSchema effective = service.schemaOf("orders");
        assertNotNull(effective.field("channel"));
        assertNotNull(effective.field("region"));
        List<SchemaVersion> history = service.schemaHistory("orders");
        assertEquals(List.of(1L, 2L, 3L), history.stream().map(SchemaVersion::version).toList());
        // each accepted version introduced exactly one field: no half-applied state
        history.subList(1, 3).forEach(v -> assertEquals(1, v.addedFields().size()));
        log.info("[COVERAGE] conflicting concurrent evolutions: winner='{}', loser rejected then retried;"
                + " final version=3, fields={}", winner,
                effective.fields().stream().map(FieldDef::name).toList());
    }

    @Test
    @DisplayName("concurrent identical evolutions both succeed and bump the version only once")
    void concurrentIdenticalEvolutionsApplyOnce() throws Exception {
        DatasetService service = newService();
        service.registerSchema(V1);

        DatasetSchema v2 = new DatasetSchema("orders", List.of(
                new FieldDef("id", FieldType.INTEGER, true),
                new FieldDef("amount", FieldType.DOUBLE, true),
                new FieldDef("channel", FieldType.STRING, false)
        ));
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    go.await();
                    service.registerSchema(v2);
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(List.of(), List.copyOf(failures));
        assertEquals(2, service.schemaHistory("orders").size(),
                "the same evolution applied concurrently must be recorded exactly once");
        assertNotNull(service.schemaOf("orders").field("channel"));
    }
}
