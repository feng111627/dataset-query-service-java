package com.github.feng111627.datasetqueryservicejava.model;

/**
 * Backfill request: set {@code field} to {@code value} on every existing
 * record that currently lacks the field. An optional idempotencyKey lets a
 * retry after a failed delivery re-use the result of the first attempt.
 */
public record BackfillRequest(String field, Object value, String idempotencyKey) {
}
