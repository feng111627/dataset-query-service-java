package com.github.feng111627.datasetqueryservicejava.error;

import java.util.List;

/** Caller-side error (HTTP 4xx). Carries a stable code and optional details. */
public class ClientException extends RuntimeException {

    private final ErrorCode code;
    private final List<String> details;

    public ClientException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ClientException(ErrorCode code, String message, List<String> details) {
        super(message);
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public List<String> details() {
        return details;
    }
}
