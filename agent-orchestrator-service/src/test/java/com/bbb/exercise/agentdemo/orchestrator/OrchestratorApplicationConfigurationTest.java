package com.bbb.exercise.agentdemo.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorApplicationConfigurationTest {
    @Test
    void scansMigratedBusinessPackageForComponentsAndProperties() {
        assertThat(OrchestratorApplication.class.getAnnotation(SpringBootApplication.class).scanBasePackages())
                .contains("com.bbb.exercise.agentdemo1_0");
        assertThat(OrchestratorApplication.class.getAnnotation(ConfigurationPropertiesScan.class).basePackages())
                .contains("com.bbb.exercise.agentdemo1_0");
    }
}
