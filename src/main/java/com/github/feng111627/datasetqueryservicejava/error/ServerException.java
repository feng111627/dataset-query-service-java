package com.github.feng111627.datasetqueryservicejava.error;

/** Service-internal error (HTTP 5xx). Never leaks low-level causes to callers. */
public class ServerException extends RuntimeException {

    public ServerException(String message) {
        super(message);
    }

    public ServerException(String message, Throwable cause) {
        super(message, cause);
    }
}
