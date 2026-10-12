package com.bbb.exercise.agentdemo.runtime.security;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.security.web.server.csrf.CsrfToken;
import reactor.core.publisher.Mono;
import java.util.*;

/** 验证浏览器接口需要有效会话、当前权限，并对流式及文件上传请求执行 CSRF 校验。 */
class BrowserSecurityTest {
    AnnotationConfigApplicationContext context;
    WebTestClient client;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext();
        context.register(TestConfig.class, BrowserSecurityConfiguration.class);
        context.refresh();
        client = WebTestClient.bindToApplicationContext(context).configureClient().build();
    }

    @AfterEach
    void close() {
        context.close();
    }

    /** 浏览器安全集成测试配置，提供受控用户身份和测试路由。 */
    @Configuration
    @EnableWebFlux
    static class TestConfig {
        @Bean
        BrowserIdentityProvider identities() {
            return token ->
                    "user".equals(token)
                            ? Mono.just(
                                    new BrowserIdentityProvider.BrowserUser(
                                            "uuid", "local", Set.of("USER"), Set.of("chat:use", "payment:manage")))
                            : "refund-admin".equals(token)
                                    ? Mono.just(new BrowserIdentityProvider.BrowserUser("operator", "local", Set.of("ADMIN"), Set.of("billing:refund")))
                            : Mono.empty();
        }

        @Bean
        Routes routes() {
            return new Routes();
        }
    }

    /** 浏览器安全测试端点，覆盖 CSRF、普通业务、管理操作和未声明路径。 */
    @RestController
    static class Routes {
        @GetMapping("/api/auth/csrf")
        Mono<Map<String, String>> csrf(ServerWebExchange ex) {
            Mono<CsrfToken> t = ex.getAttribute(CsrfToken.class.getName());
            return t.map(token -> Map.of("token", token.getToken()));
        }

        @GetMapping("/api/chat")
        String get() {
            return "ok";
        }

        @PostMapping("/api/chat")
        String post() {
            return "ok";
        }

        @GetMapping("/api/admin/users")
        String admin() {
            return "unsafe";
        }

        @GetMapping("/api/unknown")
        String unknown() {
            return "unsafe";
        }

        @RequestMapping("/api/payments/admin/orders/{id}/refund")
        String refund() { return "refund"; }
    }

    @Test
    void requiresRealSessionAndCurrentPermission() {
        client.get()
                .uri("/api/chat")
                .header("X-Roles", "SUPER_ADMIN")
                .header("X-Permissions", "chat:use")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get()
                .uri("/api/chat")
                .cookie("bbb_agent_session", "user")
                .exchange()
                .expectStatus()
                .isOk();
        client.get()
                .uri("/api/admin/users")
                .cookie("bbb_agent_session", "user")
                .header("X-Permissions", "user:read")
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get()
                .uri("/api/unknown")
                .cookie("bbb_agent_session", "user")
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get()
                .uri("/api/chat")
                .cookie("bbb_agent_session", "invalid")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void ordinaryPayerCannotRefundAndAdminStillNeedsCsrf() {
        client.get().uri("/api/payments/admin/orders/order/refund")
                .cookie("bbb_agent_session", "user").exchange().expectStatus().isForbidden();
        client.get().uri("/api/payments/admin/orders/order/refund")
                .cookie("bbb_agent_session", "refund-admin").exchange().expectStatus().isOk();
        client.post().uri("/api/payments/admin/orders/order/refund")
                .cookie("bbb_agent_session", "refund-admin").exchange().expectStatus().isForbidden();
    }

    @Test
    void csrfIsRequiredForStreamingAndMultipartPost() {
        client.post()
                .uri("/api/chat")
                .cookie("bbb_agent_session", "user")
                .exchange()
                .expectStatus()
                .isForbidden();
        var result =
                client.get()
                        .uri("/api/auth/csrf")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult();
        String token = (String) result.getResponseBody().get("token");

        client.post()
                .uri("/api/chat")
                .cookie("bbb_agent_session", "user")
                .cookie("XSRF-TOKEN", token)
                .header("X-XSRF-TOKEN", "forged")
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri("/api/chat")
                .cookie("bbb_agent_session", "user")
                .cookie("XSRF-TOKEN", token)
                .header("X-XSRF-TOKEN", token)
                .exchange()
                .expectStatus()
                .isOk();
    }
}
