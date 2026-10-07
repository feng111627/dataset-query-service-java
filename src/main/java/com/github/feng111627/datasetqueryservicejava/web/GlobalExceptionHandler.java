package com.github.feng111627.datasetqueryservicejava.web;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.error.ServerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions to the uniform ApiError structure.
 *
 * <p>Caller mistakes (ClientException, malformed JSON, wrong content type)
 * produce 4xx with a stable error code. Everything else is an internal error:
 * it is logged with the full stack trace but returned as a generic
 * INTERNAL_ERROR body so low-level parse/conversion exceptions never leak.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ClientException.class)
    public ResponseEntity<ApiError> handleClient(ClientException ex) {
        HttpStatus status = switch (ex.code()) {
            case SCHEMA_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INCOMPATIBLE_SCHEMA, SNAPSHOT_EXPIRED -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(ApiError.of(ex.code().name(), ex.getMessage(), ex.details()));
    }

    /** Malformed JSON / unreadable request body: a caller mistake, not a 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        log.debug("unreadable request body: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.BAD_REQUEST.name(),
                        "request body is missing or not valid JSON", null));
    }

    @ExceptionHandler(ServerException.class)
    public ResponseEntity<ApiError> handleServer(ServerException ex) {
        log.error("internal error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR.name(),
                        "internal server error", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR.name(),
                        "internal server error", null));
    }
}
