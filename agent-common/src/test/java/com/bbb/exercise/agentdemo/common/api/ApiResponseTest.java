package com.bbb.exercise.agentdemo.common.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证统一接口响应的成功和失败 JSON 序列化结构。 */
class ApiResponseTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesSuccessAndFailureContracts() throws Exception {
        ApiResponse<String> success = ApiResponse.success("ok");
        ApiResponse<Void> failure = ApiResponse.failure("RATE_LIMITED", "请求过于频繁");

        assertThat(success.success()).isTrue();
        assertThat(objectMapper.writeValueAsString(success)).contains("\"success\":true");
        assertThat(failure.success()).isFalse();
        assertThat(failure.error().code()).isEqualTo("RATE_LIMITED");
        assertThat(objectMapper.writeValueAsString(failure)).contains("RATE_LIMITED");
    }
}
