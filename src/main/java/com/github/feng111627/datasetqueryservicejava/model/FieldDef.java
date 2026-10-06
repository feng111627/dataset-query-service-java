package com.github.feng111627.datasetqueryservicejava.model;

/** 一个字段的声明：名称、类型、是否必填。 */
public record FieldDef(String name, FieldType type, boolean required) {
}
