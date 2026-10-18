package com.bbb.exercise.agentdemo.common.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证公共配置采用可选 Nacos 导入并声明健康探针。 */
class ConfigurationContractTest {
    @Test
    void commonConfigUsesOptionalNacosImportAndHealthProbes() throws Exception {
        String content = Files.readString(Path.of("src/main/resources/application-common.yml"));

        assertThat(content).contains("optional:nacos:");
        assertThat(content).contains("probes:");
        assertThat(content).contains("enabled: true");
    }
}
