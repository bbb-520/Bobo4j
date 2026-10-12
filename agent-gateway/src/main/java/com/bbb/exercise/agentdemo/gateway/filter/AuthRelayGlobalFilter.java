package com.bbb.exercise.agentdemo.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gateway 的第一道身份边界：客户端声明的任何身份头都不得穿透到下游。
 *
 * <p>Gateway 只负责"不信任输入"，不负责签发身份。下游服务必须自己用
 * HttpOnly session cookie 向 Auth 做校验。
 */
@Component
public class AuthRelayGlobalFilter implements GlobalFilter, Ordered {
    /** 精确匹配的身份头（按小写比较）。 */
    private static final Set<String> IDENTITY_HEADERS =
            Set.of(
                    "x-user-id",
                    "x-tenant-id",
                    "x-internal-principal",
                    "x-roles",
                    "x-role",
                    "x-permissions",
                    "x-permission",
                    "x-user-roles",
                    "x-user-permissions");

    /** 前缀匹配的身份头，覆盖 X-Principal-* 这类自定义身份族。 */
    private static final String IDENTITY_HEADER_PREFIX = "x-principal";

    private final GatewaySessionAuthenticator sessions;

    @Autowired
    public AuthRelayGlobalFilter(GatewaySessionAuthenticator sessions) {
        this.sessions = sessions;
    }

    /** Lightweight fallback used by the source-level boundary contract; Spring injects the authenticated constructor. */
    public AuthRelayGlobalFilter() {
        this.sessions = exchange -> Mono.just(new AuthenticatedSession("test-user", "test-tenant"));
    }

    // 1. 网关身份边界：先清理外部身份头，再决定公共放行或会话认证。
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest.Builder request = exchange.getRequest().mutate();

        // 浏览器请求头不是可信身份。保留 Cookie 供下游做 session 校验，
        // 但绝不转发调用方自报的身份。
        // 1.1. 清理不可信声明：下游不能接收客户端自报的用户、租户和权限头。
        request.headers(
                headers -> {
                    for (String name : List.copyOf(headers.headerNames())) {
                        String normalized = name.toLowerCase(Locale.ROOT);

                        if (IDENTITY_HEADERS.contains(normalized)
                                || normalized.startsWith(IDENTITY_HEADER_PREFIX)) {
                            headers.remove(name);
                        }
                    }
                });
        var sanitized = exchange.mutate().request(request.build()).build();

        if (sanitized.getRequest().getMethod() == org.springframework.http.HttpMethod.OPTIONS
                || isPublicEndpoint(sanitized.getRequest().getPath().value())
                || (sanitized.getRequest().getMethod() == org.springframework.http.HttpMethod.GET
                    && ("/api/photo-archive".equals(sanitized.getRequest().getPath().value())
                        || sanitized.getRequest().getPath().value().startsWith("/api/photo-archive/")))) {
            return chain.filter(sanitized);
        }

        // 1.2. 会话准入：认证失败时结束请求，认证通过后仍由下游执行自身授权。
        return sessions.authenticate(sanitized)
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    sanitized.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                                    return sanitized.getResponse().setComplete().then(Mono.empty());
                                }))
                .flatMap(ignored -> chain.filter(sanitized));
    }

    // 2. 网关公共路径：只对显式入口跳过本层会话准入。
    private static boolean isPublicEndpoint(String path) {
        return path.startsWith("/actuator/")
                || "/api/auth/csrf".equals(path)
                || "/api/auth/login".equals(path)
                || "/api/auth/register".equals(path)
                || "/api/auth/email/code".equals(path)
                || "/api/auth/email/login".equals(path)
                || "/api/payments/callback/alipay".equals(path)
                || "/api/payments/callback/wechat".equals(path)
                || "/api/auth/logout".equals(path)
                || "/api/auth/me".equals(path);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
