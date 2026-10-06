package com.github.feng111627.datasetqueryservicejava.storage;

import com.github.feng111627.datasetqueryservicejava.error.ApiException;
import com.github.feng111627.datasetqueryservicejava.model.DatasetRecord;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DatasetStore：注册冲突、404、原子批次与水位/快照语义。 */
class DatasetStoreTest {

    private DatasetStore store;
    private final DatasetSchema schema = Fixtures.userSchema();

    @BeforeEach
    void setUp() {
        store = new DatasetStore();
    }

    private DatasetRecord record(long idSeed, String name) {
        return DatasetRecord.input(Map.of("id", idSeed, "name", name));
    }

    @Test
    @DisplayName("POST 语义：重复注册抛 409 DATASET_ALREADY_EXISTS")
    void duplicateRegisterConflicts() {
        assertThat(store.register("users", schema, true)).isTrue();
        assertThatThrownBy(() -> store.register("users", schema, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(409));
    }

    @Test
    @DisplayName("读写不存在的数据集抛 404 DATASET_NOT_FOUND")
    void unknownDatasetReturns404() {
        assertThatThrownBy(() -> store.append("nope", List.of(record(1, "a"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(404));
    }

    @Test
    @DisplayName("一次 append 原子分配连续 id，水位等于记录总数")
    void appendAssignsContiguousIds() {
        store.register("users", schema, true);
        long[] range = store.append("users",
                List.of(record(1, "a"), record(2, "b"), record(3, "c")));
        assertThat(range).containsExactly(1, 3);
        assertThat(store.currentWatermark("users")).isEqualTo(3);
        assertThat(store.countOf("users")).isEqualTo(3);
    }

    @Test
    @DisplayName("快照按 id 区间返回，超界自动截断")
    void snapshotRespectsRange() {
        store.register("users", schema, true);
        store.append("users", List.of(record(1, "a"), record(2, "b"), record(3, "c")));
        List<DatasetRecord> page = store.snapshot("users", 2, 10);
        assertThat(page).hasSize(2);
        assertThat(page.get(0).id()).isEqualTo(2);
        assertThat(page.get(1).id()).isEqualTo(3);
        assertThat(page.get(0).values().get("name")).isEqualTo("b");
    }

    @Test
    @DisplayName("快照是防御性拷贝：后续追加不影响已取快照")
    void snapshotIsImmutable() {
        store.register("users", schema, true);
        store.append("users", List.of(record(1, "a")));
        List<DatasetRecord> snap = store.snapshot("users", 1, store.currentWatermark("users"));
        store.append("users", List.of(record(2, "b"), record(3, "c")));
        assertThat(snap).hasSize(1);
        assertThat(store.snapshot("users", 1, 3)).hasSize(3);
    }
}
