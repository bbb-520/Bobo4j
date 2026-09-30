package com.bbb.exercise.agentdemo1_0.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Stops legacy business writes after traffic has moved to domain services. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LegacyReadOnlyWebFilter implements WebFilter {
    private final boolean readOnly;

    public LegacyReadOnlyWebFilter(@Value("${app.legacy.read-only:true}") boolean readOnly) {
        this.readOnly = readOnly;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!readOnly || isAllowedAuth(exchange) || isSafe(exchange.getRequest().getMethod())) return chain.filter(exchange);
        exchange.getResponse().setStatusCode(HttpStatus.GONE);
        return exchange.getResponse().setComplete();
    }

    private static boolean isSafe(HttpMethod method) { return method == null || method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS; }
    private static boolean isAllowedAuth(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return "/api/auth/login".equals(path) || "/api/auth/register".equals(path) || "/api/auth/logout".equals(path);
    }
}
