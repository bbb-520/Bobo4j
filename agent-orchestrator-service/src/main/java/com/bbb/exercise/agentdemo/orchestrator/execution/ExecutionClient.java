package com.bbb.exercise.agentdemo.orchestrator.execution;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Component
public class ExecutionClient {
    private final Map<String,WebClient> targets;private final PrincipalKeyRing keys;private final ObjectMapper json;
    @Value("${app.execution.tool-timeout-seconds:900}") private long paidToolTimeoutSeconds=900;
    public ExecutionClient(WebClient.Builder builder,PrincipalKeyRing keys,ObjectMapper json,
        @Value("${app.chat.base-url:http://127.0.0.1:18085}")String chat,
        @Value("${app.rag.base-url:http://127.0.0.1:18086}")String rag,
        @Value("${app.media.base-url:http://127.0.0.1:18082}")String media,
        @Value("${app.auth.base-url:http://127.0.0.1:18081}")String auth) {
        this.keys=keys;this.json=json;this.targets=Map.of("chat",builder.clone().baseUrl(chat).build(),"rag",builder.clone().baseUrl(rag).build(),"media",builder.clone().baseUrl(media).build(),"auth",builder.clone().baseUrl(auth).build());
    }
    public CompletableFuture<Map<String,Object>> post(ChatIdentity identity,String target,String path,Object body,boolean readOnly) {
        Mono<Map<String,Object>> request=Mono.defer(()->exchange(targets.get(target).post().uri(path)
            .header("X-Internal-Principal",keys.sign("agent-orchestrator-service",identity.userId(),identity.tenantId(),"agent-"+target+"-service","POST "+path)).bodyValue(body)));
        if(readOnly)request=request.retryWhen(Retry.backoff(2,Duration.ofMillis(500)).maxBackoff(Duration.ofSeconds(2)).filter(e->e instanceof RemoteFailure r&&r.retryable()));
        return request.timeout(Duration.ofSeconds(readOnly?10:Math.max(1,paidToolTimeoutSeconds))).toFuture();
    }
    public CompletableFuture<Map<String,Object>> get(ChatIdentity identity,String target,String path) {
        String operation="GET "+path.split("\\?",2)[0];
        return Mono.defer(()->exchange(targets.get(target).get().uri(path).header("X-Internal-Principal",keys.sign("agent-orchestrator-service",identity.userId(),identity.tenantId(),"agent-"+target+"-service",operation))))
                .timeout(Duration.ofSeconds(10)).toFuture();
    }
    /** No retry: a disconnected provider attempt must be reconciled before redispatch. */
    @SuppressWarnings("unchecked") public CompletableFuture<Map<String,Object>> stream(ChatIdentity identity,Object body,Consumer<Map<String,Object>> onEvent) {
        return Flux.defer(()->targets.get("chat").post().uri("/internal/chat/stream")
            .header("X-Internal-Principal",keys.sign("agent-orchestrator-service",identity.userId(),identity.tenantId(),"agent-chat-service","POST /internal/chat/stream"))
            .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(body).exchangeToFlux(response->{
                if(!response.statusCode().is2xxSuccessful())return response.releaseBody().thenMany(Flux.error(new RemoteFailure(response.statusCode().value())));
                return response.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>(){});
            }))
            .filter(event->event.data()!=null&&!event.data().isBlank())
            .map(event->(Map<String,Object>)json.readValue(event.data(),Map.class))
            // JDBC event persistence must never block the reactor network thread.
            .publishOn(Schedulers.boundedElastic())
            .handle((Map<String,Object> event,reactor.core.publisher.SynchronousSink<Map<String,Object>> sink)->{
                String type=String.valueOf(event.get("type"));
                if(type.equals("answer_committed"))sink.next((Map<String,Object>)event.get("payload"));
                else if(Set.of("answer_delta","answer_replace","fallback_started","progress").contains(type))onEvent.accept(event);
                else if(type.equals("error"))sink.error(new RemoteFailure(503));
            }).single().timeout(Duration.ofSeconds(300)).toFuture();
    }
    @SuppressWarnings("unchecked") private Mono<Map<String,Object>> exchange(WebClient.RequestHeadersSpec<?> request) {
        return request.exchangeToMono(response->{
            if(!response.statusCode().is2xxSuccessful())return response.releaseBody().then(Mono.error(new RemoteFailure(response.statusCode().value())));
            return response.bodyToMono(String.class).map(s->{var node=json.readTree(s);return node.isArray()?Map.of("items",json.convertValue(node,List.class)):(Map<String,Object>)json.convertValue(node,Map.class);});
        });
    }
    public static class RemoteFailure extends RuntimeException {
        final int status;public RemoteFailure(int status){super("内部调用失败，状态 "+status);this.status=status;}
        boolean retryable(){return status==429||status>=500;}
    }
}
