package com.bbb.exercise.agentdemo1_0.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;

class LegacyReadOnlyWebFilterTest {
    @Test
    void blocksLegacyBusinessWritesButKeepsAuthenticationAvailable() {
        var filter = new LegacyReadOnlyWebFilter(true);
        var write = MockServerWebExchange.from(MockServerHttpRequest.post("/api/chat").build());
        filter.filter(write, exchange -> Mono.empty()).block();
        assertThat(write.getResponse().getStatusCode().value()).isEqualTo(410);

        var login = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login").build());
        var called = new boolean[1];
        filter.filter(login, exchange -> { called[0] = true; return Mono.empty(); }).block();
        assertThat(called[0]).isTrue();
    }
}
