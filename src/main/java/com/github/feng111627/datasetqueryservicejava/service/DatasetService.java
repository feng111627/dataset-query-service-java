package com.github.feng111627.datasetqueryservicejava.service;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.BackfillRequest;
import com.github.feng111627.datasetqueryservicejava.model.BackfillResult;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.QueryRequest;
import com.github.feng111627.datasetqueryservicejava.model.QueryResult;
import com.github.feng111627.datasetqueryservicejava.model.SchemaView;
import com.github.feng111627.datasetqueryservicejava.model.Snapshot;
import com.github.feng111627.datasetqueryservicejava.query.QueryEngine;
import com.github.feng111627.datasetqueryservicejava.schema.SchemaCompatibility;
import com.github.feng111627.datasetqueryservicejava.schema.SchemaValidator;
import com.github.feng111627.datasetqueryservicejava.store.DatasetStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Orchestrates schema registration/evolution, record writes, backfills and queries. */
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
     * Registers the initial schema or accepts a compatible evolution. The
     * compatibility check and the install are one atomic store operation, so
     * concurrent evolutions are serialized: once an evolution is accepted its
     * new fields are immediately live for writes, schema queries and queries.
     * Returns the versioned schema view including the evolution history.
     */
    public SchemaView registerSchema(DatasetSchema schema) {
        validateSchemaDeclaration(schema);
        return store.commitSchema(schema, compatibility::checkCompatible, compatibility::diff);
    }

    /** Returns the current schema with its version and evolution history. */
    public SchemaView schemaView(String dataset) {
        SchemaView view = store.schemaView(dataset);
        if (view == null) {
            throw new ClientException(ErrorCode.SCHEMA_NOT_FOUND,
                    "dataset '" + dataset + "' is not registered");
        }
        return view;
    }

    /** Validates and appends records; returns assigned sequence numbers. */
    public List<Long> writeRecords(String dataset, List<Map<String, Object>> records) {
        DatasetSchema schema = requireSchema(dataset);
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
     * Backfills one optional field on every existing record currently missing
     * it. The whole fill is one atomic store operation at a single sequence
     * point, so a reader always sees either all records pre-backfill or all
     * post-backfill. Validation failures leave the data untouched and can be
     * retried; a retry carrying the same idempotency key changes nothing and
     * never overwrites values written after the first successful backfill.
     */
    public BackfillResult backfill(String dataset, BackfillRequest request) {
        DatasetSchema schema = requireSchema(dataset);
        if (request == null || request.field() == null || request.field().isBlank()) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR,
                    "backfill must specify a 'field'");
        }
        FieldDef field = schema.field(request.field());
        if (field == null) {
            throw new ClientException(ErrorCode.UNKNOWN_FIELD,
                    "cannot backfill field '" + request.field()
                            + "': it is not declared in the current schema",
                    List.of(request.field()));
        }
        Object value = request.value();
        if (value == null) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR,
                    "backfill value must not be null; backfilling a null is indistinguishable"
                            + " from an absent field and changes no data");
        }
        if (!typeMatches(field, value)) {
            throw new ClientException(ErrorCode.TYPE_MISMATCH,
                    "backfill value has the wrong type",
                    List.of(field.name() + " (expected " + field.type()
                            + ", got " + value.getClass().getSimpleName() + ")"));
        }
        DatasetStore.BackfillOutcome outcome =
                store.backfill(dataset, field.name(), value, normalizeKey(request.idempotencyKey()));
        return new BackfillResult(field.name(), value, outcome.backfilled(),
                outcome.seq(), outcome.schemaVersion(), outcome.replayed(),
                normalizeKey(request.idempotencyKey()));
    }

    private static String normalizeKey(String key) {
        return key == null || key.isBlank() ? null : key;
    }

    private boolean typeMatches(FieldDef field, Object value) {
        return switch (field.type()) {
            case STRING -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
            case INTEGER -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long;
            case DOUBLE -> value instanceof Number;
        };
    }

    public QueryResult query(String dataset, QueryRequest request) {
        // Resolve schema and snapshot together: the current schema governs
        // validation/projection while the snapshot pins the record view.
        SchemaView view = schemaView(dataset);
        Snapshot snapshot = request != null && request.snapshotSeq() != null
                ? store.snapshot(dataset, request.snapshotSeq())
                : store.snapshot(dataset);
        return queryEngine.execute(view.schema(), view.version(), snapshot, request);
    }

    private DatasetSchema requireSchema(String dataset) {
        DatasetSchema schema = store.schemaOf(dataset);
        if (schema == null) {
            throw new ClientException(ErrorCode.SCHEMA_NOT_FOUND,
                    "dataset '" + dataset + "' is not registered");
        }
        return schema;
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
