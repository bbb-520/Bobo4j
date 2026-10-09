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
    private final AuthService auth;private final AuthIdentityResolver identities;private final ModelCallGroupService groups;private final Set<String> admins;
    public ModelGroupAdminController(AuthService auth,AuthIdentityResolver identities,ModelCallGroupService groups,@Value("${app.billing.admin-user-ids:}") String ids){this.auth=auth;this.identities=identities;this.groups=groups;this.admins=new HashSet<>(Arrays.asList(ids.split(",")));}
    @PostMapping("/resolve-failure") public Mono<Void> resolve(@PathVariable String user,@PathVariable String id,@RequestBody Evidence body,ServerWebExchange exchange){return identities.resolveRequired(exchange).flatMap(identity -> Mono.fromRunnable(() -> {String actor=auth.publicUserId(identity);if(!admins.contains(actor))throw new BillingException(403,"需要运营管理员权限");groups.resolveFailure(user,identity.tenantId(),id,body.actualCostMicros(),actor,body.reason(),body.noUsageConfirmed());}).subscribeOn(Schedulers.boundedElastic())).then();}
    @PostMapping("/attempts/{role}/reconcile") public Mono<Void> reconcile(@PathVariable String user,@PathVariable String id,@PathVariable String role,@RequestBody Evidence body,ServerWebExchange exchange){return identities.resolveRequired(exchange).flatMap(identity -> Mono.fromRunnable(() -> {String actor=auth.publicUserId(identity);if(!admins.contains(actor))throw new BillingException(403,"需要运营管理员权限");groups.reconcile(user,identity.tenantId(),id,role,body.actualCostMicros(),actor,body.reason(),body.noUsageConfirmed());}).subscribeOn(Schedulers.boundedElastic())).then();}
    public record Evidence(long actualCostMicros,String reason,boolean noUsageConfirmed) {public Evidence(long cost,String reason){this(cost,reason,false);}}
    @PostMapping("/generations/{generation}/attempts/{role}/usage") public Mono<Void> usage(@PathVariable String user,@PathVariable String id,@PathVariable int generation,@PathVariable String role,@RequestBody TokenEvidence body,ServerWebExchange exchange){return identities.resolveRequired(exchange).flatMap(identity -> Mono.fromRunnable(() -> {String actor=auth.publicUserId(identity);if(!admins.contains(actor))throw new BillingException(403,"需要运营管理员权限");groups.reconcileTokens(user,identity.tenantId(),id,generation,role,body.inputTokens(),body.outputTokens(),actor,body.reason());}).subscribeOn(Schedulers.boundedElastic())).then();}
    public record TokenEvidence(long inputTokens,long outputTokens,String reason) {}
}
