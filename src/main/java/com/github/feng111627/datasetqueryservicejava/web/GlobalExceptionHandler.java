package com.github.feng111627.datasetqueryservicejava.web;

import com.github.feng111627.datasetqueryservicejava.error.ApiError;
import com.github.feng111627.datasetqueryservicejava.error.ApiException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;

/**
 * 统一错误出口。
 * <ul>
 *   <li>调用方用错（4xx）：携带稳定错误码与可读信息，JSON 解析/转换异常只给概括信息，不透出底层细节；</li>
 *   <li>服务内部出错（500）：固定 INTERNAL_ERROR 结构，异常堆栈只进服务端日志。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        if (ex.status() >= 500) {
            log.error("服务内部错误: {}", ex.getMessage(), ex);
        }
        return ResponseEntity.status(ex.status())
                .body(new ApiError(ex.code().name(), ex.getMessage(), ex.details()));
    }

    /** 请求体不是合法 JSON、枚举值无法转换等：底层 HttpMessageNotReadableException 不透出。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        log.debug("请求体无法解析: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.MALFORMED_REQUEST.name(),
                        "请求体不是合法的 JSON，或字段值类型与声明不符", List.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.MALFORMED_REQUEST.name(),
                        "路径参数或查询参数类型不正确: " + ex.getName(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.MALFORMED_REQUEST.name(),
                        "请求参数未通过校验", List.of()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(ErrorCode.DATASET_NOT_FOUND.name(),
                        "请求的接口不存在: " + ex.getRequestURL(), List.of()));
    }

    /** 兜底：任何未预期异常都折叠为 500，堆栈只写日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex) {
        log.error("未预期的服务端异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(ErrorCode.INTERNAL_ERROR.name(),
                        "服务内部发生错误，请联系服务维护者", List.of()));
    }
}
