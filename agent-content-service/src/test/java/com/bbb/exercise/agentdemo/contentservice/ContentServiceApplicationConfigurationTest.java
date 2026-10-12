package com.bbb.exercise.agentdemo.contentservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证内容服务的配置属性及共享运行时客户端扫描范围。 */
class ContentServiceApplicationConfigurationTest {
    @Test
    void scansMigratedConfigurationPropertiesPackage() {
        assertThat(
                        ContentServiceApplication.class
                                .getAnnotation(ConfigurationPropertiesScan.class)
                                .basePackages())
                .contains("com.bbb.exercise.agentdemo.runtime.config")
                .doesNotContain("com.bbb.exercise.agentdemo1_0");
    }

    @Test
    void scansRuntimeClientsRequiredByContentServices() {
        assertThat(
                        ContentServiceApplication.class
                                .getAnnotation(SpringBootApplication.class)
                                .scanBasePackages())
                .contains("com.bbb.exercise.agentdemo.runtime.client");
    }
}
