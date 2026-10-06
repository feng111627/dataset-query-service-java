package com.github.feng111627.datasetqueryservicejava.error;

/** 单条记录校验问题；kind 取值 missing_field / type_mismatch / unknown_field。 */
public record ValidationIssue(int index, String field, String kind, String message) {
}
