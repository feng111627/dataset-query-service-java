package com.github.feng111627.datasetqueryservicejava.storage;

import com.github.feng111627.datasetqueryservicejava.error.ApiException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetRecord;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.validation.SchemaCompatibility;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存数据集注册表。
 *
 * <p>注册表本身用 {@link ConcurrentHashMap}，单个数据集内部有自己的锁，
 * 因此不同数据集之间可以并发读写，同一数据集的 append/snapshot 各自原子。
 */
@Component
public class DatasetStore {

    private final ConcurrentMap<String, Dataset> datasets = new ConcurrentHashMap<>();

    /**
     * 注册或（兼容地）更新数据集 schema。
     *
     * @param failIfExists true（POST 语义）时，数据集已存在即冲突；
     *                     false（PUT 语义）时，对已存在数据集做兼容性检查后更新
     * @return true 表示本次新建，false 表示已存在并通过兼容更新
     */
    public boolean register(String name, DatasetSchema schema, boolean failIfExists) {
        Dataset fresh = new Dataset(name, schema);
        Dataset existing = datasets.putIfAbsent(name, fresh);
        if (existing == null) {
            return true;
        }
        if (failIfExists) {
            throw new ApiException(409, ErrorCode.DATASET_ALREADY_EXISTS,
                    "数据集 '" + name + "' 已存在，请改用 PUT 进行兼容更新");
        }
        SchemaCompatibility.Result result = SchemaCompatibility.check(existing.schema(), schema);
        if (!result.compatible()) {
            throw new ApiException(409, ErrorCode.SCHEMA_INCOMPATIBLE, result.reason());
        }
        existing.updateSchema(schema);
        return false;
    }

    public boolean exists(String name) {
        return datasets.containsKey(name);
    }

    public Optional<DatasetSchema> schemaOf(String name) {
        Dataset dataset = datasets.get(name);
        return dataset == null ? Optional.empty() : Optional.of(dataset.schema());
    }

    public long countOf(String name) {
        Dataset dataset = require(name);
        return dataset.count();
    }

    /** 原子追加，返回 [fromId, toId]；数据集不存在时抛 404。 */
    public long[] append(String name, List<DatasetRecord> records) {
        return require(name).append(records);
    }

    /** 当前已发布水位。 */
    public long currentWatermark(String name) {
        return require(name).watermark();
    }

    /** 取 [fromId, toId] 的快照；toId 超过水位时截断到水位。 */
    public List<DatasetRecord> snapshot(String name, long fromId, long toId) {
        return require(name).snapshot(fromId, toId);
    }

    private Dataset require(String name) {
        Dataset dataset = datasets.get(name);
        if (dataset == null) {
            throw new ApiException(404, ErrorCode.DATASET_NOT_FOUND,
                    "数据集 '" + name + "' 不存在，请先注册");
        }
        return dataset;
    }
}
