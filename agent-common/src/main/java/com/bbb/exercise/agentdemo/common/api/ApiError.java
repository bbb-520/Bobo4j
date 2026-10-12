package com.bbb.exercise.agentdemo.common.api;

/** 统一接口错误载荷，使用稳定错误码和可读说明描述失败。 */
public record ApiError(String code, String message) {}
