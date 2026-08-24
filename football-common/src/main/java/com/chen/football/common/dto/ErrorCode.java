package com.chen.football.common.dto;

public enum ErrorCode {
    SUCCESS("0", "success"),
    BAD_REQUEST("40000", "参数错误"),
    UNAUTHORIZED("40100", "未登录或登录已过期"),
    FORBIDDEN("40300", "无权限访问"),
    NOT_FOUND("40400", "资源不存在"),
    CONFLICT("40900", "资源冲突"),
    BUSINESS_ERROR("42200", "业务处理失败"),
    SYSTEM_ERROR("50000", "系统错误");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
