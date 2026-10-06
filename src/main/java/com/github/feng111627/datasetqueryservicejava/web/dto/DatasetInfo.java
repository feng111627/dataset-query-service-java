package com.github.feng111627.datasetqueryservicejava.web.dto;

/** 数据集元信息响应。 */
public record DatasetInfo(String name, SchemaDto schema, long recordCount) {
}
