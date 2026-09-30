package com.bbb.exercise.agentdemo.chatservice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRuntimeResourcesTest {
    @Test
    void packagesSystemPromptForContainerRuntime() {
        assertThat(getClass().getClassLoader().getResource("system_prompt")).isNotNull();
    }
}
