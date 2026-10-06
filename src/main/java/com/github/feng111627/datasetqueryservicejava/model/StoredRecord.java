package com.github.feng111627.datasetqueryservicejava.model;

import java.util.Map;

/** A stored record with a monotonically increasing sequence number. */
public record StoredRecord(long seq, Map<String, Object> values) {
}
