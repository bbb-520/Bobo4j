package com.bbb.exercise.agentdemo.authservice;

import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.auth.PlatformModels;
import com.bbb.exercise.agentdemo.auth.billing.*;
import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import com.bbb.exercise.agentdemo.api.model.ModelCapability;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import com.bbb.exercise.agentdemo.common.security.SignedPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Instant;
import java.util.Set;

@RestController
@RequestMapping("/internal/billing/users/{user}")
public class InternalBillingController {
    private final AuthService auth;private final BillingService billing;private final PlatformModels models;private final PrincipalKeyRing keys;private final ModelCallGroupService groups;
    public InternalBillingController(AuthService auth,BillingService billing,PlatformModels models,
                                     @Value("${app.security.internal-principal-secrets:}") String secrets,@Value("${app.security.internal-principal-active-key-id:current}") String active) {
        this(auth,billing,models,secrets,active,null);
    }
    @Autowired
    public InternalBillingController(AuthService auth,BillingService billing,PlatformModels models,
                                     @Value("${app.security.internal-principal-secrets:}") String secrets,@Value("${app.security.internal-principal-active-key-id:current}") String active,ModelCallGroupService groups) {
        this.auth=auth;this.billing=billing;this.models=models;this.keys=new PrincipalKeyRing(secrets,active);this.groups=groups;
    }
    private String verify(String token,String user,String operation) {
        try {var p=keys.verify(token,"agent-auth-service",operation);
            if(!p.subject().equals(user)||!Set.of("agent-media-service","agent-chat-service","agent-content-service","agent-rag-service","agent-orchestrator-service").contains(p.service())) throw new BillingException(403,"内部计费调用不允许");
            if(auth.resolvePublicUser(user,p.tenant())==null) throw new BillingException(404,"用户不存在");return user;
        }catch(IllegalArgumentException e) {throw new BillingException(401,"内部计费签名无效");}
    }
    @GetMapping("/wallet") public Mono<Wallet> wallet(@PathVariable String user,@RequestHeader("X-Internal-Principal") String token) {
        return Mono.fromCallable(() -> billing.wallet(verify(token,user,"GET /internal/billing/users/"+user+"/wallet"))).subscribeOn(Schedulers.boundedElastic());
    }
    @GetMapping("/{id}/status") public Mono<java.util.Map<String,String>> status(@PathVariable String user,@PathVariable String id,@RequestHeader("X-Internal-Principal") String token) {
        return Mono.fromCallable(() -> {verify(token,user,"GET /internal/billing/users/"+user+"/"+id+"/status");return java.util.Map.of("status",billing.status(user,id));}).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/reserve") public Mono<Reservation> reserve(@PathVariable String user,@RequestHeader("X-Internal-Principal") String token,@RequestBody Reserve request) {
        return Mono.fromCallable(() -> {
            verify(token,user,"POST /internal/billing/users/"+user+"/reserve");
            var selected=models.select(ModelCapability.parse(request.capability()));
            if(!selected.model().equals(request.model())) throw new BillingException(400,"计费模型不匹配平台配置");return billing.reserve(user,request);
        }).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/{id}/dispatch") public Mono<Void> dispatch(@PathVariable String user,@PathVariable String id,@RequestHeader("X-Internal-Principal") String token) {return action(user,id,token,"dispatch",() -> billing.dispatch(user,id));}
    @PostMapping("/{id}/unknown") public Mono<Void> unknown(@PathVariable String user,@PathVariable String id,@RequestHeader("X-Internal-Principal") String token) {return action(user,id,token,"unknown",() -> billing.unknown(user,id));}
    @PostMapping("/{id}/cancel") public Mono<Void> cancel(@PathVariable String user,@PathVariable String id,@RequestHeader("X-Internal-Principal") String token) {return action(user,id,token,"cancel",() -> billing.cancel(user,id));}
    @PostMapping("/{id}/settle") public Mono<Wallet> settle(@PathVariable String user,@PathVariable String id,@RequestHeader("X-Internal-Principal") String token,@RequestBody Settlement result) {
        return Mono.fromCallable(() -> {verify(token,user,"POST /internal/billing/users/"+user+"/"+id+"/settle");return billing.settle(user,id,result);}).subscribeOn(Schedulers.boundedElastic());
    }
    private Mono<Void> action(String user,String id,String token,String action,Runnable call) {
        return Mono.fromRunnable(() -> {verify(token,user,"POST /internal/billing/users/"+user+"/"+id+"/"+action);call.run();}).subscribeOn(Schedulers.boundedElastic()).then();
    }
    private String groupTenant(String token,String user,String operation) {verify(token,user,operation);return keys.verify(token,"agent-auth-service",operation).tenant();}
    @PostMapping("/groups") public Mono<GroupState> groupReserve(@PathVariable String user,@RequestHeader("X-Internal-Principal") String token,@RequestBody GroupReserve body) {
        String path="/internal/billing/users/"+user+"/groups";
        return Mono.fromCallable(() -> groups.reserve(user,groupTenant(token,user,"POST "+path),body)).subscribeOn(Schedulers.boundedElastic());
    }
    @GetMapping("/groups/{id}") public Mono<GroupState> groupState(@PathVariable String user,@PathVariable String id,@RequestHeader("X-Internal-Principal") String token) {
        return Mono.fromCallable(() -> groups.state(user,groupTenant(token,user,"GET /internal/billing/users/"+user+"/groups/"+id),id)).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/groups/{id}/attempts") public Mono<AttemptState> groupAttempt(@PathVariable String user,@PathVariable String id,@RequestHeader("X-Internal-Principal") String token,@RequestBody AttemptReserve body) {
        return Mono.fromCallable(() -> {
            String tenant=groupTenant(token,user,"POST /internal/billing/users/"+user+"/groups/"+id+"/attempts");
            var capability="FALLBACK".equals(body.role())?ModelCapability.FALLBACK:ModelCapability.parse(groups.capability(user,tenant,id));
            var selected=models.select(capability);
            if(!selected.model().equals(body.model())||!selected.provider().name().equals(body.provider())||!selected.baseUrl().equals(body.endpoint())||!selected.credentialId().equals(body.credentialId()))throw new BillingException(400,"计费模型不匹配平台配置");
            return groups.attempt(user,tenant,id,body);
        }).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/groups/{id}/attempts/{role}/dispatch") public Mono<Void> groupDispatch(@PathVariable String user,@PathVariable String id,@PathVariable String role,@RequestHeader("X-Internal-Principal") String token,@RequestBody AttemptAction body) {
        return Mono.fromRunnable(() -> groups.dispatch(user,groupTenant(token,user,"POST /internal/billing/users/"+user+"/groups/"+id+"/attempts/"+role+"/dispatch"),id,role,body.generation())).subscribeOn(Schedulers.boundedElastic()).then();
    }
    @PostMapping("/groups/{id}/attempts/{role}/unknown") public Mono<Void> groupUnknown(@PathVariable String user,@PathVariable String id,@PathVariable String role,@RequestHeader("X-Internal-Principal") String token,@RequestBody AttemptAction body) {
        return Mono.fromRunnable(() -> groups.unknown(user,groupTenant(token,user,"POST /internal/billing/users/"+user+"/groups/"+id+"/attempts/"+role+"/unknown"),id,role,body.generation())).subscribeOn(Schedulers.boundedElastic()).then();
    }
    @PostMapping("/groups/{id}/attempts/{role}/skip") public Mono<Void> groupSkip(@PathVariable String user,@PathVariable String id,@PathVariable String role,@RequestHeader("X-Internal-Principal") String token,@RequestBody AttemptAction body) {return Mono.fromRunnable(() -> groups.skip(user,groupTenant(token,user,"POST /internal/billing/users/"+user+"/groups/"+id+"/attempts/"+role+"/skip"),id,role,body.generation())).subscribeOn(Schedulers.boundedElastic()).then();}
    @PostMapping("/groups/{id}/receipt") public Mono<Void> groupReceipt(@PathVariable String user,@PathVariable String id,@RequestHeader("X-Internal-Principal") String token,@RequestBody AttemptReceipt body) {
        return Mono.fromRunnable(() -> groups.receipt(user,groupTenant(token,user,"POST /internal/billing/users/"+user+"/groups/"+id+"/receipt"),id,body)).subscribeOn(Schedulers.boundedElastic()).then();
    }
    @PostMapping("/groups/{id}/finish") public Mono<GroupState> groupFinish(@PathVariable String user,@PathVariable String id,@RequestHeader("X-Internal-Principal") String token) {
        return Mono.fromCallable(() -> groups.finish(user,groupTenant(token,user,"POST /internal/billing/users/"+user+"/groups/"+id+"/finish"),id)).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/groups/{id}/cancel") public Mono<Void> groupCancel(@PathVariable String user,@PathVariable String id,@RequestHeader("X-Internal-Principal") String token,@RequestBody AttemptAction body){return Mono.fromRunnable(() -> groups.cancel(user,groupTenant(token,user,"POST /internal/billing/users/"+user+"/groups/"+id+"/cancel"),id,body.generation())).subscribeOn(Schedulers.boundedElastic()).then();}
}
