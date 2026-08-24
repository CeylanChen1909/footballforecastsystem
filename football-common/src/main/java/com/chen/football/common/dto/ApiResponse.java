package com.chen.football.common.dto;

public record ApiResponse<T>(boolean success, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "ok", data);
    }

    public static ApiResponse<ApiError> error(ApiError apiError) {
        return new ApiResponse<>(false, apiError.message(), apiError);
    }
}
