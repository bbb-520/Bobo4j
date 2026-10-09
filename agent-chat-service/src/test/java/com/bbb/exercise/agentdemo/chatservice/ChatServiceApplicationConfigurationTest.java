package com.bbb.exercise.agentdemo.chatservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.assertj.core.api.Assertions.assertThat;

class ChatServiceApplicationConfigurationTest {
    @Test
    void scansMigratedConfigurationPropertiesPackage() {
        assertThat(ChatServiceApplication.class.getAnnotation(ConfigurationPropertiesScan.class).basePackages())
                .contains("com.bbb.exercise.agentdemo.runtime.config")
                .doesNotContain("com.bbb.exercise.agentdemo1_0");
    }

    @Test
    void scansRuntimeClientsRequiredByChatIdentityAndService() {
        assertThat(ChatServiceApplication.class.getAnnotation(SpringBootApplication.class).scanBasePackages())
                .contains("com.bbb.exercise.agentdemo.runtime.client");
    }
}
