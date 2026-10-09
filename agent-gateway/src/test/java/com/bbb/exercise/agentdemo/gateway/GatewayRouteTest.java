package com.bbb.exercise.agentdemo.gateway;

import com.bbb.exercise.agentdemo.gateway.config.GatewayRoutes;
import com.bbb.exercise.agentdemo.gateway.config.GatewayCorsConfiguration;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import reactor.core.publisher.Mono;

class GatewayRouteTest {
    @Test
    void declaresRoutesForCoreDomains() {
        assertThat(GatewayRoutes.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("agentRoutes"));
    }

    @Test
    void routesMatchThePublicControllerPrefixes() {
        assertThat(Arrays.asList(GatewayRoutes.AUTH_PATHS))
                .contains("/api/auth/**", "/api/settings/keys/**", "/api/settings/models/**")
                .doesNotContain("/api/settings/**");
        assertThat(Arrays.asList(GatewayRoutes.CHAT_PATHS))
                .containsExactly("/api/chat/**", "/api/settings/visual-memory/**");
        assertThat(Arrays.asList(GatewayRoutes.MEDIA_PATHS))
                .contains("/api/image-assets/**", "/api/image-jobs/**")
                .doesNotContain("/api/images/**");
        assertThat(Arrays.asList(GatewayRoutes.CONTENT_PATHS))
                .containsExactly("/api/bobo/**", "/api/zine/**");
    }

    @Test
    void respondsToAllowedApiPreflightWithCorsHeaders() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.options("http://localhost:18000/api/chat")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"));

        new GatewayCorsConfiguration().corsWebFilter().filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo("http://localhost:3000");
        assertThat(exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Credentials"))
                .isEqualTo("true");
    }
}
