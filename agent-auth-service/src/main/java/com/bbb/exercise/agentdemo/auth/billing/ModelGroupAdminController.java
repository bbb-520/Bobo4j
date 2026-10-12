package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.auth.identity.AuthIdentityResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;

/** Explicit operator evidence is required to release unknown supplier costs. */
@RestController
@RequestMapping("/api/billing/admin/model-groups/{user}/{id}")
public class ModelGroupAdminController {
    private final AuthService auth;
    private final AuthIdentityResolver identities;
    private final ModelCallGroupService groups;
    private final com.bbb.exercise.agentdemo.auth.rbac.RbacService rbac;

    public ModelGroupAdminController(
            AuthService auth,
            AuthIdentityResolver identities,
            ModelCallGroupService groups,
            com.bbb.exercise.agentdemo.auth.rbac.RbacService rbac) {
        this.auth = auth;
        this.identities = identities;
        this.groups = groups;
        this.rbac = rbac;
    }

    // 1. 调用组失败处理：由有权限的运营人员明确解决未知或失败状态。
    @PostMapping("/resolve-failure")
    public Mono<Void> resolve(
            @PathVariable String user,
            @PathVariable String id,
            @RequestBody Evidence body,
            ServerWebExchange exchange) {
        return identities
                .resolveRequired(exchange)
                .flatMap(
                        identity ->
                                Mono.fromRunnable(
                                                () -> {
                                                    String actor = auth.publicUserId(identity);

                                                    rbac.require(
                                                            auth.requireUserId(identity),
                                                            "billing:reconcile");
                                                    groups.resolveFailure(
                                                            user,
                                                            identity.tenantId(),
                                                            id,
                                                            body.actualCostMicros(),
                                                            actor,
                                                            body.reason(),
                                                            body.noUsageConfirmed());
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    // 2. 尝试用量核对：按调用组和尝试角色提交核对结果。
    @PostMapping("/attempts/{role}/reconcile")
    public Mono<Void> reconcile(
            @PathVariable String user,
            @PathVariable String id,
            @PathVariable String role,
            @RequestBody Evidence body,
            ServerWebExchange exchange) {
        return identities
                .resolveRequired(exchange)
                .flatMap(
                        identity ->
                                Mono.fromRunnable(
                                                () -> {
                                                    String actor = auth.publicUserId(identity);

                                                    rbac.require(
                                                            auth.requireUserId(identity),
                                                            "billing:reconcile");
                                                    groups.reconcile(
                                                            user,
                                                            identity.tenantId(),
                                                            id,
                                                            role,
                                                            body.actualCostMicros(),
                                                            actor,
                                                            body.reason(),
                                                            body.noUsageConfirmed());
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    /** 调用组费用对账证据，记录实际费用或供应商确认无用量的说明。 */
    public record Evidence(long actualCostMicros, String reason, boolean noUsageConfirmed) {
        public Evidence(long cost, String reason) {
            this(cost, reason, false);
        }
    }

    // 3. 调用组记录：提供运营核对所需的消费与尝试信息。
    @PostMapping("/generations/{generation}/attempts/{role}/usage")
    public Mono<Void> usage(
            @PathVariable String user,
            @PathVariable String id,
            @PathVariable int generation,
            @PathVariable String role,
            @RequestBody TokenEvidence body,
            ServerWebExchange exchange) {
        return identities
                .resolveRequired(exchange)
                .flatMap(
                        identity ->
                                Mono.fromRunnable(
                                                () -> {
                                                    String actor = auth.publicUserId(identity);

                                                    rbac.require(
                                                            auth.requireUserId(identity),
                                                            "billing:reconcile");
                                                    groups.reconcileTokens(
                                                            user,
                                                            identity.tenantId(),
                                                            id,
                                                            generation,
                                                            role,
                                                            body.inputTokens(),
                                                            body.outputTokens(),
                                                            actor,
                                                            body.reason());
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    /** 调用组 Token 对账证据，以实测输入输出用量修正未知占用。 */
    public record TokenEvidence(long inputTokens, long outputTokens, String reason) {}
}
