package com.bbb.exercise.agentdemo.auth.billing;
import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.auth.identity.AuthIdentityResolver;
import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Explicit operator allowlist, empty by default; every adjustment is audited and idempotent. */
@RestController
@RequestMapping("/api/billing/admin/usages/{user}/{id}")
public class BillingAdminController {
    private final AuthService auth;private final AuthIdentityResolver identities;private final BillingService billing;private final Set<String> admins;
    public BillingAdminController(AuthService auth,AuthIdentityResolver identities,BillingService billing,@Value("${app.billing.admin-user-ids:}") String ids) {
        this.auth=auth;this.identities=identities;this.billing=billing;this.admins=Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
    }
    private Mono<String> admin(ServerWebExchange exchange) {return identities.resolveRequired(exchange).flatMap(i -> Mono.fromCallable(() -> {
        String actor=auth.publicUserId(i);if(!admins.contains(actor))throw new BillingException(403,"需要运营管理员权限");return actor;
    }).subscribeOn(Schedulers.boundedElastic()));}
    @PostMapping("/refund") public Mono<Wallet> refund(@PathVariable String user,@PathVariable String id,@RequestBody Reason body,ServerWebExchange exchange) {
        return admin(exchange).flatMap(actor -> Mono.fromCallable(() -> billing.reconcileRefund(user,id,actor,body.reason())).subscribeOn(Schedulers.boundedElastic()));
    }
    @PostMapping("/settle") public Mono<Wallet> settle(@PathVariable String user,@PathVariable String id,@RequestBody Reconcile body,ServerWebExchange exchange) {
        return admin(exchange).flatMap(actor -> Mono.fromCallable(() -> billing.reconcileSettle(user,id,new Settlement(body.inputTokens(),body.outputTokens(),body.providerRequestId()),actor,body.reason())).subscribeOn(Schedulers.boundedElastic()));
    }
    public record Reason(String reason) {}
    public record Reconcile(Long inputTokens,Long outputTokens,String providerRequestId,String reason) {}
}
