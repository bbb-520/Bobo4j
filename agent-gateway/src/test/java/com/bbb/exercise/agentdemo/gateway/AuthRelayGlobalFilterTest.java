package com.bbb.exercise.agentdemo.gateway;

import com.bbb.exercise.agentdemo.gateway.filter.AuthRelayGlobalFilter;
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
        new AuthRelayGlobalFilter().filter(exchange, forwarded -> {
            assertThat(forwarded.getRequest().getHeaders().getFirst("X-User-Id")).isNull();
            assertThat(forwarded.getRequest().getHeaders().getFirst("X-Tenant-Id")).isNull();
            assertThat(forwarded.getRequest().getHeaders().getFirst("X-Internal-Principal")).isNull();
            assertThat(forwarded.getRequest().getHeaders().getFirst("Cookie"))
                    .isEqualTo("bbb_agent_session=session-token");
            return Mono.empty();
        }).block();
    }
}
