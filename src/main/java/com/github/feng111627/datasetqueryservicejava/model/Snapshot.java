package com.github.feng111627.datasetqueryservicejava.model;

import java.util.List;

/**
 * A consistent, immutable view of a dataset: all records with
 * seq &lt;= highWaterMark, ordered by seq.
 */
public record Snapshot(long highWaterMark, List<StoredRecord> records) {
}
