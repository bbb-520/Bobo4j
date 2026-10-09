package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.auth.identity.AuthIdentityResolver;
import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/billing")
public class BillingController {
    private final AuthService auth;private final AuthIdentityResolver identities;private final BillingService billing;
    public BillingController(AuthService auth,AuthIdentityResolver identities,BillingService billing) {this.auth=auth;this.identities=identities;this.billing=billing;}
    @GetMapping("/wallet") public Mono<Wallet> wallet(ServerWebExchange exchange) {
        return identities.resolveRequired(exchange).flatMap(i -> Mono.fromCallable(() -> billing.wallet(auth.publicUserId(i))).subscribeOn(Schedulers.boundedElastic()));
    }
    @GetMapping("/usage") public Mono<List<Usage>> usage(@RequestParam(defaultValue="20") int limit,ServerWebExchange exchange) {
        return identities.resolveRequired(exchange).flatMap(i -> Mono.fromCallable(() -> billing.usage(auth.publicUserId(i),limit)).subscribeOn(Schedulers.boundedElastic()));
    }
    @GetMapping(value="/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Wallet>> events(ServerWebExchange exchange) {
        return Flux.interval(Duration.ZERO,Duration.ofSeconds(3)).take(Duration.ofHours(1))
                .concatMap(tick -> wallet(exchange)).distinctUntilChanged()
                .map(w -> ServerSentEvent.builder(w).event("balance").build());
    }
}
