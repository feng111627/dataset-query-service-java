package com.github.feng111627.datasetqueryservicejava.query;

import java.util.List;
import java.util.Map;

/**
 * 查询结果。
 *
 * @param watermark     本次查询所基于的数据集版本（最大记录 id），续翻页时回传它
 * @param totalMatched  过滤后、分页前的匹配行数（稳定快照内计数）
 * @param hasMore       是否还有后续页
 * @param aggregated    是否为聚合查询
 * @param rows          结果行
 */
public record QueryResult(long watermark,
                          int totalMatched,
                          int limit,
                          int offset,
                          boolean hasMore,
                          boolean aggregated,
                          List<Map<String, Object>> rows) {

    public int totalReturned() {
        return rows.size();
    }
}
