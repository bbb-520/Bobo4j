package com.bbb.exercise.agentdemo.gateway.filter;

import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Calls Auth's private session endpoint; the browser cookie never becomes a trusted identity header. */
@Component
public final class HttpGatewaySessionAuthenticator implements GatewaySessionAuthenticator {
    private final WebClient auth;
    private final PrincipalKeyRing keys;
    private final String caller;
    private final String cookieName;
    private final String tenant;

    public HttpGatewaySessionAuthenticator(
            WebClient.Builder builder,
            @Value("${app.auth.base-url:http://127.0.0.1:18081}") String authUrl,
            @Value("${spring.application.name:agent-gateway}") String caller,
            @Value("${app.security.internal-principal-secrets:}") String secrets,
            @Value("${app.security.internal-principal-active-key-id:current}") String active,
            @Value("${app.security.session-cookie-name:bbb_agent_session}") String cookieName,
            @Value("${app.security.default-tenant-id:local}") String tenant) {
        this.auth = builder.clone().baseUrl(authUrl).build();
        this.keys = new PrincipalKeyRing(secrets, active);
        this.caller = caller;
        this.cookieName = cookieName;
        this.tenant = tenant;
    }

    @Override
    public Mono<AuthenticatedSession> authenticate(ServerWebExchange exchange) {
        var cookie = exchange.getRequest().getCookies().getFirst(cookieName);
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()
                || cookie.getValue().length() > 512) {
            return Mono.empty();
        }
        String path = "/internal/auth/session";
        String principal = keys.sign(caller, "session", tenant, "agent-auth-service", "GET " + path);
        return auth.get().uri(path)
                .cookie(cookieName, cookie.getValue())
                .header("X-Internal-Principal", principal)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(AuthSessionResponse.class)
                                .filter(body -> body.authenticated() && body.userId() != null && !body.userId().isBlank())
                                .map(body -> new AuthenticatedSession(body.userId(), body.tenantId()));
                    }
                    return response.releaseBody().then(Mono.empty());
                })
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(ResponseStatusException.class, error ->
                        error.getStatusCode() == HttpStatus.UNAUTHORIZED ? Mono.empty() : Mono.error(error))
                .onErrorMap(error -> !(error instanceof ResponseStatusException), error ->
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "认证服务暂不可用", error));
    }

    private record AuthSessionResponse(String userId, String tenantId, boolean authenticated) {}
}
