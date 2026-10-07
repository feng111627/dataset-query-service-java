package com.github.feng111627.datasetqueryservicejava.store;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.SchemaVersion;
import com.github.feng111627.datasetqueryservicejava.model.Snapshot;
import com.github.feng111627.datasetqueryservicejava.model.StoredRecord;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * In-memory, append-only dataset storage.
 *
 * <p>Concurrency model: each dataset has a monotonically increasing sequence
 * counter. {@link #append} assigns sequence numbers atomically under a
 * per-dataset lock, so a batch becomes visible all-at-once in seq order.
 * {@link #snapshot} reads the current sequence high-water mark and collects
 * all records up to it; records written concurrently afterwards are excluded.
 * {@link #snapshot(String, long)} re-reads the same logical point in time,
 * so multi-page pagination can share one snapshot via a snapshot token and
 * never sees duplicates or gaps even while other threads keep writing.</p>
 *
 * <p>Backfill: {@link #backfill} rewrites the values of all records that
 * exist at backfill time, in one atomic step under the dataset lock — any
 * reader sees either the fully pre-backfill or the fully post-backfill
 * state. A backfill that changes data bumps the dataset's data epoch.
 * Snapshot tokens encode the epoch they were taken in; a token from an
 * older epoch is rejected with SNAPSHOT_EXPIRED instead of silently mixing
 * pre- and post-backfill data into one result.</p>
 */
@Repository
public class DatasetStore {

    /** Snapshot tokens encode (dataEpoch, highWaterMark) as one opaque long. */
    private static final long EPOCH_BASE = 1_000_000_000_000L;

    /** Outcome of a backfill: how many records were filled, up to which
     * high-water mark, and whether this call was an idempotent replay. */
    public record BackfillOutcome(String field, Object value, long highWaterMark,
                                  int backfilled, boolean replayed) {
    }

    private static class Dataset {
        volatile DatasetSchema schema;
        final AtomicLong seq = new AtomicLong(0);
        final List<StoredRecord> records = new ArrayList<>();
        final List<SchemaVersion> history = new ArrayList<>();
        long epoch = 0;
        final Map<String, BackfillOutcome> backfills = new java.util.HashMap<>();

        Dataset(DatasetSchema schema) {
            this.schema = schema;
        }
    }

    private final Map<String, Dataset> datasets = new ConcurrentHashMap<>();

    /**
     * Registers a new dataset, or atomically checks and applies a compatible
     * evolution of an existing one. The compatibility check and the schema
     * swap happen under the same per-dataset lock, so concurrent evolutions
     * are serialized: each of them either applies completely (new version
     * visible to every later reader) or fails without changing anything.
     *
     * <p>The initial registration is version 1; every accepted evolution
     * increments the version and appends a {@link SchemaVersion} entry to the
     * dataset's history. Re-registering an identical schema is a no-op.</p>
     *
     * @return the schema now in effect
     */
    public DatasetSchema registerOrEvolve(DatasetSchema incoming,
                                          BiConsumer<DatasetSchema, DatasetSchema> compatibilityCheck) {
        Dataset ds = datasets.computeIfAbsent(incoming.name(), k -> {
            Dataset created = new Dataset(incoming);
            created.history.add(new SchemaVersion(1, Instant.now(),
                    incoming.fields().stream().map(FieldDef::name).toList(), List.of()));
            return created;
        });
        synchronized (ds) {
            if (ds.schema == incoming) {
                return ds.schema; // this call created the dataset
            }
            compatibilityCheck.accept(ds.schema, incoming);
            List<String> added = new ArrayList<>();
            List<String> relaxed = new ArrayList<>();
            for (FieldDef field : incoming.fields()) {
                FieldDef old = ds.schema.field(field.name());
                if (old == null) {
                    added.add(field.name());
                } else if (old.required() && !field.required()) {
                    relaxed.add(field.name());
                }
            }
            if (added.isEmpty() && relaxed.isEmpty()) {
                return ds.schema; // identical re-registration: no-op, no version bump
            }
            ds.schema = incoming;
            ds.history.add(new SchemaVersion(ds.history.size() + 1L, Instant.now(),
                    List.copyOf(added), List.copyOf(relaxed)));
            return incoming;
        }
    }

    /** Returns the schema of a dataset, or null if unknown. */
    public DatasetSchema schemaOf(String dataset) {
        Dataset ds = datasets.get(dataset);
        return ds == null ? null : ds.schema;
    }

    /** Returns the accepted schema history (version 1 = initial registration). */
    public List<SchemaVersion> schemaHistory(String dataset) {
        Dataset ds = requireDataset(dataset);
        synchronized (ds) {
            return List.copyOf(ds.history);
        }
    }

    /** Appends already-validated records; returns assigned sequence numbers. */
    public List<Long> append(String dataset, List<Map<String, Object>> records) {
        Dataset ds = requireDataset(dataset);
        List<Long> assigned = new ArrayList<>(records.size());
        synchronized (ds) {
            for (Map<String, Object> record : records) {
                long seq = ds.seq.incrementAndGet();
                ds.records.add(new StoredRecord(seq, Map.copyOf(record)));
                assigned.add(seq);
            }
        }
        return assigned;
    }

    /** Takes a snapshot at the current high-water mark. */
    public Snapshot snapshot(String dataset) {
        Dataset ds = requireDataset(dataset);
        synchronized (ds) {
            return snapshotAt(ds, ds.seq.get());
        }
    }

    /**
     * Re-reads the snapshot identified by a previously returned token.
     * Because storage is append-only between backfills, the same token always
     * yields the same records, regardless of concurrent writes. A token
     * issued before a data-changing backfill belongs to an older epoch and
     * is rejected with SNAPSHOT_EXPIRED: callers must start a fresh snapshot
     * rather than silently mix pre- and post-backfill records.
     */
    public Snapshot snapshot(String dataset, long token) {
        Dataset ds = requireDataset(dataset);
        synchronized (ds) {
            long tokenEpoch = token / EPOCH_BASE;
            long highWaterMark = token % EPOCH_BASE;
            if (token < 0 || tokenEpoch != ds.epoch) {
                throw new ClientException(ErrorCode.SNAPSHOT_EXPIRED,
                        "snapshot token " + token + " was invalidated by a backfill;"
                                + " start a new snapshot (omit snapshotSeq) to read current data");
            }
            long current = ds.seq.get();
            if (highWaterMark > current) {
                throw new ClientException(ErrorCode.VALIDATION_ERROR,
                        "snapshotSeq " + token + " is ahead of the dataset (current "
                                + current + "); it does not belong to dataset '" + dataset + "'");
            }
            return snapshotAt(ds, highWaterMark);
        }
    }

    private Snapshot snapshotAt(Dataset ds, long highWaterMark) {
        // Records are appended in seq order; the prefix up to the
        // high-water mark is exactly the snapshot. The returned token
        // encodes the current data epoch so a later backfill invalidates it.
        List<StoredRecord> result = new ArrayList<>();
        for (StoredRecord r : ds.records) {
            if (r.seq() > highWaterMark) {
                break;
            }
            result.add(r);
        }
        return new Snapshot(ds.epoch * EPOCH_BASE + highWaterMark, List.copyOf(result));
    }

    /**
     * Fills {@code field} with {@code value} on every record that exists at
     * this moment and does not already carry a non-null value for it.
     *
     * <p>Atomicity: the rewritten record list is built first and swapped in
     * under the dataset lock, so the operation is all-or-nothing and any
     * concurrent reader sees a consistent before/after state. Writes are
     * serialized on the same lock: a record is either written before the
     * backfill (and included) or after it (and never touched).</p>
     *
     * <p>Idempotency: {@code requestId} memoizes the outcome. A retry with
     * the same id and the same field/value replays the stored outcome
     * without touching data — it can neither double-apply nor modify records
     * written after the original backfill. Reusing the id with different
     * parameters is rejected.</p>
     */
    public BackfillOutcome backfill(String dataset, String field, Object value, String requestId) {
        Dataset ds = requireDataset(dataset);
        synchronized (ds) {
            BackfillOutcome previous = ds.backfills.get(requestId);
            if (previous != null) {
                if (!previous.field().equals(field) || !java.util.Objects.equals(previous.value(), value)) {
                    throw new ClientException(ErrorCode.VALIDATION_ERROR,
                            "backfill requestId '" + requestId + "' was already used with"
                                    + " different parameters (field=" + previous.field() + ")");
                }
                return new BackfillOutcome(previous.field(), previous.value(),
                        previous.highWaterMark(), previous.backfilled(), true);
            }
            long mark = ds.seq.get();
            List<StoredRecord> rewritten = new ArrayList<>(ds.records.size());
            int filled = 0;
            for (StoredRecord record : ds.records) {
                if (record.values().get(field) == null) {
                    Map<String, Object> values = new java.util.HashMap<>(record.values());
                    values.put(field, value);
                    rewritten.add(new StoredRecord(record.seq(), Map.copyOf(values)));
                    filled++;
                } else {
                    rewritten.add(record);
                }
            }
            if (filled > 0) {
                ds.records.clear();
                ds.records.addAll(rewritten);
                ds.epoch++;
            }
            BackfillOutcome outcome = new BackfillOutcome(field, value, mark, filled, false);
            ds.backfills.put(requestId, outcome);
            return outcome;
        }
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
