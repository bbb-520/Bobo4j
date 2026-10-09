package com.bbb.exercise.agentdemo.orchestrator.execution;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.dto.UserIdentityDto;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.time.Duration;
@Component
public class ExecutionIdentity {
    private final WebClient auth;private final PrincipalKeyRing keys;private final String cookie,tenant;
    public ExecutionIdentity(WebClient.Builder builder,PrincipalKeyRing keys,
        @Value("${app.auth.base-url:http://127.0.0.1:18081}") String url,
        @Value("${app.auth.session-cookie:bbb_agent_session}") String cookie,
        @Value("${app.security.default-tenant-id:local}") String tenant) {
        this.auth=builder.clone().baseUrl(url).build();this.keys=keys;this.cookie=cookie;this.tenant=tenant;
    }
    public Mono<ChatIdentity> require(ServerWebExchange exchange) {
        var cookies=exchange.getRequest().getCookies().get(cookie);
        if(cookies==null||cookies.size()!=1||cookies.getFirst().getValue().isBlank()||cookies.getFirst().getValue().length()>512)return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录"));
        String path="/internal/auth/session";
        return Mono.defer(()->auth.get().uri(path).cookie(cookie,cookies.getFirst().getValue())
            .header("X-Internal-Principal",keys.sign("agent-orchestrator-service","session",tenant,"agent-auth-service","GET "+path))
            .retrieve().bodyToMono(UserIdentityDto.class)).timeout(Duration.ofSeconds(5))
            .filter(UserIdentityDto::authenticated).map(i->new ChatIdentity(i.tenantId(),i.userId(),true))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录")))
            .onErrorMap(org.springframework.web.reactive.function.client.WebClientResponseException.class,e->new ResponseStatusException(e.getStatusCode().value()==401?HttpStatus.UNAUTHORIZED:HttpStatus.SERVICE_UNAVAILABLE,"身份服务暂不可用"));
    }
}
