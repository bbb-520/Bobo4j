package com.bbb.exercise.agentdemo.orchestrator;

import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import com.bbb.exercise.agentdemo.runtime.security.BrowserIdentityProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证编排服务扫描迁移后的业务组件及配置属性包。 */
class OrchestratorApplicationConfigurationTest {
    @Test
    void browserIdentityCanResolveItsInternalClientFromTheActualApplicationContext() throws Exception {
        String secrets = "current=" + Base64.getEncoder().encodeToString(new byte[32]);
        var keys = new PrincipalKeyRing(secrets, "current");
        var sessionCookie = new AtomicReference<String>();
        var auth = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        auth.createContext("/internal/auth/session", exchange -> {
            keys.verify(exchange.getRequestHeaders().getFirst("X-Internal-Principal"),
                    "agent-auth-service", "GET /internal/auth/session");
            sessionCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            byte[] body = """
                    {"userId":"alice","tenantId":"tenant","authenticated":true,"username":"Alice",
                    "roles":["USER"],"permissions":["AGENT_EXECUTE"]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        auth.start();
        try {
            new ReactiveWebApplicationContextRunner()
                    .withUserConfiguration(OrchestratorApplication.class)
                    .withPropertyValues(
                            "spring.application.name=agent-orchestrator-service",
                            "spring.cloud.nacos.discovery.enabled=false",
                            "spring.cloud.nacos.config.enabled=false",
                            "spring.cloud.discovery.enabled=false",
                            "spring.cloud.compatibility-verifier.enabled=false",
                            "management.tracing.enabled=false",
                            "spring.flyway.enabled=false",
                            "spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                            "spring.datasource.username=sa",
                            "spring.datasource.password=",
                            "spring.sql.init.mode=always",
                            "spring.sql.init.schema-locations=classpath:db/migration/V3__persistent_executions.sql",
                            "app.execution.poll-ms=3600000",
                            "app.security.internal-principal-secrets=" + secrets,
                            "app.security.internal-principal-active-key-id=current",
                            "app.auth.base-url=http://127.0.0.1:" + auth.getAddress().getPort(),
                            "spring.ai.mcp.client.enabled=false",
                            "spring.ai.mcp.client.toolcallback.enabled=false",
                            "app.mcp.enabled=true",
                            "app.mcp.request-timeout=200ms",
                            "app.mcp.connections.offline.url=http://127.0.0.1:1",
                            "app.mcp.connections.offline.allow-insecure-http=true",
                            "app.mcp.connections.offline.tenants[0]=tenant")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(InternalServiceClient.class);
                        assertThat(context).hasSingleBean(com.bbb.exercise.agentdemo.mcp.McpConnectionManager.class);
                        assertThat(context.getBean(com.bbb.exercise.agentdemo.mcp.McpConnectionManager.class).status()).containsKey("offline");
                        assertThat(context.getBeansOfType(org.springframework.ai.tool.ToolCallbackProvider.class)).isEmpty();
                        assertThat(context.getBeanDefinitionNames()).doesNotContain(
                                "agentWorkflowController", "agentWorkflowService", "authSessionClient");
                        var routes = context.getBean("requestMappingHandlerMapping",
                                        org.springframework.web.reactive.result.method.annotation
                                                .RequestMappingHandlerMapping.class)
                                .getHandlerMethods().keySet().stream()
                                .flatMap(mapping -> mapping.getPatternsCondition().getPatterns().stream())
                                .map(pattern -> pattern.getPatternString()).toList();
                        assertThat(routes).contains("/api/agent-executions")
                                .noneMatch(path -> path.startsWith("/api/agent-runs"));
                        var identity = context.getBean(BrowserIdentityProvider.class)
                                .session("browser-token").block(Duration.ofSeconds(5));
                        assertThat(identity).isNotNull();
                        assertThat(identity.userId()).isEqualTo("alice");
                        assertThat(identity.tenantId()).isEqualTo("tenant");
                        assertThat(identity.roles()).containsExactly("USER");
                        assertThat(identity.permissions()).containsExactly("AGENT_EXECUTE");
                        assertThat(sessionCookie.get()).isEqualTo("bbb_agent_session=browser-token");
                    });
        } finally {
            auth.stop(0);
        }
    }

    @Test
    void scansMigratedBusinessPackageForComponentsAndProperties() {
        assertThat(
                        OrchestratorApplication.class
                                .getAnnotation(SpringBootApplication.class)
                                .scanBasePackages())
                .contains("com.bbb.exercise.agentdemo.orchestrator")
                .doesNotContain("com.bbb.exercise.agentdemo1_0");
        assertThat(
                        OrchestratorApplication.class
                                .getAnnotation(ConfigurationPropertiesScan.class)
                                .basePackages())
                .contains("com.bbb.exercise.agentdemo.orchestrator")
                .doesNotContain("com.bbb.exercise.agentdemo1_0");
    }
}
