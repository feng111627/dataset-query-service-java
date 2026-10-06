package com.github.feng111627.datasetqueryservicejava.web;

import java.util.List;

/** Uniform error body returned to callers. */
public record ApiError(ErrorBody error) {

    public record ErrorBody(String code, String message, List<String> details) {
    }

    public static ApiError of(String code, String message, List<String> details) {
        return new ApiError(new ErrorBody(code, message, details == null ? List.of() : details));
    }
}
