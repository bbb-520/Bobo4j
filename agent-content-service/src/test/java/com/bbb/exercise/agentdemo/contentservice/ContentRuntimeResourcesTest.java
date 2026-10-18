package com.bbb.exercise.agentdemo.contentservice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证内容服务运行产物不包含仅由聊天服务使用的系统提示词。 */
class ContentRuntimeResourcesTest {
    @Test
    void doesNotPackageChatOnlySystemPrompt() {
        assertThat(getClass().getClassLoader().getResource("system_prompt")).isNull();
    }
}
