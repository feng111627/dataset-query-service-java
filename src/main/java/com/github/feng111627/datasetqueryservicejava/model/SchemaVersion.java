package com.github.feng111627.datasetqueryservicejava.model;

import java.time.Instant;
import java.util.List;

/**
 * One accepted entry in a dataset's schema history: the initial registration
 * (version 1) or a compatible evolution. {@code addedFields} are newly
 * introduced optional fields, {@code relaxedFields} are fields that changed
 * from required to optional in this version.
 */
public record SchemaVersion(
        long version,
        Instant effectiveAt,
        List<String> addedFields,
        List<String> relaxedFields) {
}
