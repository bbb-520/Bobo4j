package com.bbb.exercise.agentdemo.chatservice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证聊天服务运行产物包含容器启动所需的系统提示词资源。 */
class ChatRuntimeResourcesTest {
    @Test
    void packagesSystemPromptForContainerRuntime() {
        assertThat(getClass().getClassLoader().getResource("system_prompt")).isNotNull();
    }
}
