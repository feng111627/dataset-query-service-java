package com.github.feng111627.datasetqueryservicejava.model;

import java.time.Instant;
import java.util.List;

/**
 * Current schema plus its evolution trace: monotonically increasing version
 * number and the history of accepted changes.
 */
public record SchemaView(DatasetSchema schema,
                         long version,
                         Instant createdAt,
                         List<SchemaChange> changes) {
}
