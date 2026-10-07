package com.github.feng111627.datasetqueryservicejava.web;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.BackfillRequest;
import com.github.feng111627.datasetqueryservicejava.model.BackfillResult;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.QueryRequest;
import com.github.feng111627.datasetqueryservicejava.model.QueryResult;
import com.github.feng111627.datasetqueryservicejava.model.SchemaView;
import com.github.feng111627.datasetqueryservicejava.service.DatasetService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** HTTP API for schema registration/evolution, writes, backfills and queries. */
@RestController
@RequestMapping("/datasets")
public class DatasetController {

    private final DatasetService service;

    public DatasetController(DatasetService service) {
        this.service = service;
    }

    @PutMapping("/{name}/schema")
    public SchemaView registerSchema(@PathVariable String name, @RequestBody DatasetSchema schema) {
        if (schema == null) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR, "schema body must not be null");
        }
        if (schema.name() == null || schema.name().isBlank()) {
            // path variable is the authoritative dataset name
            schema = new DatasetSchema(name, schema.fields());
        } else if (!schema.name().equals(name)) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR,
                    "schema name '" + schema.name() + "' does not match path dataset '" + name + "'");
        }
        return service.registerSchema(schema);
    }

    @GetMapping("/{name}/schema")
    public SchemaView getSchema(@PathVariable String name) {
        return service.schemaView(name);
    }

    /** Evolution history: which version added/relaxed which fields and when. */
    @GetMapping("/{name}/schema/history")
    public SchemaView getSchemaHistory(@PathVariable String name) {
        return service.schemaView(name);
    }

    @PostMapping("/{name}/records")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> writeRecords(@PathVariable String name,
                                            @RequestBody Map<String, List<Map<String, Object>>> body) {
        List<Map<String, Object>> records = body == null ? null : body.get("records");
        List<Long> seqs = service.writeRecords(name, records);
        return Map.of("written", seqs.size(), "seqs", seqs);
    }

    /** Backfill one optional field on all existing records missing it. */
    @PostMapping("/{name}/backfill")
    @ResponseStatus(HttpStatus.OK)
    public BackfillResult backfill(@PathVariable String name, @RequestBody BackfillRequest request) {
        return service.backfill(name, request);
    }

    @PostMapping("/{name}/query")
    public QueryResult query(@PathVariable String name, @RequestBody(required = false) QueryRequest request) {
        return service.query(name, request);
    }
}
