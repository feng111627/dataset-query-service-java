package com.github.feng111627.datasetqueryservicejava.store;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.Snapshot;
import com.github.feng111627.datasetqueryservicejava.model.StoredRecord;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory storage with a single append/backfill event log per dataset.
 *
 * <p>Concurrency model: every state-changing operation on a dataset (record
 * appends, backfills and schema evolution) runs while holding the dataset
 * monitor, so each operation takes effect atomically and operations are
 * totally ordered. Every committed operation consumes one sequence number
 * from a single monotonic counter. A snapshot is the materialized state of
 * replaying all events up to a high-water mark:</p>
 * <ul>
 *   <li>record batches are {@code Append} events carrying one seq per record;</li>
 *   <li>backfills are single {@code Backfill} events; replay fills the field
 *       only on records that are still missing it, so old snapshot tokens keep
 *       showing the consistent pre-backfill state, while later marks show the
 *       post-backfill state;</li>
 *   <li>schema metadata is stored under the same lock, so once an evolution is
 *       committed the new fields are immediately visible to writes and reads,
 *       and no observer can see a partially evolved schema.</li>
 * </ul>
 */
@Repository
public class DatasetStore {

    /** A committed record-append batch event. */
    private static final class AppendEvent {
        final long seq;
        final Map<String, Object> values;

        AppendEvent(long seq, Map<String, Object> values) {
            this.seq = seq;
            this.values = values;
        }
    }

    /** A committed backfill event: fill one field on records missing it. */
    private static final class BackfillEvent {
        final long seq;
        final String field;
        final Object value;

        BackfillEvent(long seq, String field, Object value) {
            this.seq = seq;
            this.field = field;
            this.value = value;
        }
    }

    /** Any kind of event carrying a sequence number. */
    private static final class LogEvent {
        final long seq;
        final AppendEvent append;
        final BackfillEvent backfill;

        LogEvent(AppendEvent append) {
            this.seq = append.seq;
            this.append = append;
            this.backfill = null;
        }

        LogEvent(BackfillEvent backfill) {
            this.seq = backfill.seq;
            this.append = null;
            this.backfill = backfill;
        }
    }

    /** Mutable row used while replaying events into a snapshot. */
    private static final class LiveRow {
        final long seq;
        final Map<String, Object> values;

        LiveRow(long seq, Map<String, Object> values) {
            this.seq = seq;
            this.values = values;
        }
    }

    /** Result of committing a backfill. */
    public record BackfillOutcome(long seq, long backfilled, boolean replayed, long schemaVersion) {
    }

    private static final class Dataset {
        volatile DatasetSchema schema;
        long schemaVersion = 1;
        final java.time.Instant createdAt = java.time.Instant.now();
        final java.util.List<com.github.feng111627.datasetqueryservicejava.model.SchemaChange> changes =
                new ArrayList<>();
        final AtomicLong seq = new AtomicLong(0);
        final List<LogEvent> events = new ArrayList<>();
        /** idempotency key -> (backfill seq, number of records actually filled) for retries. */
        final Map<String, long[]> backfillKeys = new HashMap<>();

        Dataset(DatasetSchema schema) {
            this.schema = schema;
        }
    }

    private final Map<String, Dataset> datasets = new ConcurrentHashMap<>();

    /**
     * Atomically registers the initial schema or accepts an evolved schema.
     * The whole check-and-commit runs under the dataset lock, so concurrent
     * evolutions are serialized: each is evaluated against the committed
     * predecessor and either takes effect completely or not at all.
     *
     * @param checker compatibility check between the committed and incoming
     *                schema; thrown ClientException aborts the evolution
     * @param changes accepted changes to record when the incoming schema differs
     */
    public com.github.feng111627.datasetqueryservicejava.model.SchemaView commitSchema(
            DatasetSchema incoming,
            java.util.function.BiConsumer<DatasetSchema, DatasetSchema> checker,
            java.util.function.BiFunction<DatasetSchema, DatasetSchema,
                    List<com.github.feng111627.datasetqueryservicejava.model.SchemaChange>> diff) {
        Dataset fresh = datasets.computeIfAbsent(incoming.name(), k -> new Dataset(incoming));
        synchronized (fresh) {
            DatasetSchema current = fresh.schema;
            // Concurrent first-time registrations with differing field sets still
            // go through the compatibility gate rather than being dropped.
            if (!fieldNamesAndDefsEqual(current, incoming)) {
                checker.accept(current, incoming);
                List<com.github.feng111627.datasetqueryservicejava.model.SchemaChange> newChanges =
                        diff.apply(current, incoming);
                fresh.schema = incoming;
                if (!newChanges.isEmpty()) {
                    fresh.schemaVersion++;
                    java.time.Instant now = java.time.Instant.now();
                    for (com.github.feng111627.datasetqueryservicejava.model.SchemaChange change : newChanges) {
                        fresh.changes.add(new com.github.feng111627.datasetqueryservicejava.model.SchemaChange(
                                fresh.schemaVersion, change.changeType(), change.fieldName(),
                                change.fieldType(), change.required(), now));
                    }
                }
            }
            return schemaViewOf(fresh);
        }
    }

    private static boolean fieldNamesAndDefsEqual(DatasetSchema a, DatasetSchema b) {
        return a.fields().equals(b.fields());
    }

    /** Returns the versioned schema view, or null if the dataset is unknown. */
    public com.github.feng111627.datasetqueryservicejava.model.SchemaView schemaView(String dataset) {
        Dataset ds = datasets.get(dataset);
        if (ds == null) {
            return null;
        }
        synchronized (ds) {
            return schemaViewOf(ds);
        }
    }

