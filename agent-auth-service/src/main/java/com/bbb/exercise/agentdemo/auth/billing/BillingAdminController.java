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
    private final AuthService auth;
    private final AuthIdentityResolver identities;
    private final BillingService billing;
    private final com.bbb.exercise.agentdemo.auth.rbac.RbacService rbac;

    public BillingAdminController(
            AuthService auth,
            AuthIdentityResolver identities,
            BillingService billing,
            com.bbb.exercise.agentdemo.auth.rbac.RbacService rbac) {
        this.auth = auth;
        this.identities = identities;
        this.billing = billing;
        this.rbac = rbac;
    }

    // 1. 计费运营授权：在执行退款或核对前确认当前操作者权限。
    private Mono<String> admin(ServerWebExchange exchange, String permission) {
        return identities
                .resolveRequired(exchange)
                .flatMap(
                        i ->
                                Mono.fromCallable(
                                                () -> {
                                                    String actor = auth.publicUserId(i);

                                                    rbac.require(auth.requireUserId(i), permission);

                                                    return actor;
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    // 2. 站内退款：记录操作者及原因，不等同于支付渠道原路退款。
    @PostMapping("/refund")
    public Mono<Wallet> refund(
            @PathVariable String user,
            @PathVariable String id,
            @RequestBody Reason body,
            ServerWebExchange exchange) {
        return admin(exchange, "billing:refund")
                .flatMap(
                        actor ->
                                Mono.fromCallable(
                                                () ->
                                                        billing.reconcileRefund(
                                                                user, id, actor, body.reason()))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    // 3. 人工结算：使用明确用量完成待核对消费。
    @PostMapping("/settle")
    public Mono<Wallet> settle(
            @PathVariable String user,
            @PathVariable String id,
            @RequestBody Reconcile body,
            ServerWebExchange exchange) {
        return admin(exchange, "billing:reconcile")
                .flatMap(
                        actor ->
                                Mono.fromCallable(
                                                () ->
                                                        billing.reconcileSettle(
                                                                user,
                                                                id,
                                                                new Settlement(
                                                                        body.inputTokens(),
                                                                        body.outputTokens(),
                                                                        body.providerRequestId()),
                                                                actor,
                                                                body.reason()))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /** 人工退款操作的审计说明，记录调整原因。 */
    public record Reason(String reason) {}

    /** 人工用量结算请求，携带供应商用量证据及调整原因。 */
    public record Reconcile(
            Long inputTokens, Long outputTokens, String providerRequestId, String reason) {}
}
