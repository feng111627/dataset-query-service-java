package com.github.feng111627.datasetqueryservicejava.query;

import java.util.List;

/**
 * 查询请求：
 * filters 条件过滤；select 选列（null/空表示全部字段）；
 * groupBy + aggregations 聚合；limit/offset 分页。
 */
public class QueryRequest {
    private List<Filter> filters = List.of();
    private List<String> select;
    private List<String> groupBy = List.of();
    private List<Aggregation> aggregations = List.of();
    private int limit = 100;
    private int offset = 0;
    /**
     * 续翻页时回传上一页响应中的 watermark，把整次遍历“钉”在同一个数据集版本上；
     * 缺省/为 0 表示以当前已发布水位建立新快照。
     */
    private Long watermark;

    public List<Filter> getFilters() {
        return filters;
    }

    public void setFilters(List<Filter> filters) {
        this.filters = filters;
    }

    public List<String> getSelect() {
        return select;
    }

    public void setSelect(List<String> select) {
        this.select = select;
    }

    public List<String> getGroupBy() {
        return groupBy;
    }

    public void setGroupBy(List<String> groupBy) {
        this.groupBy = groupBy;
    }

    public List<Aggregation> getAggregations() {
        return aggregations;
    }

    public void setAggregations(List<Aggregation> aggregations) {
        this.aggregations = aggregations;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public Long getWatermark() {
        return watermark;
    }

    public void setWatermark(Long watermark) {
        this.watermark = watermark;
    }
}
