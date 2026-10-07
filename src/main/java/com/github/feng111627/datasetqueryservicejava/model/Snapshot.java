package com.github.feng111627.datasetqueryservicejava.model;

import java.util.List;

/**
 * A consistent, immutable view of a dataset as of {@code highWaterMark}.
 *
 * <p>The view is produced by replaying the append log together with committed
 * backfill events up to the mark, so it either shows every record in its
 * pre-backfill state or every record in its post-backfill state — never a mix.
 * {@code stale} is true when a data-modifying event (a backfill) was
 * committed after a pinned token; the view itself is still fully consistent.</p>
 */
public record Snapshot(long highWaterMark, boolean stale, List<StoredRecord> records) {
}
