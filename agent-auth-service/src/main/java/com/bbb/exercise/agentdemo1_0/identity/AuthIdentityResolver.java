package com.bbb.exercise.agentdemo1_0.identity;

import com.bbb.exercise.agentdemo1_0.auth.AuthService;
import com.bbb.exercise.agentdemo1_0.config.AppProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.UUID;

/** Auth-owned resolver; other services use the shared resolver and signed Auth calls. */
@Component
public class AuthIdentityResolver {
    private final AppProperties properties;
    private final AuthService authService;
    public AuthIdentityResolver(AppProperties properties, AuthService authService) { this.properties = properties; this.authService = authService; }
    public Mono<ChatIdentity> resolve(ServerWebExchange exchange) {
        return Mono.fromCallable(() -> authService.resolve(exchange)).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .filter(java.util.Objects::nonNull).switchIfEmpty(Mono.fromSupplier(() -> resolveAnonymous(exchange)));
    }
    public Mono<ChatIdentity> resolveRequired(ServerWebExchange exchange) {
        return resolve(exchange).flatMap(i -> i.authenticated() ? Mono.just(i) : Mono.error(new AuthService.AuthException(401, "请先登录")));
    }
    private ChatIdentity resolveAnonymous(ServerWebExchange exchange) {
        String name = properties.getSecurity().getAnonymousCookieName();
        var cookie = exchange.getRequest().getCookies().getFirst(name);
        String id = cookie == null ? null : cookie.getValue();
        if (!isUuid(id)) { id = UUID.randomUUID().toString(); exchange.getResponse().addCookie(ResponseCookie.from(name, id).httpOnly(true).sameSite("Lax").secure(false).path("/").maxAge(Duration.ofDays(30)).build()); }
        return new ChatIdentity(properties.getSecurity().getDefaultTenantId(), "anonymous:" + id, false);
    }
    private static boolean isUuid(String value) { if (value == null) return false; try { UUID.fromString(value); return true; } catch (IllegalArgumentException ignored) { return false; } }
}
