package com.bbb.exercise.agentdemo.runtime.client;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Fail closed: every model call requires a reservation and a single-use dispatch transition. */
@Component
public class BillingClient {
    private final InternalServiceClient http;
    public BillingClient(InternalServiceClient http) {this.http=http;}
    private static String path(ChatIdentity i) {
        if(i==null||!i.authenticated()||!i.userId().matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("请先登录");
        return "/internal/billing/users/"+i.userId();
    }
    private static String id(String value) {if(value==null||!value.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("计费调用 ID 无效");return value;}
    public Mono<Wallet> wallet(ChatIdentity i) {return http.getAuth(path(i)+"/wallet",i.userId(),i.tenantId(),Wallet.class);}
    public Mono<CallStatus> status(ChatIdentity i,String request) {return http.getAuth(path(i)+"/"+id(request)+"/status",i.userId(),i.tenantId(),CallStatus.class);}
    public record CallStatus(String status) {}
    public Mono<Reservation> reserve(ChatIdentity i,String request,String capability,String model) {
        return http.postAuth(path(i)+"/reserve",i.userId(),i.tenantId(),new Reserve(id(request),capability,model),Reservation.class);
    }
    public Mono<GroupState> reserveGroup(ChatIdentity i,GroupReserve request) {return http.postAuth(path(i)+"/groups",i.userId(),i.tenantId(),request,GroupState.class);}
    public Mono<GroupState> group(ChatIdentity i,String call) {return http.getAuth(path(i)+"/groups/"+id(call),i.userId(),i.tenantId(),GroupState.class);}
    public Mono<AttemptState> attempt(ChatIdentity i,String call,AttemptReserve request) {return http.postAuth(path(i)+"/groups/"+id(call)+"/attempts",i.userId(),i.tenantId(),request,AttemptState.class);}
    public Mono<Void> dispatchAttempt(ChatIdentity i,String call,String role) {return dispatchAttempt(i,call,role,1);}
    public Mono<Void> unknownAttempt(ChatIdentity i,String call,String role) {return unknownAttempt(i,call,role,1);}
    public Mono<Void> skipAttempt(ChatIdentity i,String call,String role) {return skipAttempt(i,call,role,1);}
    public Mono<Void> dispatchAttempt(ChatIdentity i,String call,String role,int generation) {return groupAction(i,call,role,"dispatch",generation);}
    public Mono<Void> unknownAttempt(ChatIdentity i,String call,String role,int generation) {return groupAction(i,call,role,"unknown",generation);}
    public Mono<Void> skipAttempt(ChatIdentity i,String call,String role,int generation) {return groupAction(i,call,role,"skip",generation);}
    private Mono<Void> groupAction(ChatIdentity i,String call,String role,String action,int generation) {return http.postAuth(path(i)+"/groups/"+id(call)+"/attempts/"+id(role)+"/"+action,i.userId(),i.tenantId(),new AttemptAction(generation),Void.class).then();}
    public Mono<Void> receipt(ChatIdentity i,String call,AttemptReceipt result) {return http.postAuth(path(i)+"/groups/"+id(call)+"/receipt",i.userId(),i.tenantId(),result,Void.class).then();}
    public Mono<GroupState> finish(ChatIdentity i,String call) {return http.postAuth(path(i)+"/groups/"+id(call)+"/finish",i.userId(),i.tenantId(),Map.of(),GroupState.class);}
    public Mono<Void> cancelGroup(ChatIdentity i,String call,int generation) {return http.postAuth(path(i)+"/groups/"+id(call)+"/cancel",i.userId(),i.tenantId(),new AttemptAction(generation),Void.class).then();}
    public Mono<Void> dispatch(ChatIdentity i,String request) {return action(i,request,"dispatch");}
    public Mono<Void> unknown(ChatIdentity i,String request) {return action(i,request,"unknown");}
    public Mono<Void> cancel(ChatIdentity i,String request) {return action(i,request,"cancel");}
    private Mono<Void> action(ChatIdentity i,String request,String action) {return http.postAuth(path(i)+"/"+id(request)+"/"+action,i.userId(),i.tenantId(),Map.of(),Void.class).then();}
    public Mono<Wallet> settle(ChatIdentity i,String request,Settlement result) {
        return http.postAuth(path(i)+"/"+id(request)+"/settle",i.userId(),i.tenantId(),result,Wallet.class);
    }
    public <T> Mono<T> image(ChatIdentity i,String request,String model,Supplier<Mono<T>> provider,Function<T,Settlement> usage) {
        return Mono.usingWhen(reserve(i,request,"IMAGE",model),
                r -> dispatch(i,request).then(Mono.defer(provider)).switchIfEmpty(Mono.error(new IllegalStateException("模型没有返回结果")))
                        .flatMap(value -> settle(i,request,usage.apply(value)).thenReturn(value)),
                r -> Mono.empty(),(r,error) -> unknown(i,request).onErrorResume(ignored -> Mono.empty()),
                r -> unknown(i,request).onErrorResume(ignored -> Mono.empty()));
    }
}
