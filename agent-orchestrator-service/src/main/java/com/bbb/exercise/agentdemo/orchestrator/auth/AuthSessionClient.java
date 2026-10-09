package com.bbb.exercise.agentdemo.orchestrator.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Session introspection against the Auth service; never trusts identity headers. */
@Component
public class AuthSessionClient {
    private final WebClient client;
    private final String cookieName;

    public AuthSessionClient(@Value("${app.auth.base-url:http://127.0.0.1:18081}") String baseUrl,
                             @Value("${app.auth.session-cookie:bbb_agent_session}") String cookieName) {
        this.client = WebClient.builder().baseUrl(baseUrl).build();
        this.cookieName = cookieName;
    }

    public Mono<String> requireUser(ServerWebExchange exchange) {
        var cookies = exchange.getRequest().getCookies().get(cookieName);
        if (cookies == null || cookies.size() != 1 || cookies.getFirst().getValue().isBlank()) {
            return Mono.error(unauthorized());
        }
        String token = cookies.getFirst().getValue();
        if (token.length() > 512) return Mono.error(unauthorized());
        return client.get().uri("/api/auth/me").cookie(cookieName, token)
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == 401 || response.statusCode().value() == 403) {
                        return response.releaseBody().then(Mono.<Session>error(unauthorized()));
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().then(Mono.<Session>error(unavailable()));
                    }
                    return response.bodyToMono(Session.class);
                })
                .timeout(Duration.ofSeconds(3))
                .switchIfEmpty(Mono.error(unavailable()))
                .flatMap(session -> Boolean.TRUE.equals(session.authenticated())
                        && session.userId() != null && !session.userId().isBlank()
                        ? Mono.just(session.userId()) : Mono.error(unauthorized()))
                .onErrorMap(error -> !(error instanceof ResponseStatusException), error -> unavailable());
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "有效登录会话是必需的");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "身份服务暂不可用");
    }

    public record Session(Boolean authenticated, String userId, String username) {}
}
