package com.bbb.exercise.agentdemo.orchestrator.execution;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import com.bbb.exercise.agentdemo.orchestrator.execution.ExecutionStepRunner.BudgetExhausted;
import com.bbb.exercise.agentdemo.orchestrator.execution.ExecutionStepRunner.StopAfterDispatch;
import static com.bbb.exercise.agentdemo.orchestrator.execution.ExecutionStepRunner.unwrap;
import com.bbb.exercise.agentdemo.mcp.*;
import com.bbb.exercise.agentdemo.api.model.ModelPrompts;

/** Persistent outer loop: model output never calls a tool itself. Remote IO happens outside database transactions. */
@Component
public class ExecutionWorker {
    private final ExecutionStore store;
    private final ExecutionStepRunner steps;
    private final ExecutionToolRegistry toolRegistry;
    private final McpToolExecutor mcpExecutor;
    private final ContextAssembler contextAssembler;
    private final MeterRegistry metrics;
    private final io.micrometer.observation.ObservationRegistry observations;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity = new Semaphore(4);
    private final String workerId = UUID.randomUUID().toString();

    public ExecutionWorker(ExecutionStore store, ExecutionClient client, MeterRegistry metrics) {
        this(store, client, metrics, io.micrometer.observation.ObservationRegistry.NOOP);
    }

