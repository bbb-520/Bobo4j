package com.bbb.exercise.agentdemo.auth;

import com.bbb.exercise.agentdemo.auth.identity.AuthIdentityResolver;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/** 用户密钥接口，提供托管凭据状态查询及密钥变更策略的统一入口。 */
@RestController
@RequestMapping("/api/settings/keys")
public class UserApiKeyController {
    private final AuthIdentityResolver identities;
    private final UserApiKeyService keys;

    public UserApiKeyController(AuthIdentityResolver identities, UserApiKeyService keys) {
        this.identities = identities;
        this.keys = keys;
    }

    // 1. 凭据展示：只提供当前配置状态，写入和删除入口保留兼容性错误响应。
    @GetMapping
    public Mono<UserApiKeyService.KeyStatus> status(ServerWebExchange exchange) {
        return identities
                .resolveRequired(exchange)
                .map(identity -> keys.status(identity))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @PutMapping
    public Mono<Map<String, Boolean>> save(@RequestBody ApiKeys body, ServerWebExchange exchange) {
        return identities
                .resolveRequired(exchange)
                .doOnNext(identity -> keys.save(identity, body.qwenApiKey()))
                .thenReturn(Map.of("saved", true))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @DeleteMapping
    public Mono<Void> clear(ServerWebExchange exchange) {
        return identities
                .resolveRequired(exchange)
                .doOnNext(keys::clear)
                .then()
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @DeleteMapping("/{provider}")
    public Mono<Void> clearProvider(@PathVariable String provider, ServerWebExchange exchange) {
        return identities
                .resolveRequired(exchange)
                .doOnNext(identity -> keys.clearProvider(identity, provider))
                .then()
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /** 用户密钥提交参数，承接千问密钥字段。 */
    public record ApiKeys(String qwenApiKey) {}
}
