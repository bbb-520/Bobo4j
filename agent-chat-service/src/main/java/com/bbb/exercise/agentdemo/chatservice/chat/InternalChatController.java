package com.bbb.exercise.agentdemo.chatservice.chat;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import com.bbb.exercise.agentdemo.chatservice.memory.VisionMemoryService;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient.ModelCallException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.scheduler.Schedulers;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** 服务间聊天入口，验证调用签名后提供生成流、完成结果和视觉记忆查询。 */
@RestController
@RequestMapping("/internal/chat")
public class InternalChatController {
    private static final String STREAM_FAILURE_MESSAGE =
            "当前回答服务暂时不可用，你的问题和处理进度已保留。可以稍后重试。";
    private final InternalCompletionService completions;
    private final PrincipalKeyRing keys;
    private final VisionMemoryService memories;

    public InternalChatController(
            InternalCompletionService completions,
            VisionMemoryService memories,
            @Value("${app.security.internal-principal-secrets:}") String secrets,
            @Value("${app.security.internal-principal-active-key-id:current}") String active) {
        this.completions = completions;
        this.memories = memories;
        this.keys = new PrincipalKeyRing(secrets, active);
    }

    // 1. 内部身份边界：验证服务签名后构造当前请求的租户和用户身份。
    private ChatIdentity identity(String token, String operation) {
        try {
            var p = keys.verify(token, "agent-chat-service", operation);

            if (!Set.of("agent-orchestrator-service", "agent-rag-service").contains(p.service())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "内部调用不允许");
            }

            return new ChatIdentity(p.tenant(), p.subject(), true);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部身份签名无效");
        }
    }

    // 2. 内部生成接口：提供同步结果与流式事件两种传输方式。
    @PostMapping("/complete")
    public Mono<Completion> complete(
            @RequestHeader("X-Internal-Principal") String token,
            @RequestBody CompletionRequest request) {
        return Mono.fromCallable(
                        () ->
                                completions.complete(
                                        identity(token, "POST /internal/chat/complete"), request))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        IllegalStateException.class,
                        e -> new ResponseStatusException(HttpStatus.CONFLICT, "调用身份或幂等参数冲突"));
    }

    @PostMapping(value = "/stream", produces = "text/event-stream")
    public Flux<ServerSentEvent<GenerationEvent>> stream(
            @RequestHeader("X-Internal-Principal") String token,
            @RequestBody CompletionRequest request) {
        ChatIdentity trusted = identity(token, "POST /internal/chat/stream");

        if (request == null || !"CHAT".equals(request.mode())) {
            throw new IllegalArgumentException("流式正文仅支持普通 CHAT");
        }

        return Flux.<ServerSentEvent<GenerationEvent>>create(
                sink -> {
                    var lastEvent = new AtomicReference<GenerationEvent>();
                    var worker =
                            Schedulers.boundedElastic()
                                    .schedule(
                                            () -> {
                                                try {
                                                    completions.completeStreaming(
                                                            trusted,
                                                            request,
                                                            event -> {
                                                                if (sink.isCancelled()) {
                                                                    throw new java.util.concurrent
                                                                            .CancellationException(
                                                                            "已停止");
                                                                }

                                                                sink.next(
                                                                        ServerSentEvent
                                                                                .<GenerationEvent>
                                                                                        builder(
                                                                                                event)
                                                                                .event(event.type())
                                                                                .build());
                                                                lastEvent.set(event);
                                                            });

                                                    if (!sink.isCancelled()) {
                                                        sink.complete();
                                                    }
                                                } catch (Throwable error) {
                                                    if (!sink.isCancelled()) {
                                                        var emitted = lastEvent.get();
                                                        if (emitted == null) {
                                                            sink.error(error);
                                                        } else {
                                                            String code =
                                                                    error instanceof ModelCallException modelError
                                                                            ? modelError.code()
                                                                            : "MODEL_STREAM_FAILED";
                                                            var failure =
                                                                    new GenerationEvent(
                                                                            "error",
                                                                            emitted.generationVersion(),
                                                                            Map.of("code", code, "message", STREAM_FAILURE_MESSAGE));
                                                            sink.next(
                                                                    ServerSentEvent.<GenerationEvent>builder(failure)
                                                                            .event("error").build());
                                                            sink.complete();
                                                        }
                                                    }
                                                }
                                            });
                    sink.onCancel(worker::dispose);
                });
    }

    // 3. 内部视觉记忆查询：仅检索签名身份所属的记忆。
    @GetMapping("/visual-memory")
    public Mono<Map<String, Object>> visualMemory(
            @RequestHeader("X-Internal-Principal") String token, @RequestParam String query) {
        return Mono.fromCallable(
                        () -> {
                            if (query.length() > 20000) {
                                throw new IllegalArgumentException("记忆查询过长");
                            }

                            String augmented =
                                    memories.augment(
                                            identity(token, "GET /internal/chat/visual-memory"),
                                            query,
                                            8);
                            String context =
                                    augmented.equals(query)
                                            ? ""
                                            : augmented.substring(query.length());

                            return Map.<String, Object>of(
                                    "context", context, "hits", context.isBlank() ? 0 : 1);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
