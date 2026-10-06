package com.github.feng111627.datasetqueryservicejava.query;

/** 单个过滤条件。value 为 JSON 反序列化得到的标量。 */
public record Filter(String field, Operator op, Object value) {
}
