package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.mcp.schema.ToolSchema;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.bbb.exercise.agentdemo.mcp.*;

/** Executes and reconciles durable remote steps under the owning Store lease. */
final class ExecutionStepRunner {
    private final ExecutionStore store;
    private final ExecutionClient client;
    private final MeterRegistry metrics;

    ExecutionStepRunner(ExecutionStore store, ExecutionClient client, MeterRegistry metrics) {
        this.store = store;
        this.client = client;
        this.metrics = metrics;
    }

    // 3. 远端步骤调用：流式与普通调用均复用稳定步骤键和已保存回执。
    @SuppressWarnings("unchecked")
    Map<String, Object> invokeStream(ExecutionStore.Lease lease, String key, Object body)
            throws Exception {
        return invokeStreamCore(lease, key, "chat", body);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> invokeStreamCore(
            ExecutionStore.Lease lease, String key, String target, Object body) throws Exception {
        var step =
                store.beginStep(
                        lease,
                        key,
                        ExecutionStore.hash(store.write(body)),
                        callId(lease, key),
                        target,
                        target.equals("rag") ? "/internal/rag/answer" : "/internal/chat/complete",
                        body,
                        "DIRECT",
                        null);

        if (step.status().equals("SUCCEEDED")) {
            return step.result();
        }

        admitDispatch(lease);
        long limit = store.reserveStepTokens(lease, step);
        var row = store.leased(lease);
        int generation = row.view().generationVersion();

        if (row.view().answer() != null && !row.view().answer().isBlank()) {
            generation++;
            store.streamEvent(lease, "answer_replace", generation, Map.of("text", ""));
        }

        final int base = generation;
        long started = System.nanoTime();
        var timer = io.micrometer.core.instrument.Timer.start(metrics);

        try {
            java.util.function.Consumer<Map<String, Object>> forward =
                    event -> {
                        int version =
                                base + (int) number(event.getOrDefault("generationVersion", 1)) - 1;
                        store.streamEvent(
                                lease,
                                String.valueOf(event.get("type")),
                                version,
                                (Map<String, Object>) event.get("payload"));
                    };
            var result =
                    await(
                            lease,
                            step,
                            started,
                            target.equals("rag")
                                    ? client.streamRag(
                                            lease.identity(), withBudget(body, limit), forward)
                                    : client.stream(
                                            lease.identity(), withBudget(body, limit), forward));

            persistResult(
                    lease,
                    step,
                    result,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    "DIRECT",
                    null);

            return result;
        } finally {
            accountTime(lease, step, started);
            timer.stop(
                    metrics.timer(
                            "agent.execution.step.duration", "target", "chat", "kind", "DIRECT"));
        }
    }

    Map<String, Object> invoke(
            ExecutionStore.Lease lease,
            String key,
            String target,
            String path,
            Object body,
            boolean readOnly,
            String consumption,
            String tool)
            throws Exception {
        var step =
                store.beginStep(
                        lease,
                        key,
                        ExecutionStore.hash(store.write(body)),
                        callId(lease, key),
                        target,
                        path,
                        body,
                        consumption,
                        tool);

        if (step.status().equals("SUCCEEDED")) {
            return step.result();
        }

        admitDispatch(lease);
        long limit = store.reserveStepTokens(lease, step);
        long started = System.nanoTime();
        var timer = io.micrometer.core.instrument.Timer.start(metrics);

        try {
            var result =
                    await(
                            lease,
                            step,
                            started,
                            client.post(
                                    lease.identity(),
                                    target,
                                    path,
                                    withBudget(body, limit),
                                    readOnly));

            persistResult(
                    lease,
                    step,
                    result,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    consumption,
                    tool);

            if (Boolean.TRUE.equals(result.get("fallback"))) {
                store.event(lease, "fallback_started", Map.of("message", "备用服务已完成回答"));
            }

            return result;
        } catch (Exception e) {
            if (tool != null) {
                metrics.counter("agent.tool.calls", "tool", tool, "outcome", "failure").increment();
            }

            throw e;
        } finally {
            accountTime(lease, step, started);
            timer.stop(
                    metrics.timer(
                            "agent.execution.step.duration",
                            "target",
                            target,
                            "kind",
                            consumption));
        }
    }

    Map<String, Object> invokeGet(
            ExecutionStore.Lease lease,
            String key,
            String target,
            String path,
            String consumption,
            String tool)
            throws Exception {
        var step =
                store.beginStep(
                        lease,
                        key,
                        ExecutionStore.hash(path),
                        callId(lease, key),
                        target,
                        path,
                        null,
                        consumption,
                        tool);

        if (step.status().equals("SUCCEEDED")) {
            return step.result();
        }

        admitDispatch(lease);
        long at = System.nanoTime();

        try {
            var result = await(lease, step, at, client.get(lease.identity(), target, path));

            persistResult(lease, step, result, 0, consumption, tool);

            return result;
        } finally {
            accountTime(lease, step, at);
        }
    }

    Map<String,Object> invokeMcp(ExecutionStore.Lease lease,String key,ExecutionToolRegistry.Plan plan,String tool,McpToolExecutor executor) throws Exception {
        if(plan.mcp()==null)throw new IllegalArgumentException("Missing MCP target");
        if(store.unfinishedSteps(lease.identity(),lease.executionId()).stream().anyMatch(s->s.key().equals(key)))throw new McpDispatchUncertain();
        var request=Map.of("serverId",plan.mcp().serverId(),"tool",plan.mcp().tool(),"schemaHash",plan.mcp().schemaHash(),"arguments",plan.body());
        String hash=ExecutionStore.hash(ToolSchema.canonical(request));
        var completed=store.completedSteps(lease).stream().filter(s->s.key().equals(key)).findFirst();
        if(completed.isPresent()) {
            var previous=completed.get();
            if(!previous.hash().equals(hash)||!previous.callId().equals(callId(lease,key)))throw new IllegalArgumentException("步骤参数不一致");
            return previous.result();
        }
        admitDispatch(lease);
        var step=store.beginStep(lease,key,hash,callId(lease,key),"mcp",plan.path(),request,"TOOL",tool);
        long started=System.nanoTime();
        try {
            var result=await(lease,step,started,executor.execute(lease.identity(),plan.mcp(),plan.body()));
            // MCP content is not a model usage receipt, even if a server supplies token-like fields.
            var safe=new LinkedHashMap<>(result);safe.put("inputTokens",0L);safe.put("outputTokens",0L);safe.put("unknownTokens",0L);safe.remove("unknownReservedTokens");
            persistResult(lease,step,safe,0,"TOOL",tool);
            return safe;
        } catch(Exception error) {
            metrics.counter("agent.tool.calls","tool",tool,"outcome","unknown").increment();
            throw new McpDispatchUncertain();
        } finally {accountTime(lease,step,started);}
    }

    // 3.1. 结果登记：持久化远端回执，并更新本轮预算和执行进展。
    private void persistResult(
            ExecutionStore.Lease lease,
            ExecutionStore.Step step,
            Map<String, Object> result,
            long elapsed,
            String consumption,
            String tool) {
        long input = number(result.getOrDefault("inputTokens", 0)),
                output = number(result.getOrDefault("outputTokens", 0));

        store.settleStepTokens(lease, step, input, output, unknownTokens(result));
        var b = store.leased(lease).budget();
        String fingerprint = ExecutionStore.hash("mcp".equals(step.target())?ToolSchema.canonical(result):store.write(result));
        boolean progress =
                !Boolean.TRUE.equals(result.get("isError")) && !result.isEmpty()
                        && store.completedSteps(lease).stream()
                                .filter(s -> s.key().endsWith(":tool"))
                                .noneMatch(
                                        s ->
                                                ExecutionStore.hash("mcp".equals(step.target())?ToolSchema.canonical(s.result()):store.write(s.result()))
                                                        .equals(fingerprint));

        if (result.containsKey("evidence")
                && result.get("evidence") instanceof List<?> evidence
                && evidence.isEmpty()) {
            progress = false;
        }

        var updated =
                consumption.equals("DECISION")
                        ? b
                        : b.recordDecision(
                                0, 0, 0, fingerprint, progress, consumption.equals("TOOL"));

        store.finishStep(lease, step, result, updated);

        if (tool != null) {
            metrics.counter("agent.tool.calls", "tool", tool, "outcome", Boolean.TRUE.equals(result.get("isError"))?"tool_error":"success").increment();
        }
    }

    // 3.2. 等待远端：等待期间持续处理租约与停止信号。
    private Map<String, Object> await(
            ExecutionStore.Lease lease,
            ExecutionStore.Step step,
            long started,
            CompletableFuture<Map<String, Object>> future)
            throws Exception {
        try {
            long heartbeat = System.nanoTime();

            while (true) {
                var row = store.leased(lease);

                if (row.cancelled()) {
                    throw new StopAfterDispatch();
                }

                try {
                    return future.get(500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    if (System.nanoTime() - heartbeat >= 5_000_000_000L) {
                        if (!store.renew(lease)) {
                            throw new IllegalStateException("执行租约已失效");
                        }

                        accountTime(lease, step, started);

                        if (store.leased(lease).budget().activeMillis() >= 1_800_000) {
                            throw new StopAfterDispatch();
                        }

                        heartbeat = System.nanoTime();
                    }
                }
            }
        } catch (ExecutionException error) {
            if (unwrap(error) instanceof ExecutionClient.RemoteFailure r
                    && "IMAGE_ATTACHMENT_UNAVAILABLE".equals(r.code)) {
                // The allowlisted rejection occurs before model dispatch; no unknown usage to hold.
                store.settleStepTokens(lease, step, 0, 0, 0);
            }
            throw error;
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    // 3.3. 派发边界：真正请求远端前再次确认任务仍可执行。
    private void admitDispatch(ExecutionStore.Lease lease) {
        var admission = store.leased(lease).budget().admit();

        if (!admission.reason().equals("READY")) {
            throw new BudgetExhausted(admission.reason());
        }

        store.saveBudget(lease, admission.budget());
    }

    @SuppressWarnings("unchecked")
    private static Object withBudget(Object body, long limit) {
        var bounded = new LinkedHashMap<String, Object>((Map<String, Object>) body);

        bounded.put("tokenBudget", limit);

        return bounded;
    }

    // 4. 未完成步骤恢复：查询远端调用状态，区分成功回执和仍需核对的结果。
    void recoverPending(ExecutionStore.Lease lease, ExecutionStore.Row row)
            throws Exception {
        for (var step : store.reconcilableSteps(lease.identity(), lease.executionId())) {
            if("mcp".equals(step.target()))throw new McpDispatchUncertain();
            boolean rag = "rag".equals(step.target());
            String path =
                    rag
                            ? "/internal/rag/calls/" + step.callId()
                            : "/internal/billing/users/"
                                    + lease.identity().userId()
                                    + "/groups/"
                                    + step.callId();
            Map<String, Object> state;

            try {
                state =
                        await(
                                lease,
                                step,
                                System.nanoTime(),
                                client.get(lease.identity(), rag ? "rag" : "auth", path));
            } catch (ExecutionException e) {
                if (unwrap(e) instanceof ExecutionClient.RemoteFailure r && r.status == 404) {
                    store.settleStepTokens(lease, step, 0, 0, 0);
                    continue;
                }

                throw e;
            }

            String status = String.valueOf(state.get("status"));

            if (step.status().equals("SUCCEEDED")) {
                store.settleStepTokens(
                        lease,
                        step,
                        number(state.getOrDefault("inputTokens", 0)),
                        number(state.getOrDefault("outputTokens", 0)),
                        unknownTokens(state));
                continue;
            }

            if (Set.of("UNKNOWN", "RUNNING", "DISPATCHED").contains(status)) {
                throw new ExecutionClient.RemoteFailure(409);
            }

            if (state.get("receipt") != null && Set.of("SUCCEEDED", "RECEIVED").contains(status)) {
                if (step.requestJson() == null || step.target() == null || step.path() == null) {
                    throw new IllegalArgumentException("恢复调用缺少原始请求，请重新创建任务");
                }

                // A billing receipt proves model completion; only the owner can prove history and
                // scope commit.
                long started = System.nanoTime();
                Map<String, Object> result;

                try {
                    result =
                            await(
                                    lease,
                                    step,
                                    started,
                                    client.post(
                                            lease.identity(),
                                            step.target(),
                                            step.path(),
                                            withBudget(store.map(step.requestJson()), 0),
                                            false));
                } finally {
                    accountTime(lease, step, started);
                }

                var accounted = new LinkedHashMap<String, Object>(result);

                if (state.containsKey("inputTokens")) {
                    accounted.put("inputTokens", state.get("inputTokens"));
                }

                if (state.containsKey("outputTokens")) {
                    accounted.put("outputTokens", state.get("outputTokens"));
                }

                accounted.put(
                        "unknownTokens",
                        state.containsKey("unknownTokens")
                                ? unknownTokens(state)
                                : unknownTokens(result));
                persistResult(
                        lease,
                        step,
                        accounted,
                        0,
                        step.consumption() == null ? "DIRECT" : step.consumption(),
                        step.toolName());
            } else {
                store.settleStepTokens(
                        lease,
                        step,
                        number(state.getOrDefault("inputTokens", 0)),
                        number(state.getOrDefault("outputTokens", 0)),
                        unknownTokens(state));
            }
        }
    }

    private static long unknownTokens(Map<String, Object> value) {
        return number(
                value.getOrDefault(
                        "unknownTokens", value.getOrDefault("unknownReservedTokens", 0)));
    }

    private void accountTime(ExecutionStore.Lease lease, ExecutionStore.Step step, long started) {
        try {
            store.accountStepTime(
                    lease,
                    step,
                    Math.max(1, Duration.ofNanos(System.nanoTime() - started).toMillis()));
        } catch (Exception e) {
            metrics.counter("agent.execution.worker.errors", "phase", "time-checkpoint")
                    .increment();
        }
    }

    static String callId(ExecutionStore.Lease lease, String key) {
        return "exec:" + lease.executionId() + ":" + ExecutionStore.hash(key).substring(0, 24);
    }

    private static long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }

        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            throw new IllegalArgumentException("用量或工具参数无效");
        }
    }

    static Throwable unwrap(Throwable e) {
        while ((e instanceof ExecutionException || e instanceof CompletionException)
                && e.getCause() != null) {
            e = e.getCause();
        }

        return e;
    }

    /** 已派发付费调用后的停止信号，要求保留待确认状态而非重复派发。 */
    static class StopAfterDispatch extends RuntimeException {}

    /** 执行预算耗尽信号，携带终止原因供工作节点提交状态。 */
    static class BudgetExhausted extends RuntimeException {
        final String reason;

        BudgetExhausted(String reason) {
            this.reason = reason;
        }
    }
}
