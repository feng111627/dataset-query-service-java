package com.github.feng111627.datasetqueryservicejava.web;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.QueryRequest;
import com.github.feng111627.datasetqueryservicejava.model.QueryResult;
import com.github.feng111627.datasetqueryservicejava.service.DatasetService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** HTTP API for schema registration, record writes and queries. */
@RestController
@RequestMapping("/datasets")
public class DatasetController {

    private final DatasetService service;

    public DatasetController(DatasetService service) {
        this.service = service;
    }

    @PutMapping("/{name}/schema")
    public DatasetSchema registerSchema(@PathVariable String name, @RequestBody DatasetSchema schema) {
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
    public DatasetSchema getSchema(@PathVariable String name) {
        return service.schemaOf(name);
    }

    @PostMapping("/{name}/records")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> writeRecords(@PathVariable String name,
                                            @RequestBody Map<String, List<Map<String, Object>>> body) {
        List<Map<String, Object>> records = body == null ? null : body.get("records");
        List<Long> seqs = service.writeRecords(name, records);
        return Map.of("written", seqs.size(), "seqs", seqs);
    }

    @PostMapping("/{name}/query")
    public QueryResult query(@PathVariable String name, @RequestBody(required = false) QueryRequest request) {
        return service.query(name, request);
    }
}
