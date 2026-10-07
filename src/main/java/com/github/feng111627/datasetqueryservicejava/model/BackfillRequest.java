package com.github.feng111627.datasetqueryservicejava.model;

/**
 * Request to fill {@code field} with {@code value} on all existing records
 * that do not already carry a non-null value for it.
 *
 * <p>{@code requestId} is a client-chosen idempotency key: retrying a
 * backfill with the same id replays the original outcome without touching
 * any data, so a retry can neither double-apply nor modify records written
 * after the original backfill.</p>
 */
public record BackfillRequest(String field, Object value, String requestId) {
}
