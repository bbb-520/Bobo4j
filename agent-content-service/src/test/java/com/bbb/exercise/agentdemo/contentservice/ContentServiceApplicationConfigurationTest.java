package com.bbb.exercise.agentdemo.contentservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import static org.assertj.core.api.Assertions.assertThat;

class ContentServiceApplicationConfigurationTest {
    @Test
    void scansMigratedConfigurationPropertiesPackage() {
        assertThat(ContentServiceApplication.class.getAnnotation(ConfigurationPropertiesScan.class).basePackages())
                .contains("com.bbb.exercise.agentdemo1_0");
    }
}
