package com.bbb.exercise.agentdemo.mediaservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.assertj.core.api.Assertions.assertThat;

class MediaServiceApplicationConfigurationTest {
    @Test
    void scansMigratedConfigurationPropertiesPackage() {
        assertThat(MediaServiceApplication.class.getAnnotation(ConfigurationPropertiesScan.class).basePackages())
                .contains("com.bbb.exercise.agentdemo.runtime.config")
                .doesNotContain("com.bbb.exercise.agentdemo1_0");
    }

    @Test
    void scansRuntimeClientsRequiredByMediaJobs() {
        assertThat(MediaServiceApplication.class.getAnnotation(SpringBootApplication.class).scanBasePackages())
                .contains("com.bbb.exercise.agentdemo.runtime.client");
    }
}
