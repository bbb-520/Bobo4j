package com.bbb.exercise.agentdemo.gateway;

import com.bbb.exercise.agentdemo.gateway.config.GatewayRoutes;
import com.bbb.exercise.agentdemo.gateway.config.GatewayCorsConfiguration;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import reactor.core.publisher.Mono;

/** 验证网关覆盖核心业务路由及允许来源的跨域预检响应。 */
class GatewayRouteTest {
    @Test
    void declaresRoutesForCoreDomains() {
        assertThat(GatewayRoutes.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("agentRoutes"));
    }

    @Test
    void routesMatchThePublicControllerPrefixes() {
        assertThat(Arrays.asList(GatewayRoutes.AUTH_PATHS))
                .contains(
                        "/api/auth/**",
                        "/api/admin/**",
                        "/api/settings/keys/**",
                        "/api/settings/models/**")
                .doesNotContain("/api/settings/**");
        assertThat(Arrays.asList(GatewayRoutes.CHAT_PATHS))
                .containsExactly("/api/chat/**", "/api/settings/visual-memory/**", "/api/conversations/**");
        assertThat(Arrays.asList(GatewayRoutes.MEDIA_PATHS))
                .contains("/api/image-assets/**", "/api/image-jobs/**")
                .doesNotContain("/api/images/**");
        assertThat(Arrays.asList(GatewayRoutes.CONTENT_PATHS))
                .containsExactly("/api/bobo/**", "/api/zine/**", "/api/photo-archive/**");
    }

    @Test
    void respondsToAllowedApiPreflightWithCorsHeaders() {
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.options("http://localhost:18000/api/chat")
                                .header("Origin", "http://localhost:3000")
                                .header("Access-Control-Request-Method", "POST")
                                .header("Access-Control-Request-Headers", "content-type"));

        new GatewayCorsConfiguration()
                .corsWebFilter()
                .filter(exchange, ignored -> Mono.empty())
                .block();

        assertThat(exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo("http://localhost:3000");
        assertThat(exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Credentials"))
                .isEqualTo("true");
    }

    @Test
    void allowsProductionOriginPatchPreflightForBoboItemEditing() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.options("http://agent-gateway:18000/api/bobo/items/fixture-item")
                        .header("Origin", "https://www.boboo.xin")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"));
        var forwarded = new boolean[1];

        new GatewayCorsConfiguration().corsWebFilter()
                .filter(exchange, ignored -> { forwarded[0] = true; return Mono.empty(); }).block();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowMethods())
                .contains(org.springframework.http.HttpMethod.PATCH);
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("https://www.boboo.xin");
        assertThat(forwarded[0]).isFalse(); // Preflight never edits a work.
    }

    @Test
    void forwardsActualBoboPatchToTheExistingAuthenticationAndAuthorizationChain() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.patch("http://agent-gateway:18000/api/bobo/items/fixture-item")
                        .header("Origin", "https://www.boboo.xin")
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", "fixture-csrf-token")
                        .body("{\"caption\":\"updated\",\"version\":1}"));
        var forwarded = new boolean[1];

        new GatewayCorsConfiguration().corsWebFilter().filter(exchange, downstream -> {
            forwarded[0] = true;
            assertThat(downstream.getRequest().getMethod()).isEqualTo(org.springframework.http.HttpMethod.PATCH);
            assertThat(downstream.getRequest().getHeaders().getFirst("X-XSRF-TOKEN"))
                    .isEqualTo("fixture-csrf-token");
            // Represent a downstream auth denial: CORS must not bypass or replace it.
            downstream.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return downstream.getResponse().setComplete();
        }).block();

        assertThat(forwarded[0]).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void stillRejectsUntrustedOriginsAndUnsupportedMethods() {
        for (var originAndMethod : new String[][] {
                {"https://untrusted.example", "PATCH"}, {"https://www.boboo.xin", "TRACE"}}) {
            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.options("http://agent-gateway:18000/api/bobo/items/fixture-item")
                            .header("Origin", originAndMethod[0])
                            .header("Access-Control-Request-Method", originAndMethod[1]));
            var forwarded = new boolean[1];
            new GatewayCorsConfiguration().corsWebFilter()
                    .filter(exchange, ignored -> { forwarded[0] = true; return Mono.empty(); }).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(forwarded[0]).isFalse();
        }
    }
}
