package com.github.feng111627.datasetqueryservicejava.error;

/** 对外错误码：可区分不同的调用方错误与内部错误。 */
public enum ErrorCode {
    MALFORMED_REQUEST,
    DATASET_NOT_FOUND,
    DATASET_ALREADY_EXISTS,
    SCHEMA_INCOMPATIBLE,
    VALIDATION_FAILED,
    INVALID_QUERY,
    INTERNAL_ERROR
}
