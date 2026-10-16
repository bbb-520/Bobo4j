package com.bbb.exercise.agentdemo.ragservice.answer;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.*;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import com.bbb.exercise.agentdemo.ragservice.retrieval.HybridRetriever;
import com.bbb.exercise.agentdemo.ragservice.chunking.SemanticChunker;
import com.bbb.exercise.agentdemo.ragservice.domain.RagException;
import com.bbb.exercise.agentdemo.ragservice.processing.RagModelGateway;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PreDestroy;

/** 文档问答协调器，在租约与预算约束下完成证据检索、生成、评估及幂等提交。 */
@Service
public class DocumentAnswerService {
    private final RagRepository repository;
    private final HybridRetriever retriever;
    private final RagModelGateway models;
    private final GroundingEvaluator evaluator;
    private final ScheduledExecutorService renewals =
            Executors.newScheduledThreadPool(
                    2, Thread.ofPlatform().daemon().name("rag-answer-lease-", 0).factory());

    public DocumentAnswerService(
            RagRepository repository,
            HybridRetriever retriever,
            RagModelGateway models,
            GroundingEvaluator evaluator) {
        this.repository = repository;
        this.retriever = retriever;
        this.models = models;
        this.evaluator = evaluator;
    }

    // 1. 问答入口：同步和事件模式复用同一套证据与评估规则。
    public Answer answer(ChatIdentity who, AnswerRequest request) {
        return answerCore(who, request, null);
    }

    public Answer answerStreaming(
            ChatIdentity who,
            AnswerRequest request,
            java.util.function.Consumer<
                            com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>
                    events) {
        return answerCore(who, request, events);
    }

