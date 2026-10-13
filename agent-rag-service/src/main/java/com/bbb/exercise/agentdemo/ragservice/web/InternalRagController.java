package com.bbb.exercise.agentdemo.ragservice.web;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.*;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import com.bbb.exercise.agentdemo.ragservice.processing.RagModelGateway;
import com.bbb.exercise.agentdemo.ragservice.retrieval.HybridRetriever;
import com.bbb.exercise.agentdemo.ragservice.answer.DocumentAnswerService;
import com.bbb.exercise.agentdemo.ragservice.chunking.SemanticChunker;
import com.bbb.exercise.agentdemo.ragservice.domain.RagException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import org.springframework.http.codec.ServerSentEvent;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent;
import reactor.core.scheduler.Schedulers;
import java.util.*;
import java.util.function.Function;

/** 服务间 RAG 入口，验证签名后提供检索、问答流、预算状态及文档章节。 */
@RestController
@RequestMapping("/internal/rag")
public class InternalRagController {
    private final PrincipalKeyRing keys;
    private final HybridRetriever retriever;
    private final DocumentAnswerService answers;
    private final RagRepository repository;
    private final RagModelGateway models;

    public InternalRagController(
            @Value("${app.security.internal-principal-secrets:}") String secrets,
            @Value("${app.security.internal-principal-active-key-id:current}") String active,
            HybridRetriever retriever,
            DocumentAnswerService answers,
            RagRepository repository,
            RagModelGateway models) {
        this.keys = new PrincipalKeyRing(secrets, active);
        this.retriever = retriever;
        this.answers = answers;
        this.repository = repository;
        this.models = models;
    }

    // 1. 内部检索：验证调用签名后，在给定文档会话范围内召回证据。
    @PostMapping("/retrieve")
    public Mono<Retrieval> retrieve(@RequestBody RetrieveRequest request, ServerWebExchange ex) {
        return signed(
                ex,
                who -> {
                    String id =
                            request.callId() == null
                                    ? "retrieve:" + UUID.randomUUID()
                                    : request.callId();
                    var stable =
                            new RetrieveRequest(
                                    request.conversationId(),
                                    request.question(),
                                    id,
                                    request.tokenBudget());

                    return retriever.retrieve(
                            who,
                            stable,
                            "ragr:"
                                    + SemanticChunker.hash(
                                                    who.tenantId() + ":" + who.userId() + ":" + id)
                                            .substring(0, 48));
                });
    }

    // 2. 内部问答：同步和流式入口都使用相同的证据验证流程。
    @PostMapping("/answer")
    public Mono<Answer> answer(@RequestBody AnswerRequest request, ServerWebExchange ex) {
        return signed(ex, who -> answers.answer(who, request));
    }

    @PostMapping(value = "/answer-stream", produces = "text/event-stream")
    public Flux<ServerSentEvent<GenerationEvent>> answerStream(
            @RequestBody AnswerRequest request, ServerWebExchange ex) {
        return signed(ex, who -> who)
                .flatMapMany(
                        who ->
                                Flux.<ServerSentEvent<GenerationEvent>>create(
                                        sink -> {
                                            var worker =
                                                    Schedulers.boundedElastic()
                                                            .schedule(
                                                                    () -> {
                                                                        var lastEvent = new java.util.concurrent.atomic.AtomicReference<GenerationEvent>();
                                                                        try {
                                                                            var version =
                                                                                    new java.util
                                                                                            .concurrent
                                                                                            .atomic
                                                                                            .AtomicInteger(
                                                                                            1);
                                                                            var result =
                                                                                    answers
                                                                                            .answerStreaming(
                                                                                                    who,
                                                                                                    request,
                                                                                                    event -> {
                                                                                                        if (sink
                                                                                                                .isCancelled()) {
                                                                                                            throw new java
                                                                                                                    .util
                                                                                                                    .concurrent
                                                                                                                    .CancellationException();
                                                                                                        }

                                                                                                        version
                                                                                                                .set(
                                                                                                                        Math
                                                                                                                                .max(
                                                                                                                                        version
                                                                                                                                                .get(),
                                                                                                                                        event
                                                                                                                                                .generationVersion()));
                                                                                                        sink
                                                                                                                .next(
                                                                                                                        ServerSentEvent
                                                                                                                                .<GenerationEvent>
                                                                                                                                        builder(
                                                                                                                                                event)
                                                                                                                                .event(
                                                                                                                                        event
                                                                                                                                                .type())
                                                                                                                                .build());
                                                                                                        lastEvent.set(event);
                                                                                                    });

                                                                            if (!sink
                                                                                    .isCancelled()) {
                                                                                @SuppressWarnings(
                                                                                        "unchecked")
                                                                                Map<String, Object>
                                                                                        payload =
                                                                                                tools
                                                                                                        .jackson
                                                                                                        .databind
                                                                                                        .json
                                                                                                        .JsonMapper
                                                                                                        .builder()
                                                                                                        .build()
                                                                                                        .convertValue(
                                                                                                                result,
                                                                                                                Map
                                                                                                                        .class);
                                                                                var committed =
                                                                                        new GenerationEvent(
                                                                                                "answer_committed",
                                                                                                version
                                                                                                        .get(),
                                                                                                payload);

                                                                                sink.next(
                                                                                        ServerSentEvent
                                                                                                .<GenerationEvent>
                                                                                                        builder(
                                                                                                                committed)
                                                                                                .event(
                                                                                                        committed
                                                                                                                .type())
                                                                                                .build());
                                                                                sink.complete();
                                                                            }
                                                                        } catch (Throwable error) {
                                                                            if (!sink
                                                                                    .isCancelled()) {
                                                                                var emitted=lastEvent.get();
                                                                                if(emitted==null) sink.error(error);
                                                                                else {
                                                                                    String code=error instanceof RagException rag?rag.code()
                                                                                            :error instanceof com.bbb.exercise.agentdemo.runtime.client.ModelCallClient.ModelCallException model?model.code():"RAG_STREAM_FAILED";
                                                                                    var failure=new GenerationEvent("error",emitted.generationVersion(),Map.of("code",code,"message","文档回答暂时无法完成，已完成步骤已保留。可以稍后重试。"));
                                                                                    sink.next(ServerSentEvent.<GenerationEvent>builder(failure).event("error").build());
                                                                                    sink.complete();
                                                                                }
                                                                            }
                                                                        }
                                                                    });
                                            sink.onCancel(worker::dispose);
                                        }));
    }

