package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.auth.identity.AuthIdentityResolver;
import com.bbb.exercise.agentdemo.auth.payment.PaymentReconciliation;
import com.bbb.exercise.agentdemo.auth.rbac.RbacService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Original-channel refunds require operator permission in both HTTP policy and Auth service. */
@RestController
@RequestMapping("/api/payments/admin/orders/{id}/refund")
public class PaymentAdminController {
    private final AuthService auth;
    private final AuthIdentityResolver identities;
    private final RbacService rbac;
    private final PaymentReconciliation recovery;
    private final PaymentRefundService refunds;
    public PaymentAdminController(AuthService auth, AuthIdentityResolver identities, RbacService rbac,
                                  PaymentReconciliation recovery, PaymentRefundService refunds) {
        this.auth=auth; this.identities=identities; this.rbac=rbac; this.recovery=recovery; this.refunds=refunds;
    }
    private Mono<String> operator(ServerWebExchange exchange) {
        return operator(exchange,false);
    }
    private Mono<String> operator(ServerWebExchange exchange,boolean reconciliation) {
        return identities.resolveRequired(exchange).flatMap(identity -> Mono.fromCallable(() -> {
            rbac.require(auth.requireUserId(identity),"billing:refund");
            if(reconciliation)rbac.require(auth.requireUserId(identity),"billing:reconcile");
            return auth.publicUserId(identity);
        }).subscribeOn(Schedulers.boundedElastic()));
    }
    @PostMapping
    public Mono<PaymentRefundService.Refund> refund(@PathVariable String id, @RequestBody Reason body,
                                                   ServerWebExchange exchange) {
        return operator(exchange).flatMap(actor -> Mono.fromCallable(() -> recovery.refund(id,actor,body.reason(),body.requestId()))
                .subscribeOn(Schedulers.boundedElastic()));
    }
    @GetMapping
    public Mono<PaymentRefundService.Refund> get(@PathVariable String id, ServerWebExchange exchange) {
        return operator(exchange).flatMap(actor -> Mono.fromCallable(() -> refunds.forOrder(id))
                .subscribeOn(Schedulers.boundedElastic()));
    }
    public record Reason(String reason,String requestId) {public Reason(String reason){this(reason,null);}}
    @GetMapping("/history")
    public Mono<java.util.List<PaymentRefundService.Refund>> history(@PathVariable String id,ServerWebExchange exchange) {
        return operator(exchange).flatMap(actor->Mono.fromCallable(()->refunds.history(id)).subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping("/resolve")
    public Mono<PaymentRefundService.Refund> resolve(@PathVariable String id,@RequestBody Resolution body,ServerWebExchange exchange) {
        return operator(exchange,true).flatMap(actor -> Mono.fromCallable(() -> refunds.resolve(id,actor,body.outcome(),body.reason(),body.evidenceReference()))
                .subscribeOn(Schedulers.boundedElastic()));
    }
    public record Resolution(String outcome,String reason,String evidenceReference) {}
}
