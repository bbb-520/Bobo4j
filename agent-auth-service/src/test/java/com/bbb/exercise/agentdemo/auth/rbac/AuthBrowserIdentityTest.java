package com.bbb.exercise.agentdemo.auth.rbac;

import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.runtime.security.BrowserSecurityConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.mockito.Mockito.*;

/** 验证认证服务浏览器身份边界拒绝没有有效角色的账户。 */
class AuthBrowserIdentityTest {
    /** 浏览器身份边界测试配置，提供受控认证服务和测试路由。 */
    @Configuration
    @EnableWebFlux
    static class Config {
        @Bean
        AuthService auth() {
            var auth = mock(AuthService.class);

            when(auth.resolveSessionToken("roleless"))
                    .thenThrow(new AuthService.AuthException(403, "账户未配置有效角色"));

            return auth;
        }

        @Bean
        Routes routes() {
            return new Routes();
        }
    }

    /** 身份边界测试用接口，用于观察当前账户是否可以通过安全过滤链。 */
    @RestController
    static class Routes {
        @GetMapping("/api/auth/me")
        String me() {
            return "unsafe";
        }
    }

    @Test
    void identityFilterReturnsForbiddenForAccountWithoutEnabledRoles() {
        try (var context =
                new AnnotationConfigApplicationContext(
                        Config.class,
                        AuthBrowserIdentityConfiguration.class,
                        BrowserSecurityConfiguration.class)) {
            var client = WebTestClient.bindToApplicationContext(context).configureClient().build();

            client.get()
                    .uri("/api/auth/me")
                    .cookie("bbb_agent_session", "roleless")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
            client.get()
                    .uri("/api/auth/me")
                    .cookie("bbb_agent_session", "invalid")
                    .exchange()
                    .expectStatus()
                    .isUnauthorized();
        }
    }
}
