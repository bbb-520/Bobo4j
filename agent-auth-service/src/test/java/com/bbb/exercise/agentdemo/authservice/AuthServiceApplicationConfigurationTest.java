package com.bbb.exercise.agentdemo.authservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import static org.assertj.core.api.Assertions.assertThat;

class AuthServiceApplicationConfigurationTest {
    @Test
    void scansMigratedBusinessPackageForComponentsAndProperties() {
        assertThat(AuthServiceApplication.class.getAnnotation(SpringBootApplication.class).scanBasePackages())
                .contains("com.bbb.exercise.agentdemo1_0");
        assertThat(AuthServiceApplication.class.getAnnotation(ConfigurationPropertiesScan.class).basePackages())
                .contains("com.bbb.exercise.agentdemo1_0");
    }
}
