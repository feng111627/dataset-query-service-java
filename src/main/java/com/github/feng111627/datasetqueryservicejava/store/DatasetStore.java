package com.github.feng111627.datasetqueryservicejava.store;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.Snapshot;
import com.github.feng111627.datasetqueryservicejava.model.StoredRecord;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 */
@Repository
public class DatasetStore {

    private static class Dataset {
        final DatasetSchema schema;
        final AtomicLong seq = new AtomicLong(0);
        final List<StoredRecord> records = new ArrayList<>();

        Dataset(DatasetSchema schema) {
            this.schema = schema;
        }
    }

    private final Map<String, Dataset> datasets = new ConcurrentHashMap<>();

    /** Registers a dataset with its schema. Returns the effective schema. */
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
                ds.records.add(new StoredRecord(seq, Map.copyOf(record)));
                assigned.add(seq);
            }
        }
        return assigned;
    }

    /** Takes a snapshot at the current high-water mark. */
    public Snapshot snapshot(String dataset) {
        Dataset ds = requireDataset(dataset);
        return snapshotAt(ds, ds.seq.get());
    }

    /**
     * Re-reads the snapshot identified by a previously returned high-water
     * mark. Because storage is append-only, the same mark always yields the
     * same records, regardless of concurrent writes.
     */
    public Snapshot snapshot(String dataset, long highWaterMark) {
        Dataset ds = requireDataset(dataset);
        long current = ds.seq.get();
        if (highWaterMark > current) {
            throw new ClientException(ErrorCode.VALIDATION_ERROR,
                    "snapshotSeq " + highWaterMark + " is ahead of the dataset (current "
                            + current + "); it does not belong to dataset '" + dataset + "'");
        }
        return snapshotAt(ds, highWaterMark);
    }

    private Snapshot snapshotAt(Dataset ds, long highWaterMark) {
        synchronized (ds) {
            // Records are appended in seq order; the prefix up to the
            // high-water mark is exactly the snapshot.
            List<StoredRecord> result = new ArrayList<>();
            for (StoredRecord r : ds.records) {
                if (r.seq() > highWaterMark) {
                    break;
                }
                result.add(r);
            }
            return new Snapshot(highWaterMark, List.copyOf(result));
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
