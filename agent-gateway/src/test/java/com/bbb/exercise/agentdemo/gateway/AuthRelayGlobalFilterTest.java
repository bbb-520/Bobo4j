package com.bbb.exercise.agentdemo.gateway;

import com.bbb.exercise.agentdemo.gateway.filter.AuthRelayGlobalFilter;
import com.bbb.exercise.agentdemo.gateway.filter.AuthenticatedSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRelayGlobalFilterTest {
    @Test
    void removesClientSuppliedIdentityButPreservesSessionCookie() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/agent-runs")
                .header("x-user-id", "victim", "another-victim")
                .header("X-Tenant-Id", "other-tenant")
                .header("X-Internal-Principal", "forged")
                .header("Cookie", "bbb_agent_session=session-token"));
        new AuthRelayGlobalFilter(request -> Mono.just(new AuthenticatedSession("user-1", "tenant-1")))
                .filter(exchange, forwarded -> {
            assertThat(forwarded.getRequest().getHeaders().getFirst("X-User-Id")).isNull();
            assertThat(forwarded.getRequest().getHeaders().getFirst("X-Tenant-Id")).isNull();
            assertThat(forwarded.getRequest().getHeaders().getFirst("X-Internal-Principal")).isNull();
            assertThat(forwarded.getRequest().getHeaders().getFirst("Cookie"))
                    .isEqualTo("bbb_agent_session=session-token");
            return Mono.empty();
                }).block();
    }

    /**
     * A0：除固定三个身份头外，整个 X-Principal-* 身份族也必须被剥离，
     * 否则下游一旦读这些头就等于信任了客户端。
     */
    @Test
    void removesTheWholeXPrincipalHeaderFamily() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/agent-runs")
                .header("X-Principal-User-Id", "victim")
                .header("X-Principal-Tenant-Id", "other-tenant")
                .header("X-Principal-Signature", "forged-signature")
                .header("Cookie", "bbb_agent_session=session-token"));
        new AuthRelayGlobalFilter(request -> Mono.empty()).filter(exchange, forwarded -> {
            var headers = forwarded.getRequest().getHeaders();
            assertThat(headers.toSingleValueMap().keySet())
                    .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith("x-principal"));
            assertThat(headers.getFirst("Cookie")).isEqualTo("bbb_agent_session=session-token");
            return Mono.empty();
        }).block();
    }

    @Test
    void rejectsProtectedRequestWhenSessionIsMissing() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/chat/completions")
                .header("X-User-Id", "forged"));
        var invoked = new boolean[1];

        new AuthRelayGlobalFilter(request -> Mono.empty()).filter(exchange, forwarded -> {
            invoked[0] = true;
            return Mono.empty();
        }).block();

        assertThat(invoked[0]).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    @Test
    void allowsPublicAuthEndpointsWithoutSession() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login")
                .header("X-User-Id", "forged"));
        var invoked = new boolean[1];

        new AuthRelayGlobalFilter(request -> Mono.empty()).filter(exchange, forwarded -> {
            invoked[0] = true;
            assertThat(forwarded.getRequest().getHeaders().getFirst("X-User-Id")).isNull();
            return Mono.empty();
        }).block();

        assertThat(invoked[0]).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void allowsCorsPreflightWithoutSession() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.options("/api/chat")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST"));
        var invoked = new boolean[1];

        new AuthRelayGlobalFilter(request -> Mono.empty()).filter(exchange, forwarded -> {
            invoked[0] = true;
            return Mono.empty();
        }).block();

        assertThat(invoked[0]).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
