package com.github.feng111627.datasetqueryservicejava.error;

import java.util.List;

/** 可预期的调用方错误，携带 HTTP 状态、错误码与可选校验明细。 */
public class ApiException extends RuntimeException {

    private final int status;
    private final ErrorCode code;
    private final transient List<ValidationIssue> details;

    public ApiException(int status, ErrorCode code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(int status, ErrorCode code, String message, List<ValidationIssue> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public int status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }

    public List<ValidationIssue> details() {
        return details;
    }
}
