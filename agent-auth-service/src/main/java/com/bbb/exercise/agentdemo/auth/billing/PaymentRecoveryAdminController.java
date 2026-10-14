package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.auth.identity.AuthIdentityResolver;
import com.bbb.exercise.agentdemo.auth.rbac.RbacService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.function.Function;

/** Uses the existing billing admin HTTP policy, CSRF and trusted server-side permission check. */
@RestController
@RequestMapping("/api/billing/admin/payment-orders/{id}")
public class PaymentRecoveryAdminController {
    private final AuthService auth;
    private final AuthIdentityResolver identities;
    private final RbacService rbac;
    private final PaymentService orders;
    public PaymentRecoveryAdminController(AuthService auth,AuthIdentityResolver identities,RbacService rbac,PaymentService orders) {
        this.auth=auth;this.identities=identities;this.rbac=rbac;this.orders=orders;
    }
    private <T> Mono<T> authorized(ServerWebExchange exchange,Function<String,T> action) {
        return identities.resolveRequired(exchange).flatMap(identity -> Mono.fromCallable(() -> {
            rbac.require(auth.requireUserId(identity),"billing:reconcile");
            return action.apply(auth.publicUserId(identity));
        }).subscribeOn(Schedulers.boundedElastic()));
    }
    @GetMapping
    public Mono<PaymentService.Order> get(@PathVariable String id,ServerWebExchange exchange) {
        return authorized(exchange,actor -> orders.find(id));
    }
    @PostMapping("/resume")
    public Mono<PaymentService.Order> resume(@PathVariable String id,@RequestBody Reason body,ServerWebExchange exchange) {
        return authorized(exchange,actor -> orders.resumeReconciliation(id,actor,body.reason()));
    }
    public record Reason(String reason) {}
}
