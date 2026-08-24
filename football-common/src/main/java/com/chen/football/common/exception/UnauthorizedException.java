package com.chen.football.common.exception;

/**
 * 未认证/登录过期异常，由全局异常处理器映射为 HTTP 401。
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
