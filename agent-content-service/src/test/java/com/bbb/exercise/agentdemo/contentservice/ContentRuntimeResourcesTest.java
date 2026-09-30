package com.bbb.exercise.agentdemo.contentservice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentRuntimeResourcesTest {
    @Test
    void doesNotPackageChatOnlySystemPrompt() {
        assertThat(getClass().getClassLoader().getResource("system_prompt")).isNull();
    }
}
