package com.bbb.exercise.agentdemo1_0.auth;

import com.bbb.exercise.agentdemo1_0.identity.ChatIdentityResolver;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/settings/keys")
public class UserApiKeyController {
    private final ChatIdentityResolver identities;
    private final UserApiKeyService keys;

    public UserApiKeyController(ChatIdentityResolver identities, UserApiKeyService keys) {
        this.identities = identities;
        this.keys = keys;
    }

    @GetMapping
    public Mono<UserApiKeyService.KeyStatus> status(ServerWebExchange exchange) {
        return identities.resolveRequired(exchange)
                .map(identity -> keys.status(identity))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @PutMapping
    public Mono<Map<String, Boolean>> save(@RequestBody ApiKeys body, ServerWebExchange exchange) {
        return identities.resolveRequired(exchange)
                .doOnNext(identity -> keys.save(identity, body.qwenApiKey()))
                .thenReturn(Map.of("saved", true))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @DeleteMapping
    public Mono<Void> clear(ServerWebExchange exchange) {
        return identities.resolveRequired(exchange)
                .doOnNext(keys::clear).then()
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @DeleteMapping("/{provider}")
    public Mono<Void> clearProvider(@PathVariable String provider, ServerWebExchange exchange) {
        return identities.resolveRequired(exchange)
                .doOnNext(identity -> keys.clearProvider(identity, provider)).then()
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    public record ApiKeys(String qwenApiKey) {}
}
