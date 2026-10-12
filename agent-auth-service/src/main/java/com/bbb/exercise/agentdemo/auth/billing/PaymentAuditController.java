package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.auth.identity.AuthIdentityResolver;
import com.bbb.exercise.agentdemo.auth.rbac.RbacService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/payments/admin")
public class PaymentAuditController {
    private final AuthService auth;private final AuthIdentityResolver identities;private final RbacService rbac;private final PaymentAuditService audit;
    public PaymentAuditController(AuthService auth,AuthIdentityResolver identities,RbacService rbac,PaymentAuditService audit) {
        this.auth=auth;this.identities=identities;this.rbac=rbac;this.audit=audit;
    }
    private <T> Mono<T> authorized(ServerWebExchange exchange,Supplier<T> action) {
        return identities.resolveRequired(exchange).flatMap(identity->Mono.fromCallable(()->{
            rbac.require(auth.requireUserId(identity),"billing:reconcile");return action.get();
        }).subscribeOn(Schedulers.boundedElastic()));
    }
    @GetMapping("/reconciliation")
    public Mono<List<Map<String,Object>>> report(@RequestParam(defaultValue="50") int limit,ServerWebExchange exchange) {
        return authorized(exchange,()->audit.report(limit));
    }
    @GetMapping("/orders/{id}/audit")
    public Mono<Map<String,Object>> audit(@PathVariable String id,ServerWebExchange exchange) {
        return authorized(exchange,()->audit.audit(id));
    }
}
