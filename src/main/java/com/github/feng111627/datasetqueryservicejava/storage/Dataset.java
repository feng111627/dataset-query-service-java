package com.github.feng111627.datasetqueryservicejava.storage;

import com.github.feng111627.datasetqueryservicejava.model.DatasetRecord;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个数据集：schema 与已发布记录。
 *
 * <p>并发语义：所有读写都在 this 监视器上串行执行。
 * <ul>
 *   <li>记录 id 从 1 开始、连续不跳号，即其在列表中的位置；</li>
 *   <li>一次 append 是原子的：整批记录全部加入后，发布水位 watermark 才前移，
 *       因此读端永远看不到“写了一半”的批次，也不会观察到 id 空洞；</li>
 *   <li>snapshot 返回指定 id 区间的防御性拷贝，遍历期间不受后续追加影响。</li>
 * </ul>
 */
class Dataset {

    private final String name;
    private DatasetSchema schema;
    private final List<DatasetRecord> records = new ArrayList<>();
    /** 已发布水位：最大的已提交记录 id；0 表示尚无记录。 */
    private long watermark = 0;

    Dataset(String name, DatasetSchema schema) {
        this.name = name;
        this.schema = schema;
    }

    String name() {
        return name;
    }

    synchronized DatasetSchema schema() {
        return schema;
    }

    synchronized void updateSchema(DatasetSchema newSchema) {
        this.schema = newSchema;
    }

    /**
     * 原子追加一整批记录，按顺序分配连续 id。
     *
     * @return 长度 2 数组 [fromId, toId]：本批记录占据的闭区间 id（空批时两者相等且无新增）
     */
    synchronized long[] append(List<DatasetRecord> batch) {
        long fromId = watermark + 1;
        for (int i = 0; i < batch.size(); i++) {
            records.add(new DatasetRecord(watermark + i + 1, batch.get(i).values()));
        }
        watermark += batch.size();
        return new long[]{fromId, watermark};
    }

    /** 当前已发布水位。 */
    synchronized long watermark() {
        return watermark;
    }

    synchronized long count() {
        return records.size();
    }

    /**
     * 取 id 在 [fromId, toId] 闭区间内的已发布记录，按 id 升序。
     * toId 大于当前水位时自动截断到当前水位。
     */
    synchronized List<DatasetRecord> snapshot(long fromId, long toId) {
        long effectiveTo = Math.min(toId, watermark);
        if (fromId > effectiveTo || fromId <= 0) {
            return new ArrayList<>();
        }
        return new ArrayList<>(records.subList((int) (fromId - 1), (int) effectiveTo));
    }
}