    // 3. 内部核对：查询已保存调用状态，供上游恢复未知步骤。
    @GetMapping("/calls/{callId}")
    public Mono<Map<String, Object>> status(@PathVariable String callId, ServerWebExchange ex) {
        return signed(ex, who -> models.status(who, callId));
    }

    // 4. 文档片段读取：限定当前会话和分页范围，返回已授权来源片段。
    @GetMapping("/documents/{id}/sections")
    public Mono<List<Evidence>> sections(
            @PathVariable String id,
            @RequestParam String conversationId,
            @RequestParam(defaultValue = "-1") int afterOrdinal,
            @RequestParam(defaultValue = "8") int limit,
            ServerWebExchange ex) {
        return signed(
                ex,
                who -> {
                    var scope = repository.scope(who, conversationId);
                    Integer version = scope.versions().get(id);

                    if (version == null) {
                        throw new RagException(
                                "DOCUMENT_OUTSIDE_CONVERSATION", HttpStatus.FORBIDDEN);
                    }

                    if (limit < 1 || limit > 8 || afterOrdinal < -1) {
                        throw new RagException("SECTION_RANGE_INVALID", HttpStatus.BAD_REQUEST);
                    }

                    return repository.chunks(who, id, version).stream()
                            .filter(c -> c.ordinal() > afterOrdinal)
                            .limit(limit)
                            .map(
                                    c ->
                                            new Evidence(
                                                    c.id(),
                                                    c.documentId(),
                                                    c.version(),
                                                    c.text(),
                                                    c.title(),
                                                    c.page(),
                                                    c.ordinal(),
                                                    1))
                            .toList();
                });
    }

    // 5. 服务边界：从签名主体构造用户身份，不能信任模型或外部输入中的用户 ID。
    private <T> Mono<T> signed(ServerWebExchange ex, Function<ChatIdentity, T> action) {
        return Mono.fromCallable(
                        () -> {
                            com.bbb.exercise.agentdemo.common.security.SignedPrincipal.Scoped
                                    principal;

                            try {
                                principal =
                                        keys.verify(
                                                ex.getRequest()
                                                        .getHeaders()
                                                        .getFirst("X-Internal-Principal"),
                                                "agent-rag-service",
                                                ex.getRequest().getMethod().name()
                                                        + " "
                                                        + ex.getRequest().getURI().getPath());
                            } catch (IllegalArgumentException bad) {
                                throw new RagException(
                                        "INTERNAL_PRINCIPAL_INVALID", HttpStatus.UNAUTHORIZED);
                            }

                            if (principal == null) {
                                throw new RagException(
                                        "INTERNAL_PRINCIPAL_INVALID", HttpStatus.UNAUTHORIZED);
                            }

                            if (!Set.of("agent-orchestrator-service", "agent-chat-service")
                                    .contains(principal.service())) {
                                throw new RagException(
                                        "INTERNAL_CALLER_FORBIDDEN", HttpStatus.FORBIDDEN);
                            }

                            return action.apply(
                                    new ChatIdentity(
                                            principal.tenant(), principal.subject(), true));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
