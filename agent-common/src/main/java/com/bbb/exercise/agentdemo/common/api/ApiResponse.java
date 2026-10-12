package com.bbb.exercise.agentdemo.common.api;

/** 统一接口响应封装，明确区分成功数据与失败错误。 */
public record ApiResponse<T>(boolean success, T data, ApiError error) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> failure(String code, String message) {
        return new ApiResponse<>(false, null, new ApiError(code, message));
    }
}
