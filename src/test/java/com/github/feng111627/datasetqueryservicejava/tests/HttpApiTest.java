package com.github.feng111627.datasetqueryservicejava.tests;

import com.github.feng111627.datasetqueryservicejava.tests.support.BusinessCoverageLogger;
import com.github.feng111627.datasetqueryservicejava.tests.support.CoversScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@CoversScenario("HTTP API: uniform error structure, client errors vs internal errors, low-level exceptions never leak")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(BusinessCoverageLogger.class)
class HttpApiTest {

    private static final Logger log = LoggerFactory.getLogger(HttpApiTest.class);

    @Autowired
    private TestRestTemplate rest;

    private static final Map<String, Object> SCHEMA_BODY = Map.of(
            "fields", List.of(
                    Map.of("name", "id", "type", "INTEGER", "required", true),
                    Map.of("name", "price", "type", "DOUBLE", "required", true),
                    Map.of("name", "label", "type", "STRING", "required", false)
            )
    );

    private void register(String dataset) {
        ResponseEntity<Map> response = rest.exchange("/datasets/" + dataset + "/schema",
                HttpMethod.PUT, json(SCHEMA_BODY), Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "schema registration failed: " + response.getBody());
    }

    private static HttpEntity<?> json(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static HttpEntity<String> rawJson(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(ResponseEntity<Map> response) {
        Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
        assertNotNull(error, "error responses must use the uniform {error:{...}} structure");
        assertNotNull(error.get("code"));
        assertNotNull(error.get("message"));
        return error;
    }

    @Test
    @DisplayName("happy path: register schema, write records, query with filter/projection/aggregation")
    void happyPath() {
        String ds = "http_happy";
        register(ds);

        ResponseEntity<Map> write = rest.postForEntity("/datasets/" + ds + "/records",
                Map.of("records", List.of(
                        Map.of("id", 1, "price", 10.0, "label", "a"),
                        Map.of("id", 2, "price", 20.0, "label", "b"),
                        Map.of("id", 3, "price", 30.0)
                )), Map.class);
        assertEquals(HttpStatus.CREATED, write.getStatusCode());
        assertEquals(3, write.getBody().get("written"));

        ResponseEntity<Map> query = rest.postForEntity("/datasets/" + ds + "/query",
                Map.of(
                        "filters", List.of(Map.of("field", "price", "op", "GTE", "value", 20)),
                        "columns", List.of("id", "price"),
                        "aggregations", List.of(Map.of("func", "SUM", "field", "price", "alias", "total_price")),
                        "sort", List.of(Map.of("field", "price", "ascending", false))
                ), Map.class);
        assertEquals(HttpStatus.OK, query.getStatusCode());
        Map<String, Object> body = query.getBody();
        assertEquals(2, ((Number) body.get("total")).intValue());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        assertEquals(List.of(3, 2), rows.stream().map(r -> r.get("id")).toList());
        assertEquals(50.0, ((Number) ((Map<String, Object>) body.get("aggregations")).get("total_price")).doubleValue());
        assertNotNull(body.get("snapshotSeq"), "query must return a snapshot token for pagination");
    }

    @Test
    @DisplayName("invalid record -> 400 with uniform error body and distinguishable code")
    void invalidRecordRejected() {
        String ds = "http_invalid";
        register(ds);

        ResponseEntity<Map> missing = rest.postForEntity("/datasets/" + ds + "/records",
                Map.of("records", List.of(Map.of("id", 1))), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertEquals("MISSING_FIELD", errorOf(missing).get("code"));

        ResponseEntity<Map> mismatch = rest.postForEntity("/datasets/" + ds + "/records",
                Map.of("records", List.of(Map.of("id", 1, "price", "cheap"))), Map.class);
        assertEquals("TYPE_MISMATCH", errorOf(mismatch).get("code"));

        ResponseEntity<Map> unknown = rest.postForEntity("/datasets/" + ds + "/records",
                Map.of("records", List.of(Map.of("id", 1, "price", 1.0, "ghost", 1))), Map.class);
        assertEquals("UNKNOWN_FIELD", errorOf(unknown).get("code"));

        // a rejected batch must be all-or-nothing
        ResponseEntity<Map> after = rest.postForEntity("/datasets/" + ds + "/query", Map.of(), Map.class);
        assertEquals(0, ((Number) after.getBody().get("total")).intValue());
    }

    @Test
    @DisplayName("incompatible schema re-registration -> 409 INCOMPATIBLE_SCHEMA")
    void incompatibleSchemaRejected() {
        String ds = "http_schema";
        register(ds);

        ResponseEntity<Map> incompatible = rest.exchange("/datasets/" + ds + "/schema",
                HttpMethod.PUT, json(Map.of("fields", List.of(
                        Map.of("name", "id", "type", "STRING", "required", true)
                ))), Map.class);
        assertEquals(HttpStatus.CONFLICT, incompatible.getStatusCode());
        assertEquals("INCOMPATIBLE_SCHEMA", errorOf(incompatible).get("code"));

        // compatible evolution (add optional field) is accepted
        ResponseEntity<Map> compatible = rest.exchange("/datasets/" + ds + "/schema",
                HttpMethod.PUT, json(Map.of("fields", List.of(
                        Map.of("name", "id", "type", "INTEGER", "required", true),
                        Map.of("name", "price", "type", "DOUBLE", "required", true),
                        Map.of("name", "label", "type", "STRING", "required", false),
                        Map.of("name", "extra", "type", "BOOLEAN", "required", false)
                ))), Map.class);
        assertEquals(HttpStatus.OK, compatible.getStatusCode());
    }

    @Test
    @DisplayName("unknown dataset -> 404 SCHEMA_NOT_FOUND with uniform error body")
    void unknownDataset() {
        ResponseEntity<Map> response = rest.postForEntity("/datasets/no_such_dataset/query", Map.of(), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("SCHEMA_NOT_FOUND", errorOf(response).get("code"));
    }

    @Test
    @DisplayName("malformed JSON body -> 400 BAD_REQUEST, parser internals never leak")
    void malformedJsonDoesNotLeakInternals() {
        register("http_badjson");
        ResponseEntity<Map> response = rest.exchange("/datasets/http_badjson/records",
                HttpMethod.POST, rawJson("{not valid json"), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> error = errorOf(response);
        assertEquals("BAD_REQUEST", error.get("code"));
        String body = String.valueOf(response.getBody());
        assertFalse(body.contains("JsonParseException"), "low-level parser exception leaked: " + body);
        assertFalse(body.contains("jackson"), "parser library name leaked: " + body);
        log.info("[COVERAGE] malformed JSON returned uniform 400 body without parser internals: {}", error);
    }

    @Test
    @DisplayName("snapshot token keeps multi-page reads stable over HTTP while writes continue")
    void snapshotTokenOverHttp() {
        String ds = "http_paging";
        register(ds);
        for (int i = 1; i <= 25; i++) {
            rest.postForEntity("/datasets/" + ds + "/records",
                    Map.of("records", List.of(Map.of("id", i, "price", i * 1.0))), Map.class);
        }

        ResponseEntity<Map> first = rest.postForEntity("/datasets/" + ds + "/query",
                Map.of("offset", 0, "limit", 10), Map.class);
        long token = ((Number) first.getBody().get("snapshotSeq")).longValue();
        assertEquals(25, ((Number) first.getBody().get("total")).intValue());

        // more writes after the first page
        rest.postForEntity("/datasets/" + ds + "/records",
                Map.of("records", List.of(Map.of("id", 26, "price", 26.0))), Map.class);

        ResponseEntity<Map> second = rest.postForEntity("/datasets/" + ds + "/query",
                Map.of("offset", 10, "limit", 10, "snapshotSeq", token), Map.class);
        assertEquals(token, ((Number) second.getBody().get("snapshotSeq")).longValue());
        assertEquals(25, ((Number) second.getBody().get("total")).intValue(),
                "pinned snapshot must keep the total stable across pages");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) second.getBody().get("rows");
        assertEquals(10, rows.size());
        assertEquals(11, rows.get(0).get("id"));
    }
}
