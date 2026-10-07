package com.github.feng111627.datasetqueryservicejava.service;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.BackfillRequest;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.QueryRequest;
import com.github.feng111627.datasetqueryservicejava.model.QueryResult;
import com.github.feng111627.datasetqueryservicejava.model.SchemaVersion;
import com.github.feng111627.datasetqueryservicejava.model.Snapshot;
import com.github.feng111627.datasetqueryservicejava.query.QueryEngine;
import com.github.feng111627.datasetqueryservicejava.schema.SchemaCompatibility;
import com.github.feng111627.datasetqueryservicejava.schema.SchemaValidator;
import com.github.feng111627.datasetqueryservicejava.store.DatasetStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Orchestrates schema registration, record writes and queries. */
@Service
public class DatasetService {

    private final DatasetStore store;
    private final SchemaValidator validator;
    private final SchemaCompatibility compatibility;
    private final QueryEngine queryEngine;

    public DatasetService(DatasetStore store, SchemaValidator validator,
                          SchemaCompatibility compatibility, QueryEngine queryEngine) {
        this.store = store;
        this.validator = validator;
        this.compatibility = compatibility;
        this.queryEngine = queryEngine;
    }

    /**
     * Registers a dataset schema, or applies a compatible evolution of an
     * existing one. An accepted evolution takes effect immediately: later
     * writes may carry the new fields, schema lookups return the latest
     * fields, and the new fields can be selected, filtered and aggregated.
     * Records written before the evolution are untouched and simply have no
     * value for the new fields.
     */
    public DatasetSchema registerSchema(DatasetSchema schema) {
        validateSchemaDeclaration(schema);
        return store.registerOrEvolve(schema, compatibility::checkCompatible);
    }

    public DatasetSchema schemaOf(String dataset) {
        DatasetSchema schema = store.schemaOf(dataset);
        if (schema == null) {
            throw new ClientException(ErrorCode.SCHEMA_NOT_FOUND,
                    "dataset '" + dataset + "' is not registered");
        }
        return schema;
    }

    /** Returns the accepted schema evolution history (version 1 = initial). */
    public List<SchemaVersion> schemaHistory(String dataset) {
        schemaOf(dataset); // 404 for unknown datasets
        return store.schemaHistory(dataset);
    }

    /** Validates and appends records; returns assigned sequence numbers. */
    public List<Long> writeRecords(String dataset, List<Map<String, Object>> records) {
        DatasetSchema schema = schemaOf(dataset);
        if (records == null || records.isEmpty()) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR,
                    "request body must contain a non-empty 'records' array");
        }
        // Validate every record before writing anything: a batch is all-or-nothing.
        List<String> allProblems = new ArrayList<>();
        ErrorCode firstCode = null;
        for (int i = 0; i < records.size(); i++) {
            try {
                validator.validate(schema, records.get(i));
            } catch (ClientException ex) {
                if (firstCode == null) {
                    firstCode = ex.code();
                }
                for (String detail : ex.details()) {
                    allProblems.add("record[" + i + "]: " + detail);
                }
                if (ex.details().isEmpty()) {
                    allProblems.add("record[" + i + "]: " + ex.getMessage());
                }
            }
        }
        if (firstCode != null) {
            throw new ClientException(firstCode,
                    "batch rejected: " + allProblems.size() + " validation problem(s)",
                    allProblems);
        }
        return store.append(dataset, records);
    }

    /**
     * Backfills a default value for a field onto all currently existing
     * records that lack a non-null value for it. The operation is atomic
     * with respect to concurrent writes and reads: any query sees either the
     * fully pre-backfill or the fully post-backfill state. Snapshot tokens
     * issued before a data-changing backfill are invalidated (SNAPSHOT_EXPIRED)
     * so pages never mix pre- and post-backfill records.
     *
     * <p>Retries are safe: the same requestId replays the stored outcome
     * without re-applying anything.</p>
     */
    public Map<String, Object> backfill(String dataset, BackfillRequest request) {
        DatasetSchema schema = schemaOf(dataset);
        if (request == null) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR,
                    "request body must contain 'field', 'value' and 'requestId'");
        }
        List<String> problems = new ArrayList<>();
        FieldDef field = request.field() == null ? null : schema.field(request.field());
        if (request.field() == null || request.field().isBlank()) {
            problems.add("field must not be blank");
        } else if (field == null) {
            problems.add("unknown field: " + request.field());
        }
        if (request.value() == null) {
            problems.add("value must not be null (backfilling null is a no-op)");
        }
        if (request.requestId() == null || request.requestId().isBlank()) {
            problems.add("requestId must not be blank (it makes retries idempotent)");
        }
        if (!problems.isEmpty()) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR, "invalid backfill request", problems);
        }
        validator.validateFieldValue(field, request.value());

        DatasetStore.BackfillOutcome outcome =
                store.backfill(dataset, request.field(), request.value(), request.requestId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("field", outcome.field());
        result.put("value", outcome.value());
        result.put("backfilled", outcome.backfilled());
        result.put("highWaterMark", outcome.highWaterMark());
        result.put("replayed", outcome.replayed());
        return result;
    }

    public QueryResult query(String dataset, QueryRequest request) {
        DatasetSchema schema = schemaOf(dataset);
        // A snapshotSeq token pins the read to the same snapshot across pages;
        // without one, a fresh snapshot is taken for this request.
        Snapshot snapshot = request != null && request.snapshotSeq() != null
                ? store.snapshot(dataset, request.snapshotSeq())
                : store.snapshot(dataset);
        return queryEngine.execute(schema, snapshot, request);
    }

    private void validateSchemaDeclaration(DatasetSchema schema) {
        List<String> problems = new ArrayList<>();
        if (schema == null) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR, "schema must not be null");
        }
        if (schema.name() == null || schema.name().isBlank()) {
            problems.add("schema name must not be blank");
        }
        if (schema.fields() == null || schema.fields().isEmpty()) {
            problems.add("schema must declare at least one field");
        } else {
            Set<String> seen = new HashSet<>();
            for (FieldDef field : schema.fields()) {
                if (field.name() == null || field.name().isBlank()) {
                    problems.add("field name must not be blank");
                } else if (!seen.add(field.name())) {
                    problems.add("duplicate field name: " + field.name());
                }
                if (field.type() == null) {
                    problems.add("field '" + field.name() + "' is missing a type");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR, "invalid schema", problems);
        }
    }
}
