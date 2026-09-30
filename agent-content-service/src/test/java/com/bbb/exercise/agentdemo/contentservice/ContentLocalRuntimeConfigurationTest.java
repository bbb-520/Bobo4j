package com.bbb.exercise.agentdemo.contentservice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ContentLocalRuntimeConfigurationTest {
    @Test
    void suppliesNacosCredentialsForLocalDockerInfrastructure() {
        String application = readApplicationConfiguration();

        assertThat(application).contains("server-addr: ${NACOS_SERVER_ADDR:127.0.0.1:8848}");
        assertThat(count(application, "username: ${NACOS_USERNAME:nacos}")).isEqualTo(2);
        assertThat(count(application, "password: ${NACOS_PASSWORD:nacos}")).isEqualTo(2);
    }

    private static String readApplicationConfiguration() {
        try (var stream = ContentLocalRuntimeConfigurationTest.class.getResourceAsStream("/application.yml")) {
            if (stream == null) {
                throw new IllegalStateException("application.yml is missing from the test classpath");
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
