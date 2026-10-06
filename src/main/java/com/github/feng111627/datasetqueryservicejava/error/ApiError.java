package com.github.feng111627.datasetqueryservicejava.error;

import java.util.List;

/** 统一错误响应结构。 */
public record ApiError(String code, String message, List<ValidationIssue> details) {
}
