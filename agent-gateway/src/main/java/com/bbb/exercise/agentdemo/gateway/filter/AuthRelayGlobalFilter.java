package com.bbb.exercise.agentdemo.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class AuthRelayGlobalFilter implements GlobalFilter, Ordered {
    private static final String USER_ID = "X-User-Id";
    private static final String TENANT_ID = "X-Tenant-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest.Builder request = exchange.getRequest().mutate();
        // Browser headers are not authenticated principals. Preserve cookies for
        // session validation by services, but never relay caller-supplied identity.
        request.headers(headers -> {
            headers.remove(USER_ID);
            headers.remove(TENANT_ID);
            headers.remove("X-Internal-Principal");
        });
        return chain.filter(exchange.mutate().request(request.build()).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
