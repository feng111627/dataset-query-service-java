package com.github.feng111627.datasetqueryservicejava.schema;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates records against a declared schema. */
@Component
public class SchemaValidator {

    /**
     * Validates one record. Throws ClientException with a distinguishable
     * ErrorCode (MISSING_FIELD / TYPE_MISMATCH / UNKNOWN_FIELD) on failure.
     * All problems of the record are collected into the exception details.
     */
    public void validate(DatasetSchema schema, Map<String, Object> record) {
        if (record == null) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR, "record must be a JSON object");
        }

        List<String> missing = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        for (FieldDef field : schema.fields()) {
            Object value = record.get(field.name());
            if (value == null) {
                if (field.required()) {
                    missing.add(field.name());
                }
                continue;
            }
            if (!typeMatches(field, value)) {
                mismatched.add(field.name() + " (expected " + field.type()
                        + ", got " + value.getClass().getSimpleName() + ")");
            }
        }
        for (String key : record.keySet()) {
            if (schema.field(key) == null) {
                unknown.add(key);
            }
        }

        // Report the first (most severe) category as the error code so callers
        // can distinguish causes; all problems are listed in details.
        if (!missing.isEmpty()) {
            throw new ClientException(ErrorCode.MISSING_FIELD,
                    "record is missing required fields", missing);
        }
        if (!mismatched.isEmpty()) {
            throw new ClientException(ErrorCode.TYPE_MISMATCH,
                    "record has fields with mismatched types", mismatched);
        }
        if (!unknown.isEmpty()) {
            throw new ClientException(ErrorCode.UNKNOWN_FIELD,
                    "record contains fields not declared in the schema", unknown);
        }
    }

    /**
     * Validates a single value against a field declaration. Throws
     * ClientException(TYPE_MISMATCH) when the value's type does not match.
     */
    public void validateFieldValue(FieldDef field, Object value) {
        if (value == null) {
            return; // null means "absent" and is always type-compatible
        }
        if (!typeMatches(field, value)) {
            throw new ClientException(ErrorCode.TYPE_MISMATCH,
                    "value does not match the declared type of field '" + field.name() + "'",
                    List.of(field.name() + " (expected " + field.type()
                            + ", got " + value.getClass().getSimpleName() + ")"));
        }
    }

    private boolean typeMatches(FieldDef field, Object value) {
        return switch (field.type()) {
            case STRING -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
            // INTEGER accepts any integral number, but rejects fractional values.
            case INTEGER -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long;
            // DOUBLE accepts any JSON number (Jackson maps to Integer/Long/Double/...).
            case DOUBLE -> value instanceof Number;
        };
    }
}
