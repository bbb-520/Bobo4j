package com.bbb.exercise.agentdemo1_0.identity;

import com.bbb.exercise.agentdemo1_0.config.AppProperties;
import com.bbb.exercise.agentdemo.common.client.InternalServiceClient;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.time.Duration;
import java.util.UUID;

/** Resolves authenticated service identity, falling back to a signed anonymous cookie. */
@Component
public class ChatIdentityResolver {
    private final AppProperties properties;
    private final InternalServiceClient authClient;

    public ChatIdentityResolver(AppProperties properties, InternalServiceClient authClient) {
        this.properties = properties;
        this.authClient = authClient;
    }

    public Mono<ChatIdentity> resolve(ServerWebExchange exchange) {
        var cookie = exchange.getRequest().getCookies().getFirst(properties.getSecurity().getSessionCookieName());
        return (cookie == null ? Mono.<InternalServiceClient.Identity>empty() : authClient.session(cookie.getValue()))
                .map(user -> new ChatIdentity(user.tenantId(), user.userId(), true))
                .switchIfEmpty(exchange.getPrincipal()
                        .map(Principal::getName)
                        .filter(name -> name != null && !name.isBlank())
                        .map(name -> new ChatIdentity(properties.getSecurity().getDefaultTenantId(), name, true))
                        .switchIfEmpty(Mono.fromSupplier(() -> resolveAnonymous(exchange))));
    }

    public Mono<ChatIdentity> resolveRequired(ServerWebExchange exchange) {
        return resolve(exchange).flatMap(identity -> identity.authenticated()
                ? Mono.just(identity)
                : Mono.error(new IllegalStateException("请先登录")));
    }

    private ChatIdentity resolveAnonymous(ServerWebExchange exchange) {
        String anonymousCookieName = properties.getSecurity().getAnonymousCookieName();
        var cookie = exchange.getRequest().getCookies().getFirst(anonymousCookieName);
        String userId = cookie == null ? null : cookie.getValue();
        if (!isUuid(userId)) {
            userId = UUID.randomUUID().toString();
            boolean secure = isSecureRequest(exchange);
            exchange.getResponse().addCookie(ResponseCookie.from(anonymousCookieName, userId)
                    .httpOnly(true).sameSite(secure ? "None" : "Lax").secure(secure)
                    .path("/").maxAge(Duration.ofDays(30)).build());
        }
        return new ChatIdentity(properties.getSecurity().getDefaultTenantId(), "anonymous:" + userId, false);
    }

    private static boolean isSecureRequest(ServerWebExchange exchange) {
        String forwardedProto = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(forwardedProto)
                || "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme())
                || Boolean.parseBoolean(System.getenv().getOrDefault("FORCE_COOKIE_SECURE", "false"));
    }

    private static boolean isUuid(String value) {
        if (value == null) return false;
        try { UUID.fromString(value); return true; }
        catch (IllegalArgumentException ignored) { return false; }
    }
}
