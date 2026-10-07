package com.github.feng111627.datasetqueryservicejava.model;

import java.time.Instant;

/**
 * One accepted, traceable schema evolution step. Each evolution request that
 * actually changes the schema gets a strictly increasing version number.
 */
public record SchemaChange(long version,
                           ChangeType changeType,
                           String fieldName,
                           FieldType fieldType,
                           boolean required,
                           Instant effectiveAt) {

    public enum ChangeType {
        /** A new optional field was added. */
        FIELD_ADDED,
        /** A field was relaxed from required to optional. */
        REQUIRED_TO_OPTIONAL
    }
}