    public ExecutionWorker(
            ExecutionStore store,
            ExecutionClient client,
            MeterRegistry metrics,
            io.micrometer.observation.ObservationRegistry observations) {
        this(store,client,metrics,observations,new ExecutionToolRegistry(List.of()));
    }
    public ExecutionWorker(ExecutionStore store,ExecutionClient client,MeterRegistry metrics,io.micrometer.observation.ObservationRegistry observations,ExecutionToolRegistry toolRegistry) {
        this(store,client,metrics,observations,toolRegistry,(who,call,args)->CompletableFuture.failedFuture(new IllegalStateException("MCP is disabled")));
    }
    public ExecutionWorker(ExecutionStore store,ExecutionClient client,MeterRegistry metrics,io.micrometer.observation.ObservationRegistry observations,ExecutionToolRegistry toolRegistry,McpToolExecutor mcpExecutor) {
        this(store,client,metrics,observations,toolRegistry,mcpExecutor,new ContextAssembler(ContextAssembler.DEFAULT_LIMIT));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionWorker(ExecutionStore store,ExecutionClient client,MeterRegistry metrics,io.micrometer.observation.ObservationRegistry observations,ExecutionToolRegistry toolRegistry,McpToolExecutor mcpExecutor,ContextAssembler contextAssembler) {
        this.store = store;
        this.steps = new ExecutionStepRunner(store, client, metrics);
        this.metrics = metrics;
        this.observations = observations;
        this.toolRegistry=toolRegistry;
        this.mcpExecutor=mcpExecutor;
        this.contextAssembler=contextAssembler;
    }

    // 1. 后台调度：先取得本机并发名额，再领取数据库租约。
    @Scheduled(fixedDelayString = "${app.execution.poll-ms:500}")
    public void poll() {
        while (capacity.tryAcquire()) {
            Optional<ExecutionStore.Lease> lease;

            try {
                lease = store.claim(workerId);
            } catch (Exception e) {
                capacity.release();
                metrics.counter("agent.execution.worker.errors", "phase", "claim").increment();

                return;
            }

            if (lease.isEmpty()) {
                capacity.release();
                return;
            }

            workers.submit(
                    () -> {
                        try {
                            run(lease.get());
                        } finally {
                            capacity.release();
                        }
                    });
        }
    }

    // 1.1. 执行观测：为一次租约执行关联任务类型和任务 ID。
    public void run(ExecutionStore.Lease lease) {
        io.micrometer.observation.Observation.createNotStarted("agent.execution", observations)
                .lowCardinalityKeyValue("type", store.leased(lease).input().type())
                .highCardinalityKeyValue("execution.id", lease.executionId())
                .observe(() -> runLoop(lease));
    }

    // 2. 执行主循环：恢复旧步骤后，按预算驱动本轮任务和工具决策。
    private void runLoop(ExecutionStore.Lease lease) {
        boolean paid = false;

        try {
            store.event(lease, "progress", Map.of("stage", "execution", "message", "任务开始处理"));
            var recovering = store.leased(lease);

            if (recovering.cancelled()) {
                store.terminal(lease, "STOPPED", null);
                return;
            }

            paid = !store.unfinishedSteps(lease.identity(), lease.executionId()).isEmpty();
            // 2.1. 恢复优先：先核对未完成的付费步骤，避免重启后再次派发同一调用。
            steps.recoverPending(lease, recovering);
            var finalReceipt =
                    store.completedSteps(lease).stream()
                            .filter(s -> s.key().startsWith("g" + recovering.inputRevision() + ":"))
                            .filter(
                                    s ->
                                            s.key().endsWith(":chat")
                                                    || s.key().endsWith(":document")
                                                    || s.key().endsWith(":verified-final"))
                            .reduce((first, last) -> last);

            if (finalReceipt.isPresent()) {
                commit(
                        lease,
                        finalReceipt.get().result(),
                        recovering.input().type().equals("CHAT") ? "NOT_APPLICABLE" : "PASS");
                return;
            }

            // 2.2. 逐轮准入：每轮重读停止状态和预算，再决定是否继续执行。
            while (true) {
                var row = store.leased(lease);

                if (row.cancelled()) {
                    store.terminal(lease, "STOPPED", null);
                    return;
                }

                var admission = row.budget().admit();

                if (!admission.reason().equals("READY")) {
                    store.terminal(
                            lease,
                            admission.reason(),
                            admission.reason().equals("NEEDS_INPUT")
                                    ? "任务暂时没有取得新进展，请补充信息"
                                    : "执行预算已到上限，处理进度已保留");
                    return;
                }

                store.saveBudget(lease, admission.budget());
                row = store.leased(lease);
                String prefix = "g" + row.inputRevision() + ":r" + row.budget().roundsUsed();

                // 2.2.1. 直接聊天：通过 Chat 流式调用提交回答，不进入工具决策循环。
                if (row.input().type().equals("CHAT")) {
                    paid = true;
                    String key = prefix + ":chat";
                    var result =
                            steps.invokeStream(
                                    lease,
                                    key,
                                    preparedCompletion(lease, key, completion(
                                            row,
                                            callId(lease, key),
                                            "CHAT",
                                            row.input().attachments() == null || row.input().attachments().isEmpty()
                                                    ? "" : ModelPrompts.taskRules("CHAT"),
                                            row.input().question())));

                    commit(lease, result, "NOT_APPLICABLE");

                    return;
                }

                // 2.2.2. 文档问答：交由 RAG 完成证据检索和回答验证。
                if (row.input().type().equals("DOCUMENT_QA")) {
                    paid = true;
                    Map<String, Object> imageEvidence = null;
                    if (row.input().attachments() != null && !row.input().attachments().isEmpty()) {
                        // The image checkpoint is stable across rounds, including crash recovery.
                        String imageKey = "g" + row.inputRevision() + ":vision";
                        var imageBody = completion(row, callId(lease, imageKey), "CHAT",
                                ModelPrompts.taskRules("IMAGE_EVIDENCE"), row.input().question());
                        imageBody.put("conversationId", null);
                        imageBody = preparedCompletion(lease, imageKey, imageBody);
                        store.event(lease, "progress", Map.of("stage", "images", "message", "正在理解图片附件"));
                        var observed = steps.invoke(lease, imageKey, "chat", "/internal/chat/complete",
                                imageBody, false, "DIRECT", null);
                        imageEvidence = Map.of("text", text(observed, "text"), "assetIds",
                                row.input().attachments().stream().map(a -> a.getAssetId()).toList());
                        row = store.leased(lease);
                    }
                    String key = prefix + ":document";
                    var documentBody = new LinkedHashMap<>(answer(row, callId(lease, key), row.input().question()));
                    if (imageEvidence != null) documentBody.put("imageEvidence", imageEvidence);
                    var result =
                            steps.invokeStreamCore(
                                    lease,
                                    key,
                                    "rag",
                                    documentBody);

                    commit(lease, result, "PASS");

                    return;
                }

                paid = true;
                // 2.2.3. Agent 决策：模型仅返回结构化决定，工具由服务端校验并执行。
                String key = prefix + ":decision";
                String context = context(lease, row);
                var decisionScope=toolScope(lease,row,prefix+":tool");
                var savedDecision=store.completedSteps(lease).stream().filter(s->s.key().equals(key)&&"DECISION".equals(s.consumption())&&"chat".equals(s.target())).findFirst();
                var model = savedDecision.isPresent()?savedDecision.get().result():
                        steps.invoke(
                                lease,
                                key,
                                "chat",
                                "/internal/chat/complete",
                                preparedCompletion(lease, key, completion(
                                        row,
                                        callId(lease, key),
                                        "DECISION",
                                        "\n服务端已注册工具及参数：\n"+toolRegistry.descriptions(decisionScope),
                                        context)),
                                false,
                                "DECISION",
                                null);
                var decision =
                        AgentDecision.parse(
                                tools.jackson.databind.json.JsonMapper.builder().build(),
                                text(model, "text"),toolRegistry.names(decisionScope));

                row = store.leased(lease);

                // 2.3. 暂停分支：保存需要补充输入的原因和当前预算。
                if (decision.type().equals("PAUSE")) {
                    store.terminalWithBudget(
                            lease,
                            "NEEDS_INPUT",
                            decision.answer() == null ? "请补充任务信息" : decision.answer(),
                            row.budget().recordDecision(0, 0, 0, "pause", false, false));
                    return;
                }

                // 2.4. 完成分支：带文档范围时仍经 RAG 验证，再提交最终结果。
                if (decision.type().equals("FINAL")) {
                    if (row.input().documentConversationId() != null) {
                        String finalKey = prefix + ":verified-final";
                        var verified =
                                steps.invoke(
                                        lease,
                                        finalKey,
                                        "rag",
                                        "/internal/rag/answer",
                                        answer(
                                                row,
                                                callId(lease, finalKey),
                                                row.input().question()),
                                        false,
                                        "DIRECT",
                                        null);

                        commit(lease, verified, "PASS");
                    } else {
                        store.complete(
                                lease,
                                decision.answer(),
                                List.of(),
                                "NOT_APPLICABLE",
                                row.budget().recordDecision(0, 0, 0, "final", true, false));
                    }

                    return;
                }

                String toolKey = prefix + ":tool";
                Map<String, Object> result;

                // 2.5. 工具派发：只执行白名单工具，并逐项检查文档范围或图片生成授权。
                var scope=toolScope(lease,row,toolKey);
                result=toolRegistry.dispatch(decision,scope,plan->plan.mcp()!=null?steps.invokeMcp(lease,toolKey,plan,decision.tool(),mcpExecutor):plan.get()
                        ?steps.invokeGet(lease,toolKey,plan.target(),plan.path(),"TOOL",decision.tool())
                        :steps.invoke(lease,toolKey,plan.target(),plan.path(),plan.body(),false,"TOOL",decision.tool()));
                store.event(
                        lease,
                        "progress",
                        Map.of("stage", "tool", "message", "工具已返回结果", "tool", decision.tool()));
            }
        } catch (McpDispatchUncertain e) {
            safeTerminal(lease,"WAITING_FOR_RECONCILIATION",e.getMessage());
        } catch (BudgetExhausted e) {
            safeTerminal(lease, e.reason, "执行预算已到上限，处理进度已保留");
        } catch (StopAfterDispatch e) {
            safeTerminal(lease, "WAITING_FOR_RECONCILIATION", "停止请求已发送，模型调用结果仍待核对");
        } catch (IllegalArgumentException e) {
            safeTerminal(lease, "NEEDS_INPUT", e.getMessage());
        } catch (Exception e) {
            Throwable cause = unwrap(e);

            if (cause instanceof ExecutionClient.RemoteFailure r
                    && "IMAGE_ATTACHMENT_UNAVAILABLE".equals(r.code)) {
                safeTerminal(lease, "NEEDS_INPUT", "图片附件不可用或已过期，请重新上传图片后发送新消息");
                return;
            }

            if (cause instanceof org.springframework.web.server.ResponseStatusException r
                    && r.getReason() != null
                    && r.getReason().contains("租约")) {
                return;
            }

            if(cause instanceof ExecutionClient.RemoteFailure remote && "ANSWER_EVALUATION_UNAVAILABLE".equals(remote.code)) {
                safeTerminal(lease,"FAILED","文档答案校验暂时不可用，未发布未经核验的回答。可以重新发送问题。");return;
            }
            if(cause instanceof ExecutionClient.RemoteFailure remote && Set.of("PROVIDER_REJECTED","PROVIDER_CONNECT_FAILED","MODEL_NOT_CONFIGURED","MODEL_CALL_INTERRUPTED").contains(String.valueOf(remote.code))) {
                safeTerminal(lease,"FAILED","本次调用未完成，账户不会永久占用；请重试或发送新消息");return;
            }
            int status =
                    cause instanceof ExecutionClient.RemoteFailure remote ? remote.status : 503;
            String state =
                    status == 429 || status == 402
                            ? "WAITING_FOR_BUDGET"
                            : (paid && (status == 409 || status >= 500))
                                    ? "WAITING_FOR_RECONCILIATION"
                                    : status == 400 || status == 404 ? "NEEDS_INPUT" : "FAILED";

            safeTerminal(
                    lease,
                    state,
                    state.equals("WAITING_FOR_RECONCILIATION")
                            ? "调用结果暂时不明，处理进度已保留；核对完成后可以继续"
                            : "任务暂时无法继续，输入和已完成步骤已保留");
        }
    }

    /** A durable completion request stays authoritative when prompt rules change after a restart. */
    private Map<String, Object> preparedCompletion(
            ExecutionStore.Lease lease, String key, Map<String, Object> current) {
        var pending = store.unfinishedSteps(lease.identity(), lease.executionId()).stream()
                .filter(step -> key.equals(step.key())).findFirst();
        if (pending.isEmpty()) return current;
        var step = pending.get();
        String id = callId(lease, key);
        if (!id.equals(step.callId()) || !"chat".equals(step.target())
                || !"/internal/chat/complete".equals(step.path()) || step.requestJson() == null) {
            throw new IllegalArgumentException("恢复生成调用缺少匹配的原始请求");
        }
        var original = store.map(step.requestJson());
        if (!id.equals(original.get("callId")) || !Objects.equals(current.get("mode"), original.get("mode"))) {
            throw new IllegalArgumentException("恢复生成调用的原始请求无效");
        }
        // StepRunner/Store still validate the original parameter hash before any dispatch.
        return new LinkedHashMap<>(original);
    }

    private static Map<String, Object> completion(
            ExecutionStore.Row row, String id, String mode, String system, String user) {
        var body = new LinkedHashMap<String, Object>();

        body.put("callId", id);
        body.put("mode", mode);
        body.put("system", system);
        body.put("user", user);
        body.put("conversationId", row.input().conversationId());
        if ("CHAT".equals(mode) && row.input().attachments() != null && !row.input().attachments().isEmpty()) {
            body.put("attachments", row.input().attachments());
        }

        return body;
    }

    private static Map<String, Object> answer(ExecutionStore.Row row, String id, String question) {
        return Map.of(
                "callId",
                id,
                "conversationId",
                row.input().documentConversationId(),
                "question",
                question);
    }

    private ExecutionToolRegistry.Scope toolScope(ExecutionStore.Lease lease,ExecutionStore.Row row,String stepKey) {
        var known=new HashSet<String>();
        for(var completed:store.completedSteps(lease))if(completed.result().get("evidence") instanceof List<?> evidence)
            for(Object item:evidence)if(item instanceof Map<?,?> source&&source.get("documentId") instanceof String id)known.add(id);
        return new ExecutionToolRegistry.Scope(lease.identity(),row.input().conversationId(),row.input().documentConversationId(),Boolean.TRUE.equals(row.input().allowImageGeneration()),callId(lease,stepKey),row.input().question(),known);
    }
    // 3. 决策上下文：将当前问题和已有工具回执组织为模型输入。
    private String context(ExecutionStore.Lease lease, ExecutionStore.Row row) {
        return contextAssembler.context(row.input(), store.completedSteps(lease));
    }

    // 3.1. 最终提交：把回答、来源和评估状态一起写入任务终态。
    @SuppressWarnings("unchecked")
    private void commit(
            ExecutionStore.Lease lease, Map<String, Object> result, String defaultEvaluation) {
        String answer = text(result, "text");

        if (answer.isBlank()) {
            throw new IllegalArgumentException("没有可提交的答案");
        }

        List<Map<String, Object>> sources =
                result.get("sources") instanceof List<?> s
                        ? (List<Map<String, Object>>) s
                        : List.of();

        if (result.get("provider") instanceof String provider
                && result.get("model") instanceof String model
                && !provider.isBlank()
                && !model.isBlank()) {
            store.event(
                    lease,
                    "model_status",
                    Map.of(
                            "provider",
                            provider,
                            "model",
                            model,
                            "capability",
                            store.leased(lease).input().type(),
                            "state",
                            "SUCCEEDED",
                            "fallback",
                            Boolean.TRUE.equals(result.get("fallback"))));
        }

        store.complete(
                lease,
                answer,
                sources,
                String.valueOf(result.getOrDefault("evaluation", defaultEvaluation)));
        metrics.counter(
                        "agent.execution.completed",
                        "type",
                        store.get(lease.identity(), lease.executionId()).input().type())
                .increment();
    }

    /** Stable ID retained for callers that correlate existing execution receipts. */
    public static String callId(ExecutionStore.Lease lease, String key) {
        return ExecutionStepRunner.callId(lease, key);
    }

    private void safeTerminal(ExecutionStore.Lease lease, String state, String message) {
        try {
            store.terminal(lease, state, message);
        } catch (Exception ignored) {
            metrics.counter("agent.execution.worker.errors", "phase", "terminal").increment();
        }
    }

    private static String text(Map<String, Object> map, String key) {
        return String.valueOf(map.getOrDefault(key, ""));
    }

    // 4. 执行器关闭：应用退出时停止接收新的本机执行工作。
    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }

}