    // 2. 文档回答流程：固定幂等请求和租约，再检索、生成、评估并提交。
    private Answer answerCore(
            ChatIdentity who,
            AnswerRequest request,
            java.util.function.Consumer<
                            com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>
                    events) {
        if (request == null
                || request.callId() == null
                || !request.callId().matches("[A-Za-z0-9_.:@-]{1,128}")
                || request.question() == null
                || request.question().isBlank()
                || request.question().length() > 4000) {
            throw new RagException("ANSWER_REQUEST_INVALID", HttpStatus.BAD_REQUEST);
        }
        validateImageEvidence(request.imageEvidence());

        // 2.1. 请求准入：先登记复合 Token 预算，再领取本次问答租约。
        models.ensureBudget(who, request.callId(), request.tokenBudget());
        var lease = repository.begin(who, request);

        // 2.1.1. 幂等重放：已有完成回执时直接返回，不再发起检索和模型调用。
        if (lease.completed() != null) {
            return lease.completed();
        }

        var leaseFailure = new AtomicReference<RuntimeException>();
        var heartbeat =
                renewals.scheduleAtFixedRate(
                        () -> {
                            if (leaseFailure.get() == null) {
                                try {
                                    repository.renewAnswer(who, request, lease.fence());
                                } catch (RuntimeException error) {
                                    leaseFailure.compareAndSet(null, error);
                                }
                            }
                        },
                        30,
                        30,
                        TimeUnit.SECONDS);

        try {
            String call =
                    "ragqa:"
                            + SemanticChunker.hash(
                                            who.tenantId()
                                                    + ":"
                                                    + who.userId()
                                                    + ":"
                                                    + request.callId())
                                    .substring(0, 48);
            // 2.2. 证据检索：会话限定文档范围，检索调用沿用本次问答的预算。
            Retrieval retrieval =
                    retriever.retrieve(
                            who,
                            new RetrieveRequest(
                                    request.conversationId(),
                                    retrievalQuestion(request),
                                    request.callId(),
                                    request.tokenBudget()),
                            call + ":retrieve");
            long input = retrieval.inputTokens(), output = retrieval.outputTokens();

            List<Evidence> combined = new ArrayList<>(retrieval.evidence());
            if (request.imageEvidence() != null) {
                var image = request.imageEvidence();
                String imageId = "image-attachments:" + SemanticChunker.hash(
                        com.bbb.exercise.agentdemo.ragservice.domain.CanonicalJson.write(image));
                combined.add(new Evidence(imageId, null, 1, image.text(),
                        "用户图片附件（视觉模型提取，可能存在识别误差）", null, -1, 1.0));
            }

            requireLease(leaseFailure);

            // 2.2.1. 无证据拒答：提交明确的 NO_EVIDENCE 结果，不让模型补造来源。
            if (combined.isEmpty()) {
                Answer refusal =
                        result(
                                who,
                                request,
                                "文档中没有足够依据回答这个问题。",
                                List.of(),
                                "NO_EVIDENCE",
                                retrieval.degraded());

                repository.commit(who, request, lease.fence(), refusal);

                return refusal;
            }

            List<Map<String, Object>> evidence = new ArrayList<>();

            for (int i = 0; i < combined.size(); i++) {
                var e = combined.get(i);
                evidence.add(Map.of("id", "S" + (i + 1), "title", e.title(), "text", e.text()));
            }

            String feedback = "";
            var generation = new java.util.concurrent.atomic.AtomicInteger(0);

            // 2.3. 生成与修复：使用评估反馈进行有限次修复，每轮都重新检查租约和文档范围。
            for (int attempt = 0; attempt < 2; attempt++) {
                requireLease(leaseFailure);
                repository.scope(who, request.conversationId());
                String user =
                        com.bbb.exercise.agentdemo.ragservice.domain.CanonicalJson.write(
                                Map.of(
                                        "originalQuestion",
                                        request.question(),
                                        "searchQuery",
                                        retrieval.query(),
                                        "evidence",
                                        evidence,
                                        "repairFeedback",
                                        feedback));
                String rules = DocumentAnswerPrompts.rules();
                int base = generation.get() + 1;
                var generated =
                        events == null
                                ? models.complete(
                                        who,
                                        request.callId(),
                                        call + ":draft:" + attempt,
                                        "DOCUMENT_QA",
                                        rules,
                                        user,
                                        true)
                                : models.completeWithEvents(
                                        who,
                                        request.callId(),
                                        call + ":draft:" + attempt,
                                        "DOCUMENT_QA",
                                        rules,
                                        user,
                                        true,
                                        event -> {
                                            int version = base + event.generationVersion() - 1;

                                            generation.set(Math.max(generation.get(), version));
                                            events.accept(
                                                    new com.bbb.exercise.agentdemo.api.model
                                                            .ModelContracts.GenerationEvent(
                                                            event.type(),
                                                            version,
                                                            event.payload()));
                                        });

                input += generated.inputTokens();
                output += generated.outputTokens();
                requireLease(leaseFailure);
                int evaluationBase = generation.get() + 1;
                // 2.4. 独立评估：检查相关性、事实支持和引用，生成成功本身不足以提交答案。
                var evaluation =
                        events == null
                                ? evaluator.evaluate(
                                        who,
                                        request.callId(),
                                        call + ":eval:" + attempt,
                                        request.question(),
                                        combined,
                                        generated.text())
                                : evaluator.evaluateWithEvents(
                                        who,
                                        request.callId(),
                                        call + ":eval:" + attempt,
                                        request.question(),
                                        combined,
                                        generated.text(),
                                        event -> {
                                            int version =
                                                    evaluationBase + event.generationVersion() - 1;

                                            generation.accumulateAndGet(version, Math::max);
                                            events.accept(
                                                    new com.bbb.exercise.agentdemo.api.model
                                                            .ModelContracts.GenerationEvent(
                                                            event.type(),
                                                            version,
                                                            event.payload()));
                                        });

                input += evaluation.inputTokens();
                output += evaluation.outputTokens();
                requireLease(leaseFailure);
                repository.trace(
                        who,
                        request.conversationId(),
                        request.callId(),
                        "EVALUATION",
                        Map.of(
                                "attempt",
                                attempt,
                                "question",
                                request.question(),
                                "evidenceIds",
                                combined.stream().map(Evidence::chunkId).toList(),
                                "status",
                                evaluation.status(),
                                "reason",
                                evaluation.reason(),
                                "promptVersion",
                                "grounding-v2",
                                "inputTokens",
                                evaluation.inputTokens(),
                                "outputTokens",
                                evaluation.outputTokens()));

                // 2.4.1. 评估不可用：按错误处理，不能把未完成的校验视为通过。
                if (evaluation.status().equals("ERROR")) {
                    throw new RagException(
                            "ANSWER_EVALUATION_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
                }

                // 2.5. 验证通过：只收集回答实际引用的来源，并随答案一起持久提交。
                if (evaluation.status().equals("PASS")) {
                    Set<Integer> cited =
                            CitationValidator.validate(
                                    generated.text(), combined.size());
                    List<Source> sources = new ArrayList<>();

                    for (int id : cited) {
                        var e = combined.get(id - 1);
                        sources.add(
                                new Source(
                                        "S" + id,
                                        e.documentId(),
                                        e.chunkId(),
                                        e.title(),
                                        e.page(),
                                        e.text(),
                                        e.documentId() == null ? "IMAGE" : "DOCUMENT"));
                    }

                    var measured =
                            result(
                                    who,
                                    request,
                                    generated.text(),
                                    List.copyOf(sources),
                                    "PASS",
                                    retrieval.degraded());
                    var answer =
                            new Answer(
                                    measured.text(),
                                    measured.sources(),
                                    measured.evaluation(),
                                    measured.degraded(),
                                    measured.inputTokens(),
                                    measured.outputTokens(),
                                    measured.budgetTokens(),
                                    measured.unknownReservedTokens(),
                                    generated.provider(),
                                    generated.model(),
                                    generated.fallback());

                    repository.commit(who, request, lease.fence(), answer);

                    return answer;
                }

                feedback = CitationValidator.repairFeedback(generated.text(), combined.size(), evaluation.reason());
            }

            // 2.6. 修复仍失败：保存拒答结果，避免把未经证据支持的草稿作为最终答案。
            var refusal =
                    result(
                            who,
                            request,
                            "检索证据未能支持通过验证的回答，请补充问题或查看引用原文。",
                            List.of(),
                            "FAIL",
                            retrieval.degraded());

            repository.commit(who, request, lease.fence(), refusal);

            return refusal;
        } catch (RuntimeException error) {
            boolean budget =
                    error instanceof RagException rag && rag.code().equals("MODEL_TOKEN_BUDGET")
                            || error
                                            instanceof
                                            com.bbb.exercise.agentdemo.runtime.client
                                                                    .ModelCallClient
                                                                    .ModelCallException
                                                            model
                                    && model.code().equals("MODEL_TOKEN_BUDGET");

            boolean definite = error instanceof RagException rag && rag.code().equals("ANSWER_EVALUATION_UNAVAILABLE")
                    || error instanceof com.bbb.exercise.agentdemo.runtime.client.ModelCallClient.ModelCallException model
                    && Set.of("PROVIDER_REJECTED","PROVIDER_CONNECT_FAILED","MODEL_NOT_CONFIGURED","MODEL_CALL_INTERRUPTED").contains(model.code());
            repository.answerError(
                    who,
                    request,
                    lease.fence(),
                    budget ? "WAITING_FOR_BUDGET" : definite ? "FAILED" : "WAITING_FOR_RECONCILIATION");

            throw error;
        } finally {
            heartbeat.cancel(false);
        }
    }

    // 3. 租约围栏：续期失败后停止后续写入，防止旧回答覆盖接管者结果。
    private static void requireLease(AtomicReference<RuntimeException> failure) {
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private static void validateImageEvidence(ImageEvidence image) {
        if (image == null) return;
        if (image.text() == null || image.text().isBlank() || image.text().length() > 16000
                || image.assetIds() == null || image.assetIds().isEmpty() || image.assetIds().size() > 3) {
            throw new RagException("IMAGE_EVIDENCE_INVALID", HttpStatus.BAD_REQUEST);
        }
        Set<String> ids = new HashSet<>();
        for (String id : image.assetIds()) {
            if (id == null || !id.matches("[A-Za-z0-9_.:@-]{1,128}") || !ids.add(id)) {
                throw new RagException("IMAGE_EVIDENCE_INVALID", HttpStatus.BAD_REQUEST);
            }
        }
    }

    private static String retrievalQuestion(AnswerRequest request) {
        if (request.imageEvidence() == null) return request.question();
        // Keep the original question for answering/judging; use bounded observations to resolve
        // identifiers such as a SKU visible only in the image during document retrieval.
        return "当前问题（待分析数据）：\n"
                + com.bbb.exercise.agentdemo.ragservice.retrieval.EvidenceWindow.select(
                        request.question(), request.question(), 1850)
                + "\n图片附件识别结果（可能有误，仅用于定位文档）：\n"
                + com.bbb.exercise.agentdemo.ragservice.retrieval.EvidenceWindow.select(
                        request.imageEvidence().text(), request.question(), 2000);
    }

    @PreDestroy
    public void shutdown() {
        renewals.shutdownNow();
    }

    private Answer result(
            ChatIdentity who,
            AnswerRequest request,
            String text,
            List<Source> sources,
            String evaluation,
            boolean degraded) {
        var budget = models.budget(who, request.callId());
        var usage = models.accounting(who, request.callId());

        return new Answer(
                text,
                sources,
                evaluation,
                degraded,
                usage.inputTokens(),
                usage.outputTokens(),
                budget.accounted(),
                budget.held());
    }
}