    private static com.github.feng111627.datasetqueryservicejava.model.SchemaView schemaViewOf(Dataset ds) {
        return new com.github.feng111627.datasetqueryservicejava.model.SchemaView(
                ds.schema, ds.schemaVersion, ds.createdAt, List.copyOf(ds.changes));
    }

    /** Registers a dataset with its initial schema. Returns the effective schema. */
    public DatasetSchema registerSchema(DatasetSchema schema) {
        Dataset ds = datasets.computeIfAbsent(schema.name(), k -> new Dataset(schema));
        return ds.schema;
    }

    /** Returns the schema of a dataset, or null if unknown. */
    public DatasetSchema schemaOf(String dataset) {
        Dataset ds = datasets.get(dataset);
        return ds == null ? null : ds.schema;
    }

    /** Appends already-validated records; returns assigned sequence numbers. */
    public List<Long> append(String dataset, List<Map<String, Object>> records) {
        Dataset ds = requireDataset(dataset);
        List<Long> assigned = new ArrayList<>(records.size());
        synchronized (ds) {
            for (Map<String, Object> record : records) {
                long seq = ds.seq.incrementAndGet();
                ds.events.add(new LogEvent(new AppendEvent(seq, normalize(record))));
                assigned.add(seq);
            }
        }
        return assigned;
    }

    /**
     * Drops explicit null entries. Validation already treats an explicit null
     * exactly like an absent field; storing them under one representation keeps
     * that identity guarantee in reads too.
     */
    private static Map<String, Object> normalize(Map<String, Object> record) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            if (entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(copy);
    }

    /**
     * Commits a backfill under the dataset lock. Applies the fill to exactly
     * the records currently missing the field, all at one sequence point.
     * If the idempotency key was used by a previous committed backfill, no
     * state changes and the prior result is replayed.
     */
    public BackfillOutcome backfill(String dataset, String field, Object value, String idempotencyKey) {
        Dataset ds = requireDataset(dataset);
        synchronized (ds) {
            if (idempotencyKey != null) {
                long[] prior = ds.backfillKeys.get(idempotencyKey);
                if (prior != null) {
                    return new BackfillOutcome(prior[0], prior[1], true, ds.schemaVersion);
                }
            }
            long backfilled = countMissingAt(ds, ds.seq.get(), field);
            long seq = ds.seq.incrementAndGet();
            ds.events.add(new LogEvent(new BackfillEvent(seq, field, immutableValue(value))));
            if (idempotencyKey != null) {
                ds.backfillKeys.put(idempotencyKey, new long[]{seq, backfilled});
            }
            return new BackfillOutcome(seq, backfilled, false, ds.schemaVersion);
        }
    }

    private static Object immutableValue(Object value) {
        // values are scalar JSON primitives; Map.copyOf on a 1-entry map freezes it
        return Map.of("v", value).get("v");
    }

    /** Number of records missing a field in the state as of a given mark. */
    private long countMissingAt(Dataset ds, long mark, String field) {
        long missing = 0;
        for (LiveRow row : replay(ds, mark)) {
            if (!row.values.containsKey(field)) {
                missing++;
            }
        }
        return missing;
    }

    /** Takes a snapshot at the current high-water mark. */
    public Snapshot snapshot(String dataset) {
        Dataset ds = requireDataset(dataset);
        synchronized (ds) {
            long mark = ds.seq.get();
            return new Snapshot(mark, false, materialize(ds, mark));
        }
    }

    /**
     * Re-reads the snapshot identified by a previously returned high-water
     * mark. Event replay makes the same mark always yield the same materialized
     * records, regardless of later writes or backfills. When a backfill was
     * committed after the mark the snapshot is flagged stale (the view itself
     * remains fully consistent).
     */
    public Snapshot snapshot(String dataset, long highWaterMark) {
        Dataset ds = requireDataset(dataset);
        synchronized (ds) {
            long current = ds.seq.get();
            if (highWaterMark > current) {
                throw new ClientException(ErrorCode.VALIDATION_ERROR,
                        "snapshotSeq " + highWaterMark + " is ahead of the dataset (current "
                                + current + "); it does not belong to dataset '" + dataset + "'");
            }
            boolean stale = hasBackfillAfter(ds, highWaterMark);
            return new Snapshot(highWaterMark, stale, materialize(ds, highWaterMark));
        }
    }

    private boolean hasBackfillAfter(Dataset ds, long mark) {
        for (LogEvent event : ds.events) {
            if (event.seq > mark && event.backfill != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replays every event up to the mark in seq order to materialize rows.
     * Append events create rows; backfill events fill missing fields on
     * exactly the rows visible at that point.
     */
    private List<LiveRow> replay(Dataset ds, long mark) {
        List<LiveRow> rows = new ArrayList<>();
        for (LogEvent event : ds.events) {
            if (event.seq > mark) {
                break;
            }
            if (event.append != null) {
                rows.add(new LiveRow(event.append.seq, new HashMap<>(event.append.values)));
            } else {
                BackfillEvent b = event.backfill;
                for (LiveRow row : rows) {
                    row.values.putIfAbsent(b.field, b.value);
                }
            }
        }
        return rows;
    }

    private List<StoredRecord> materialize(Dataset ds, long mark) {
        List<StoredRecord> frozen = new ArrayList<>();
        for (LiveRow row : replay(ds, mark)) {
            frozen.add(new StoredRecord(row.seq, Map.copyOf(row.values)));
        }
        return frozen;
    }

    private Dataset requireDataset(String dataset) {
        Dataset ds = datasets.get(dataset);
        if (ds == null) {
            throw new ClientException(ErrorCode.SCHEMA_NOT_FOUND,
                    "dataset '" + dataset + "' is not registered");
        }
        return ds;
    }
}
