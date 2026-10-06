package com.github.feng111627.datasetqueryservicejava.model;

import java.util.List;

/** 数据集 schema：有序字段定义列表。 */
public record DatasetSchema(List<FieldDef> fields) {
}
