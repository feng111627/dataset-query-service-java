package com.github.feng111627.datasetqueryservicejava.tests;

import com.github.feng111627.datasetqueryservicejava.tests.support.BusinessCoverageLogger;
import com.github.feng111627.datasetqueryservicejava.tests.support.CoversScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@CoversScenario("HTTP end-to-end for schema version history, backfill (incl. idempotent retry and stale snapshot flag) and IS NULL filtering")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(BusinessCoverageLogger.class)
class EvolutionBackfillHttpTest {

    @Autowired
    private TestRestTemplate rest;

    private static HttpEntity<?> json(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<Map> r) {
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(ResponseEntity<Map> r) {
        return (Map<String, Object>) r.getBody().get("error");
    }

    @Test
    @DisplayName("evolution over HTTP: versioned schema GET, traceable history, immediate write/query effect")
    void schemaEvolutionOverHttp() {
        String ds = "http_evo";
        ResponseEntity<Map> v1 = rest.exchange("/datasets/" + ds + "/schema", HttpMethod.PUT, json(Map.of(
                "fields", List.of(
                        Map.of("name", "id", "type", "INTEGER", "required", true),
                        Map.of("name", "price", "type", "DOUBLE", "required", true)))), Map.class);
        assertEquals(HttpStatus.OK, v1.getStatusCode());
        assertEquals(1, ((Number) body(v1).get("version")).intValue());

        rest.postForEntity("/datasets/" + ds + "/records",
                Map.of("records", List.of(Map.of("id", 1, "price", 1.0))), Map.class);

        // add optional field
        ResponseEntity<Map> v2 = rest.exchange("/datasets/" + ds + "/schema", HttpMethod.PUT, json(Map.of(
                "fields", List.of(
                        Map.of("name", "id", "type", "INTEGER", "required", true),
                        Map.of("name", "price", "type", "DOUBLE", "required", true),
                        Map.of("name", "region", "type", "STRING", "required", false)))), Map.class);
        assertEquals(HttpStatus.OK, v2.getStatusCode());
        assertEquals(2, ((Number) body(v2).get("version")).intValue());
        List<Map<String, Object>> changes = (List<Map<String, Object>>) body(v2).get("changes");
        assertEquals(1, changes.size());
        assertEquals("FIELD_ADDED", changes.get(0).get("changeType"));
        assertEquals("region", changes.get(0).get("fieldName"));
        assertEquals(2, ((Number) changes.get(0).get("version")).intValue());
        assertNotNull(changes.get(0).get("effectiveAt"));

        // GET history endpoint reports the same trace
        ResponseEntity<Map> history = rest.getForEntity("/datasets/" + ds + "/schema/history", Map.class);
        assertEquals(HttpStatus.OK, history.getStatusCode());
        assertEquals(2, ((Number) body(history).get("version")).intValue());
        assertEquals(1, ((List<?>) body(history).get("changes")).size());

        // write with new field works; old row projects null
        rest.postForEntity("/datasets/" + ds + "/records",
                Map.of("records", List.of(Map.of("id", 2, "price", 2.0, "region", "cn"))), Map.class);
        ResponseEntity<Map> q = rest.postForEntity("/datasets/" + ds + "/query",
                Map.of("columns", List.of("id", "region"), "sort",
                        List.of(Map.of("field", "id", "ascending", true))), Map.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body(q).get("rows");
        assertNull(rows.get(0).get("region"));
        assertEquals("cn", rows.get(1).get("region"));

        // incompatible evolution -> 409 with all conflicts
        ResponseEntity<Map> bad = rest.exchange("/datasets/" + ds + "/schema", HttpMethod.PUT, json(Map.of(
                "fields", List.of(
                        Map.of("name", "id", "type", "STRING", "required", false),
                        Map.of("name", "price", "type", "DOUBLE", "required", true),
                        Map.of("name", "region", "type", "STRING", "required", false),
                        Map.of("name", "must", "type", "STRING", "required", true)))), Map.class);
        assertEquals(HttpStatus.CONFLICT, bad.getStatusCode());
        assertEquals("INCOMPATIBLE_SCHEMA", error(bad).get("code"));
        List<String> details = (List<String>) error(bad).get("details");
        assertTrue(details.size() >= 2, "conflicts listed together: " + details);
    }

    @Test
    @DisplayName("backfill over HTTP: default fill, stale flag on old token, idempotent replay, IS NULL afterwards")
    void backfillOverHttp() {
        String ds = "http_backfill";
        rest.exchange("/datasets/" + ds + "/schema", HttpMethod.PUT, json(Map.of(
                "fields", List.of(
                        Map.of("name", "id", "type", "INTEGER", "required", true),
                        Map.of("name", "tier", "type", "INTEGER", "required", false)))), Map.class);
        for (int i = 1; i <= 5; i++) {
            rest.postForEntity("/datasets/" + ds + "/records",
                    Map.of("records", List.of(Map.of("id", i))), Map.class);
        }

        ResponseEntity<Map> firstPage = rest.postForEntity("/datasets/" + ds + "/query",
                Map.of("offset", 0, "limit", 2), Map.class);
        long token = ((Number) body(firstPage).get("snapshotSeq")).longValue();
        assertEquals(Boolean.FALSE, body(firstPage).get("snapshotStale"));

        // backfill unknown field -> 400
        ResponseEntity<Map> badField = rest.postForEntity("/datasets/" + ds + "/backfill",
                Map.of("field", "ghost", "value", 1), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, badField.getStatusCode());
        assertEquals("UNKNOWN_FIELD", error(badField).get("code"));

        // successful backfill with idempotency key
        ResponseEntity<Map> bf1 = rest.postForEntity("/datasets/" + ds + "/backfill",
                Map.of("field", "tier", "value", 0, "idempotencyKey", "run-1"), Map.class);
        assertEquals(HttpStatus.OK, bf1.getStatusCode());
        assertEquals(5, ((Number) body(bf1).get("backfilled")).intValue());
        assertEquals(Boolean.FALSE, body(bf1).get("replayed"));
        assertNotNull(body(bf1).get("seq"));

        // old token stays on pre-backfill view but is flagged stale
        ResponseEntity<Map> oldPage = rest.postForEntity("/datasets/" + ds + "/query",
                Map.of("offset", 2, "limit", 2, "snapshotSeq", token), Map.class);
        assertEquals(Boolean.TRUE, body(oldPage).get("snapshotStale"),
                "pages on a pre-backfill token must be explicitly flagged");
        assertEquals(5, ((Number) body(oldPage).get("total")).intValue());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body(oldPage).get("rows");
        assertTrue(rows.stream().allMatch(r -> r.get("tier") == null),
                "old snapshot must not leak backfilled values: " + rows);

        // retry same key: replayed, no new effect
        ResponseEntity<Map> bf2 = rest.postForEntity("/datasets/" + ds + "/backfill",
                Map.of("field", "tier", "value", 0, "idempotencyKey", "run-1"), Map.class);
        assertEquals(Boolean.TRUE, body(bf2).get("replayed"));
        assertEquals(body(bf1).get("seq"), body(bf2).get("seq"));

        // fresh query: all five filled; IS NULL finds none; IS NOT NULL finds five
        ResponseEntity<Map> filled = rest.postForEntity("/datasets/" + ds + "/query", Map.of(
                "filters", List.of(Map.of("field", "tier", "op", "IS_NULL"))), Map.class);
        assertEquals(0, ((Number) body(filled).get("total")).intValue());
        ResponseEntity<Map> present = rest.postForEntity("/datasets/" + ds + "/query", Map.of(
                "filters", List.of(Map.of("field", "tier", "op", "IS_NOT_NULL")),
                "aggregations", List.of(Map.of("func", "SUM", "field", "tier", "alias", "s"))), Map.class);
        assertEquals(5, ((Number) body(present).get("total")).intValue());
        assertEquals(0.0, ((Number) ((Map<?, ?>) body(present).get("aggregations")).get("s")).doubleValue());
    }
}
