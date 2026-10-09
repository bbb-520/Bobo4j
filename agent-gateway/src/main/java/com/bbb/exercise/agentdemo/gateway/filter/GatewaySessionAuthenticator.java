package com.bbb.exercise.agentdemo.gateway.filter;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Gateway-side authentication port; implementations must validate the session with Auth. */
@FunctionalInterface
public interface GatewaySessionAuthenticator {
    Mono<AuthenticatedSession> authenticate(ServerWebExchange exchange);
}
