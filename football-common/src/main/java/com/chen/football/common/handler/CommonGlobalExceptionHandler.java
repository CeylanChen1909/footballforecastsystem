package com.chen.football.common.handler;

import com.chen.football.common.dto.ApiError;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.dto.ErrorCode;
import com.chen.football.common.exception.BusinessException;
import com.chen.football.common.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.NoSuchElementException;

/**
 * 全局异常处理器。
 * 区分业务异常 / 参数异常 / 404 / 405 / 未知异常，返回正确的 HTTP 状态码，
 * 错误响应体统一为 {success:false, message, data:{code,message,details}}。
 */
@RestControllerAdvice
@org.springframework.core.annotation.Order(0)
public class CommonGlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CommonGlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleBusiness(BusinessException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.BUSINESS_ERROR, e.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleUnauthorized(UnauthorizedException e) {
        return error(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleResponseStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        ErrorCode code = switch (status) {
            case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case TOO_MANY_REQUESTS -> ErrorCode.CONFLICT;
            case BAD_REQUEST -> ErrorCode.BAD_REQUEST;
            default -> ErrorCode.SYSTEM_ERROR;
        };
        return error(status, code, e.getReason());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<ApiError>> handleBadRequest(Exception e) {
        String message = ErrorCode.BAD_REQUEST.defaultMessage();
        if (e instanceof MethodArgumentNotValidException manv) {
            message = manv.getBindingResult().getFieldErrors().stream()
                    .findFirst()
                    .map(err -> err.getField() + " " + err.getDefaultMessage())
                    .orElse(ErrorCode.BAD_REQUEST.defaultMessage());
        }
        return error(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleIllegalArgument(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleNoSuchElement(NoSuchElementException e) {
        return error(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({NoResourceFoundException.class, org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<ApiError>> handleNotFound(Exception e) {
        return error(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.BAD_REQUEST,
                "请求方法不支持: " + e.getMethod());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.BAD_REQUEST,
                "不支持的 Content-Type: " + (e.getContentType() == null ? "unknown" : e.getContentType()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ApiError>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.SYSTEM_ERROR, ErrorCode.SYSTEM_ERROR.defaultMessage());
    }

    private ResponseEntity<ApiResponse<ApiError>> error(HttpStatus status, ErrorCode code, String message) {
        String text = (message == null || message.isBlank()) ? code.defaultMessage() : message;
        return ResponseEntity.status(status).body(ApiResponse.error(ApiError.of(code.code(), text)));
    }
}
