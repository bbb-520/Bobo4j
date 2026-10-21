package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.common.trace.Correlation;
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

/** 编排器的跨服务客户端，为工具请求和生成流附加限定目标及操作的身份签名。 */
@Component
public class ExecutionClient {
    private final Map<String, WebClient> targets;
    private final PrincipalKeyRing keys;
    private final ObjectMapper json;

    @Value("${app.execution.tool-timeout-seconds:900}")
    private long paidToolTimeoutSeconds = 900;

    public ExecutionClient(
            WebClient.Builder builder,
            PrincipalKeyRing keys,
            ObjectMapper json,
            @Value("${app.chat.base-url:http://127.0.0.1:18085}") String chat,
            @Value("${app.rag.base-url:http://127.0.0.1:18086}") String rag,
            @Value("${app.media.base-url:http://127.0.0.1:18082}") String media,
            @Value("${app.auth.base-url:http://127.0.0.1:18081}") String auth) {
        this.keys = keys;
        this.json = json;
        this.targets =
                Map.of(
                        "chat",
                        builder.clone().baseUrl(chat).build(),
                        "rag",
                        builder.clone().baseUrl(rag).build(),
                        "media",
                        builder.clone().baseUrl(media).build(),
                        "auth",
                        builder.clone().baseUrl(auth).build());
    }

    // 1. 内部服务调用：携带当前任务身份和作用域签名请求远端服务。
    public CompletableFuture<Map<String, Object>> post(
            ChatIdentity identity, String target, String path, Object body, boolean readOnly) {
        Mono<Map<String, Object>> request =
                Mono.defer(
                        () ->
                                exchange(
                                        targets.get(target)
                                                .post()
                                                .uri(path)
                                                .headers(h -> Correlation.outgoingHeaders().forEach(h::set))
                                                .header(
                                                        "X-Internal-Principal",
                                                        keys.sign(
                                                                "agent-orchestrator-service",
                                                                identity.userId(),
                                                                identity.tenantId(),
                                                                "agent-" + target + "-service",
                                                                "POST " + path))
                                                .bodyValue(body)));

        if (readOnly) {
            request =
                    request.retryWhen(
                            Retry.backoff(2, Duration.ofMillis(500))
                                    .maxBackoff(Duration.ofSeconds(2))
                                    .filter(e -> e instanceof RemoteFailure r && r.retryable()));
        }

        return request.timeout(
                        Duration.ofSeconds(readOnly ? 10 : Math.max(1, paidToolTimeoutSeconds)))
                .toFuture();
    }

    public CompletableFuture<Map<String, Object>> get(
            ChatIdentity identity, String target, String path) {
        String operation = "GET " + path.split("\\?", 2)[0];
        return Mono.defer(
                        () ->
                                exchange(
                                        targets.get(target)
                                                .get()
                                                .uri(path)
                                                .headers(h -> Correlation.outgoingHeaders().forEach(h::set))
                                                .header(
                                                        "X-Internal-Principal",
                                                        keys.sign(
                                                                "agent-orchestrator-service",
                                                                identity.userId(),
                                                                identity.tenantId(),
                                                                "agent-" + target + "-service",
                                                                operation))))
                .timeout(Duration.ofSeconds(10))
                .toFuture();
    }

    // 2. 流式调用：消费远端生成事件，最终结果交回步骤持久化流程。
    /** No retry: a disconnected provider attempt must be reconciled before redispatch. */
    public CompletableFuture<Map<String, Object>> stream(
            ChatIdentity identity, Object body, Consumer<Map<String, Object>> onEvent) {
        return stream(identity, "chat", "/internal/chat/stream", body, onEvent);
    }

