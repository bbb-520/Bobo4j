package com.bbb.exercise.agentdemo.mediaservice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证媒体服务本地配置包含连接 Docker 基础设施所需的 Nacos 凭据配置。 */
class MediaLocalRuntimeConfigurationTest {
    @Test
    void suppliesNacosCredentialsForLocalDockerInfrastructure() {
        String application = readApplicationConfiguration();

        assertThat(count(application, "username: ${NACOS_USERNAME:nacos}")).isEqualTo(2);
        assertThat(count(application, "password: ${NACOS_PASSWORD:nacos}")).isEqualTo(2);
    }

    private static String readApplicationConfiguration() {
        try (var stream =
                MediaLocalRuntimeConfigurationTest.class.getResourceAsStream("/application.yml")) {
            if (stream == null) {
                throw new IllegalStateException(
                        "application.yml is missing from the test classpath");
            }

            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static long count(String value, String token) {
        return value.lines().filter(line -> line.contains(token)).count();
    }
}
