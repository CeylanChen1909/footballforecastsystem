package com.chen.football.common.dto;

import com.chen.football.common.i18n.I18nText;

public enum ErrorCode {
    SUCCESS("0", "success", "success"),
    BAD_REQUEST("40000", "参数错误", "Invalid request"),
    UNAUTHORIZED("40100", "未登录或登录已过期", "Not signed in or session expired"),
    FORBIDDEN("40300", "无权限访问", "Access denied"),
    NOT_FOUND("40400", "资源不存在", "Resource not found"),
    CONFLICT("40900", "资源冲突", "Resource conflict"),
    BUSINESS_ERROR("42200", "业务处理失败", "Business processing failed"),
    SYSTEM_ERROR("50000", "系统错误", "System error");

    private final String code;
    private final String defaultMessage;
    private final String englishMessage;

    ErrorCode(String code, String defaultMessage, String englishMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.englishMessage = englishMessage;
    }

    public String code() {
        return code;
    }

    /** Localized default message for the active request language. */
    public String defaultMessage() {
        return I18nText.of(defaultMessage, englishMessage);
    }

    public String defaultMessageZh() {
        return defaultMessage;
    }

    public String defaultMessageEn() {
        return englishMessage;
    }
}
