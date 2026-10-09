package com.bbb.exercise.agentdemo.gateway.filter;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Preserve source IP across Gateway -> Auth without trusting a browser-supplied forwarding header. */
@Component
public class EmailSourceGlobalFilter implements GlobalFilter,Ordered {
    private final PrincipalKeyRing keys;private final String tenant;
    public EmailSourceGlobalFilter(@Value("${app.security.internal-principal-secrets:}") String secrets,
                                   @Value("${app.security.internal-principal-active-key-id:current}") String active,
                                   @Value("${app.security.default-tenant-id:local}") String tenant) {this.keys=new PrincipalKeyRing(secrets,active);this.tenant=tenant;}
    @Override public Mono<Void> filter(ServerWebExchange exchange,GatewayFilterChain chain) {
        var builder=exchange.getRequest().mutate().headers(h -> h.remove("X-Email-Source"));
        if("/api/auth/email/code".equals(exchange.getRequest().getPath().value())) {
            var remote=exchange.getRequest().getRemoteAddress();String ip=remote==null?"unknown":remote.getAddress().getHostAddress();
            builder.header("X-Email-Source",keys.sign("agent-gateway",ip,tenant,"agent-auth-service","POST /api/auth/email/code"));
        }
        return chain.filter(exchange.mutate().request(builder.build()).build());
    }
    @Override public int getOrder() {return Ordered.HIGHEST_PRECEDENCE+11;}
}
