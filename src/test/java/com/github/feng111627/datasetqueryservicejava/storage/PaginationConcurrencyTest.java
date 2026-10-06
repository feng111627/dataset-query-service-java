package com.github.feng111627.datasetqueryservicejava.storage;

import com.github.feng111627.datasetqueryservicejava.model.DatasetRecord;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发写入下分页稳定性。
 *
 * <p>协议：一次遍历开始时取当前已发布水位 w，随后每页都钉住 w（watermark 回传），
 * 数据集即呈现 id 1..w 的不可变版本。保证：
 * <ol>
 *   <li>写入端：并发 append 分配的 id 连续、区间互不重叠（不丢不重）；</li>
 *   <li>遍历端：写进行中任意一轮固定水位遍历，页内/跨页无重复 id，且恰好覆盖 1..w；</li>
 *   <li>最终以完整水位翻页，读到的集合恰好是 1..N。</li>
 * </ol>
 */
class PaginationConcurrencyTest {

    private static final int WRITER_THREADS = 8;
    private static final int BATCHES_PER_WRITER = 40;
    private static final int BATCH_SIZE = 25;
    private static final int PAGE_SIZE = 37;

    private static final class RunFailure {
        final String message;

        RunFailure(String message) {
            this.message = message;
        }
    }

    @Test
    @DisplayName("并发写入：钉住 watermark 的分页遍历不重复不丢失，写入区间连续无重叠")
    void paginationStaysConsistentUnderConcurrentWrites() throws Exception {
        DatasetStore store = new DatasetStore();
        DatasetSchema schema = Fixtures.userSchema();
        store.register("users", schema, true);

        ExecutorService pool = Executors.newFixedThreadPool(WRITER_THREADS + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(WRITER_THREADS);
        ConcurrentLinkedQueue<long[]> acceptedRanges = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<RunFailure> runFailures = new ConcurrentLinkedQueue<>();
        AtomicInteger completedRuns = new AtomicInteger();
        AtomicInteger pageRequests = new AtomicInteger();
        AtomicInteger payloadSeq = new AtomicInteger();

        for (int w = 0; w < WRITER_THREADS; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int b = 0; b < BATCHES_PER_WRITER; b++) {
                        List<DatasetRecord> batch = new ArrayList<>(BATCH_SIZE);
                        for (int i = 0; i < BATCH_SIZE; i++) {
                            int seq = payloadSeq.incrementAndGet();
                            batch.add(DatasetRecord.input(Map.of(
                                    "id", seq,
                                    "name", "u" + seq)));
                        }
                        acceptedRanges.add(store.append("users", batch));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    writersDone.countDown();
                }
            });
        }

        // 消费端：写进行中反复发起“一轮固定水位遍历”，每轮独立校验不重不丢
        pool.submit(() -> {
            try {
                start.await();
                while (writersDone.getCount() > 0) {
                    drainOneSnapshot(store, runFailures, completedRuns, pageRequests);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        start.countDown();
        assertThat(writersDone.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        int total = WRITER_THREADS * BATCHES_PER_WRITER * BATCH_SIZE;

        // 1) 写入端：确认区间拼起来恰好覆盖 1..total，无重叠无空洞
        boolean[] covered = new boolean[total + 1];
        for (long[] range : acceptedRanges) {
            for (long id = range[0]; id <= range[1]; id++) {
                if (id < 1 || id > total) {
                    runFailures.add(new RunFailure("写入 id 越界: " + id));
                } else if (covered[(int) id]) {
                    runFailures.add(new RunFailure("写入 id 被两个批次覆盖: " + id));
                } else {
                    covered[(int) id] = true;
                }
            }
        }
        for (int id = 1; id <= total; id++) {
            if (!covered[id]) {
                runFailures.add(new RunFailure("写入端 id 丢失: " + id));
            }
        }

        // 2) 最终以完整水位翻页：恰好 1..total，顺序与 id 一致
        long finalWatermark = store.currentWatermark("users");
        assertThat(finalWatermark).isEqualTo(total);
        List<Long> finalIds = readFullRun(store, finalWatermark, pageRequests);
        assertThat(finalIds).hasSize(total);
        for (int i = 0; i < total; i++) {
            assertThat(finalIds.get(i)).isEqualTo((long) i + 1);
        }

        // 3) 进行中完成的每一轮遍历都必须自洽（无重复、恰好覆盖其钉住的水位）
        assertThat(completedRuns.get())
                .as("写入期间至少应完成一轮与写入并发的分页遍历")
                .isGreaterThanOrEqualTo(1);
        assertThat(pageRequests.get()).isPositive();
        assertThat(runFailures).as("并发分页异常: %s",
                runFailures.stream().map(f -> f.message).toList()).isEmpty();
    }

    /** 一轮固定水位遍历：发现重复/缺口时记录失败，正常完成则计数 +1。 */
    private void drainOneSnapshot(DatasetStore store,
                                  ConcurrentLinkedQueue<RunFailure> failures,
                                  AtomicInteger completedRuns,
                                  AtomicInteger pageRequests) {
        long pinned = store.currentWatermark("users");
        if (pinned == 0) {
            return;
        }
        Set<Long> seen = new HashSet<>();
        long lastId = 0;
        int offset = 0;
        while (true) {
            List<DatasetRecord> all = store.snapshot("users", 1, pinned);
            int from = Math.min(offset, all.size());
            int to = Math.min(from + PAGE_SIZE, all.size());
            List<DatasetRecord> slice = all.subList(from, to);
            pageRequests.incrementAndGet();
            for (DatasetRecord r : slice) {
                if (!seen.add(r.id())) {
                    failures.add(new RunFailure("遍历(水位=" + pinned + ")出现重复 id: " + r.id()));
                    return;
                }
                if (r.id() != lastId + 1) {
                    failures.add(new RunFailure(
                            "遍历(水位=" + pinned + ")顺序/缺口异常: 上一条=" + lastId + " 当前=" + r.id()));
                    return;
                }
                lastId = r.id();
            }
            offset += PAGE_SIZE;
            if (to >= all.size()) {
                break;
            }
        }
        if (seen.size() != pinned || lastId != pinned) {
            failures.add(new RunFailure(
                    "遍历(水位=" + pinned + ")覆盖不完整: 读到=" + seen.size() + " 末id=" + lastId));
            return;
        }
        completedRuns.incrementAndGet();
    }

    private List<Long> readFullRun(DatasetStore store, long pinned, AtomicInteger pageRequests) {
        List<Long> ids = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<DatasetRecord> all = store.snapshot("users", 1, pinned);
            int from = Math.min(offset, all.size());
            int to = Math.min(from + PAGE_SIZE, all.size());
            for (DatasetRecord r : all.subList(from, to)) {
                ids.add(r.id());
            }
            pageRequests.incrementAndGet();
            offset += PAGE_SIZE;
            if (to >= all.size()) {
                return ids;
            }
        }
    }
}
