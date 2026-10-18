package com.bbb.exercise.agentdemo.common.config;

import com.bbb.exercise.agentdemo.common.security.ProductionConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class ProductionConfigurationTest {
    private final ProductionConfiguration policy = new ProductionConfiguration();

    @Test
    void localDevelopmentDoesNotRequireProductionSecrets() {
        assertThatCode(() -> policy.postProcessEnvironment(new MockEnvironment(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void productionRequiresAnActiveSigningKeyBeforeAnyServiceStarts() {
        var environment = new MockEnvironment().withProperty("spring.application.name", "agent-chat-service");
        environment.setActiveProfiles("production");
        assertThatThrownBy(() -> policy.postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.internal-principal-secrets");
    }

    @Test
    void productionRejectsPublishedSigningDefaultsWithoutEchoingThem() {
        String published = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var environment = production("agent-chat-service")
                .withProperty("app.security.internal-principal-secrets", "current=" + published);
        assertThatThrownBy(() -> policy.postProcessEnvironment(environment, null))
                .hasMessageContaining("app.security.internal-principal-secrets")
                .hasMessageNotContaining(published);
    }

    @Test
    void productionRejectsMissingActiveKeyAndDuplicateKeyIds() {
        var environment = production("agent-chat-service")
                .withProperty("app.security.internal-principal-active-key-id", "missing");
        assertThatThrownBy(() -> policy.postProcessEnvironment(environment, null))
                .hasMessageContaining("app.security.internal-principal-active-key-id");
        environment.withProperty("app.security.internal-principal-active-key-id", "current")
                .withProperty("app.security.internal-principal-secrets", "current=" + key(1) + ",current=" + key(2));
        assertThatThrownBy(() -> policy.postProcessEnvironment(environment, null))
                .hasMessageContaining("app.security.internal-principal-secrets");
    }

    @Test
    void authRequiresEncryptionAndOtpSecretsAndAcceptsAValidRotatingRing() {
        var environment = production("agent-auth-service");
        assertThatThrownBy(() -> policy.postProcessEnvironment(environment, null))
                .hasMessageContaining("app.security.api-key-encryption");
        environment.withProperty("app.security.api-key-encryption-keys", "current=" + key(2))
                .withProperty("app.security.api-key-active-key-id", "current");
        assertThatThrownBy(() -> policy.postProcessEnvironment(environment, null))
                .hasMessageContaining("app.email.otp-secret");
        environment.withProperty("app.email.otp-secret", "private-otp-secret-with-at-least-32-characters");
        assertThatCode(() -> policy.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
    }

    @Test
    void insecureDatabaseRedisAndNacosDefaultsAreRejectedOnlyWhenConfigured() {
        for (var entry : java.util.Map.of("spring.datasource.password", "change-me",
                "spring.data.redis.password", "change-redis-me",
                "spring.cloud.nacos.discovery.password", "nacos").entrySet()) {
            var environment = production("agent-chat-service")
                    .withProperty("spring.datasource.url", "jdbc:mysql://mysql/agent")
                    .withProperty("spring.datasource.password", "private-db-value")
                    .withProperty("spring.data.redis.host", "redis")
                    .withProperty("spring.data.redis.password", "private-redis-value")
                    .withProperty("spring.cloud.nacos.discovery.server-addr", "nacos:8848")
                    .withProperty("spring.cloud.nacos.discovery.password", "private-nacos-value")
                    .withProperty(entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> policy.postProcessEnvironment(environment, null))
                    .hasMessage("Unsafe or missing production setting: " + entry.getKey());
        }
    }

    @Test
    void gatewayRequiresExplicitHttpsOriginsAndAllowsTheDeployedOrigin() {
        var environment = production("agent-gateway");
        assertThatThrownBy(() -> policy.postProcessEnvironment(environment, null))
                .hasMessageContaining("app.cors.allowed-origins");
        for (String origin : new String[] {"*", "http://localhost:*", "https://*.example.com", "http://example.com"}) {
            environment.withProperty("app.cors.allowed-origins", origin);
            assertThatThrownBy(() -> policy.postProcessEnvironment(environment, null))
                    .hasMessageContaining("app.cors.allowed-origins");
        }
        environment.withProperty("app.cors.allowed-origins", "https://example.com,https://www.example.com");
        assertThatCode(() -> policy.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
    }

    @Test
    void springBootDiscoversTheGuardWithoutComponentScanningCommon() {
        var application = new SpringApplication(EmptyApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> application.run("--spring.profiles.active=prod",
                "--spring.application.name=agent-chat-service", "--spring.main.banner-mode=off"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.internal-principal-secrets");
    }

    private static MockEnvironment production(String service) {
        var environment = new MockEnvironment()
                .withProperty("spring.application.name", service)
                .withProperty("app.security.internal-principal-secrets", "current=" + key(1))
                .withProperty("app.security.internal-principal-active-key-id", "current");
        environment.setActiveProfiles("prod");
        return environment;
    }

    private static String key(int offset) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 7 + offset);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyApplication {}
}
