package com.github.feng111627.datasetqueryservicejava.model;

/**
 * Result of a backfill. {@code replayed} is true when the response was served
 * from a previous successful attempt with the same idempotency key (and no
 * data was changed this time).
 */
public record BackfillResult(String field,
                             Object value,
                             long backfilled,
                             long seq,
                             long schemaVersion,
                             boolean replayed,
                             String idempotencyKey) {
}
