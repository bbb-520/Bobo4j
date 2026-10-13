package com.bbb.exercise.agentdemo.gateway.config;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.http.codec.ServerCodecConfigurer;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class GatewayTrafficTest {
    @Test void configuredSentinelRuleRejectsTrafficBeforeDownstreamAndEmitsMetrics() {
        var props=new GatewayTrafficProperties();props.setRouteQps(1);props.setIntervalSeconds(60);
        String route="limited-"+UUID.randomUUID();props.setRoutes(Map.of(route,1d/60));
        var metrics=new SimpleMeterRegistry();var configuration=new GatewayTrafficConfiguration(props,metrics,ObservationRegistry.NOOP);
        configuration.install();var filter=new SentinelGatewayFilter();var downstream=new AtomicInteger();
        var errors=new SentinelGatewayBlockExceptionHandler(List.of(),ServerCodecConfigurer.create());
        try {
            for(int i=0;i<2;i++) {
                var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/test"));
                exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,Route.async().id(route).uri("http://localhost").predicate(e->true).build());
                filter.filter(exchange,e->Mono.fromRunnable(downstream::incrementAndGet)).onErrorResume(t->errors.handle(exchange,t)).block();
                if(i==1){assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(429);assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");}
            }
            assertThat(downstream.get()).isEqualTo(1);assertThat(metrics.get("gateway.traffic.blocked").tag("route",route).counter().count()).isEqualTo(1);
        } finally {GatewayRuleManager.loadRules(Set.of());GatewayCallbackManager.resetBlockHandler();}
    }
}
