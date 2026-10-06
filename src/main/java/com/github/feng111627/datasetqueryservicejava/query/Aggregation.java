package com.github.feng111627.datasetqueryservicejava.query;

/** 单个聚合项：对 field 做 op，结果列命名为 alias。 */
public record Aggregation(AggregationOp op, String field, String alias) {
}