    public CompletableFuture<Map<String, Object>> streamRag(
            ChatIdentity identity, Object body, Consumer<Map<String, Object>> onEvent) {
        return stream(identity, "rag", "/internal/rag/answer-stream", body, onEvent);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Map<String, Object>> stream(
            ChatIdentity identity,
            String target,
            String path,
            Object body,
            Consumer<Map<String, Object>> onEvent) {
        return Flux.defer(
                        () ->
                                targets.get(target)
                                        .post()
                                        .uri(path)
                                        .headers(h -> Correlation.outgoingHeaders().forEach(h::set)).header(
                                                "X-Internal-Principal",
                                                keys.sign(
                                                        "agent-orchestrator-service",
                                                        identity.userId(),
                                                        identity.tenantId(),
                                                        "agent-" + target + "-service",
                                                        "POST " + path))
                                        .accept(MediaType.TEXT_EVENT_STREAM)
                                        .bodyValue(body)
                                        .exchangeToFlux(
                                                response -> {
                                                    if (!response.statusCode().is2xxSuccessful()) {
                                                        return failure(response).flatMapMany(Flux::error);
                                                    }

                                                    return response.bodyToFlux(
                                                            new ParameterizedTypeReference<
                                                                    ServerSentEvent<String>>() {});
                                                }))
                .filter(event -> event.data() != null && !event.data().isBlank())
                .map(event -> (Map<String, Object>) json.readValue(event.data(), Map.class))
                // JDBC event persistence must never block the reactor network thread.
                .publishOn(Schedulers.boundedElastic())
                .handle(
                        (Map<String, Object> event,
                                reactor.core.publisher.SynchronousSink<Map<String, Object>>
                                        sink) -> {
                            String type = String.valueOf(event.get("type"));

                            if (type.equals("answer_committed")) {
                                sink.next((Map<String, Object>) event.get("payload"));
                            } else if (Set.of(
                                            "answer_delta",
                                            "answer_replace",
                                            "fallback_started",
                                            "progress",
                                            "model_status")
                                    .contains(type)) {
                                onEvent.accept(event);
                            } else if (type.equals("error")) {
                                Object payload=event.get("payload");
                                String code=payload instanceof Map<?,?> p?String.valueOf(p.get("code")):"";
                                sink.error(new RemoteFailure(503,safeFailureCode(code)));
                            }
                        })
                .single()
                .timeout(
                        Duration.ofSeconds(
                                target.equals("rag") ? Math.max(1, paidToolTimeoutSeconds) : 300))
                .toFuture();
    }

    // 3. HTTP 响应边界：将远端失败状态转换为异常，并解析对象或列表结果。
    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> exchange(WebClient.RequestHeadersSpec<?> request) {
        return request.exchangeToMono(
                response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return failure(response).flatMap(Mono::error);
                    }

                    return response.bodyToMono(String.class)
                            .map(
                                    s -> {
                                        var node = json.readTree(s);
                                        return node.isArray()
                                                ? Map.of(
                                                        "items",
                                                        json.convertValue(node, List.class))
                                                : (Map<String, Object>)
                                                        json.convertValue(node, Map.class);
                                    });
                });
    }

    private Mono<RemoteFailure> failure(org.springframework.web.reactive.function.client.ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(String.class).defaultIfEmpty("")
                .map(body -> {
                    try {
                        // Fixed code only. Never propagate provider bodies, URLs or exception messages.
                        String code=safeFailureCode(json.readTree(body).path("code").asString());
                        if(code!=null)return new RemoteFailure(status,code);
                    } catch (RuntimeException ignored) {}
                    return new RemoteFailure(status);
                }).onErrorReturn(new RemoteFailure(status));
    }

    private static String safeFailureCode(String code) {
        return Set.of("IMAGE_ATTACHMENT_UNAVAILABLE","PROVIDER_REJECTED","PROVIDER_CONNECT_FAILED","MODEL_NOT_CONFIGURED","MODEL_CALL_INTERRUPTED","ANSWER_EVALUATION_UNAVAILABLE").contains(String.valueOf(code))?code:null;
    }

    /** 远端 HTTP 调用失败，保留状态码并判断是否属于可恢复故障。 */
    public static class RemoteFailure extends RuntimeException {
        final int status;
        final String code;

        public RemoteFailure(int status) {
            this(status, null);
        }

        public RemoteFailure(int status, String code) {
            super("内部调用失败，状态 " + status);
            this.status = status;
            this.code = code;
        }

        boolean retryable() {
            return status == 429 || status >= 500;
        }
    }
}
