package com.bbb.exercise.agentdemo.gateway;
import com.bbb.exercise.agentdemo.gateway.filter.EmailSourceGlobalFilter;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class EmailSourceGlobalFilterTest {
    @Test void ignoresForgedSourceHeaderAndSignsActualSocketAddress() {
        String secret="current="+Base64.getEncoder().encodeToString(new byte[32]);var captured=new AtomicReference<ServerWebExchange>();
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/email/code").remoteAddress(new InetSocketAddress("192.0.2.7",12345)).header("X-Email-Source","forged").build());
        new EmailSourceGlobalFilter(secret,"current","local").filter(exchange,e -> {captured.set(e);return Mono.empty();}).block();
        String token=captured.get().getRequest().getHeaders().getFirst("X-Email-Source");
        var source=new PrincipalKeyRing(secret,"current").verify(token,"agent-auth-service","POST /api/auth/email/code");
        assertThat(source.subject()).isEqualTo("192.0.2.7");assertThat(source.service()).isEqualTo("agent-gateway");
    }
}
