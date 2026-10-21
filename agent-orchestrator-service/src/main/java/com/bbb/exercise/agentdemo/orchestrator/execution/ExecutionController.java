package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/** 持久化执行入口，提供创建、停止、恢复、查询及按游标回放事件。 */
@RestController
@RequestMapping("/api/agent-executions")
public class ExecutionController {
    private static final Set<String> TERMINAL =
            Set.of(
                    "COMPLETED",
                    "FAILED",
                    "STOPPED",
                    "WAITING_FOR_BUDGET",
                    "WAITING_FOR_RECONCILIATION",
                    "NEEDS_INPUT");
    /** 心跳间隔：与改造前一致，客户端据此判断连接存活。 */
    private static final long HEARTBEAT_SECONDS = 15;

    private final ExecutionStore store;
    private final ExecutionIdentity identities;
    private final ExecutionClient client;

    /** 首字耗时等流式指标（F14）。 */
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    /** 任务级共享轮询（F10）。无外部依赖，随 controller 生命周期存在。 */
    private final ExecutionPollers pollers = new ExecutionPollers();

    public ExecutionController(
            ExecutionStore store, ExecutionIdentity identities, ExecutionClient client) {
        this(store, identities, client, io.micrometer.core.instrument.Metrics.globalRegistry);
    }

    /**
     * 容器装配入口。
     *
     * <p>为什么必须显式 {@code @Autowired}：本类有两个构造器（另一个给测试手工构造用），
     * 一旦出现多个候选，Spring 不再做"唯一构造器"推导，而是回退去找无参构造器，
     * 结果就是启动期 {@code No default constructor found}。显式标注可消除这类歧义。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionController(
            ExecutionStore store,
            ExecutionIdentity identities,
            ExecutionClient client,
            io.micrometer.core.instrument.MeterRegistry metrics) {
        this.store = store;
        this.identities = identities;
        this.client = client;
        this.metrics = metrics;
    }

    // 1. 持久任务入口：保存任务和请求幂等键，由后台执行器异步处理。
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<ExecutionStore.View> create(
            @RequestBody ExecutionStore.Create input, ServerWebExchange exchange) {
        return identities.require(exchange).flatMap(i -> work(() -> store.create(i, input).view()));
    }

    // 1.1. 请求恢复：按原 requestId 找回已有任务，避免网络重试创建重复任务。
    @GetMapping
    public Mono<ExecutionStore.View> lookup(
            @RequestParam String requestId, ServerWebExchange exchange) {
        return identities
                .require(exchange)
                .flatMap(i -> work(() -> store.byRequest(i, requestId).view()));
    }

    @GetMapping("/{id}")
    public Mono<ExecutionStore.View> get(@PathVariable String id, ServerWebExchange exchange) {
        return identities.require(exchange).flatMap(i -> work(() -> store.get(i, id).view()));
    }

    // 2. 停止任务：登记停止请求，已派发步骤仍保留结果核对责任。
    @PostMapping("/{id}/stop")
    public Mono<ExecutionStore.View> stop(@PathVariable String id, ServerWebExchange exchange) {
        return identities.require(exchange).flatMap(i -> work(() -> store.stop(i, id).view()));
    }

    // 2.1. 继续任务：校验恢复条件后沿用已有进度和预算。
    @PostMapping("/{id}/resume")
    public Mono<ExecutionStore.View> resume(
            @PathVariable String id,
            @RequestBody ExecutionStore.Resume input,
            ServerWebExchange exchange) {
        return identities
                .require(exchange)
                .flatMap(
                        i ->
                                work(
                                        () -> {
                                            var row = store.get(i, id);
                                            boolean reconciled = false;

                                            if (row.view()
                                                            .status()
                                                            .equals("WAITING_FOR_RECONCILIATION")
                                                    || row.view()
                                                                    .status()
                                                                    .equals("WAITING_FOR_BUDGET")
                                                            && row.budget().reservedTokens() > 0) {
                                                verifyReconciled(i, id, row);
                                                reconciled = true;
                                            }

                                            return store.resume(i, id, input, reconciled).view();
                                        }));
    }

    // 2.1.1. 付费步骤核对：恢复前确认远端未知调用是否已得到明确结果。
    private void verifyReconciled(ChatIdentity identity, String id, ExecutionStore.Row row)
            throws Exception {
        for (var step : store.reconcilableSteps(identity, id)) {
            if("mcp".equals(step.target()))throw new ResponseStatusException(HttpStatus.CONFLICT,"外部工具结果不明，本阶段不支持自动恢复；确认后请新建任务");
            boolean rag = "rag".equals(step.target());
            String path =
                    rag
                            ? "/internal/rag/calls/" + step.callId()
                            : "/internal/billing/users/"
                                    + identity.userId()
                                    + "/groups/"
                                    + step.callId();
            Map<String, Object> state;

            try {
                state = client.get(identity, rag ? "rag" : "auth", path).get(12, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof ExecutionClient.RemoteFailure r && r.status == 404) {
                    continue;
                }

                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法确认模型调用状态");
            }

            if("ABANDONED".equals(state.get("status")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"该调用已中断且账户占用已解除，请重新发送消息；旧调用不会重复派发");
            if (!Set.of(
                            "SUCCEEDED",
                            "RECEIVED",
                            "CANCELLED",
                            "QUEUED",
                            "RESERVED",
                            "FAILED_RESOLVED")
                    .contains(String.valueOf(state.get("status")))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "模型调用仍待核对，请稍后重试");
            }
        }
    }

    // 3. 事件回放：按持久序号读取事件，支持客户端断线续接。
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> events(
            @PathVariable String id,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(required = false) Long after,
            ServerWebExchange exchange) {
        long offset = cursor(id, lastEventId, after);
        return identities
                .require(exchange)
                .flatMapMany(
                        identity ->
                                work(() -> {
                                            store.get(identity, id);
                                            store.events(identity, id, offset, 1);

                                            return identity;
                                        })
                                        .flatMapMany(i -> stream(i, id, offset)));
    }

    Flux<ServerSentEvent<Object>> stream(ChatIdentity identity, String id, long after) {
        var cursor = new AtomicLong(after);
        var subscribedAt = System.nanoTime();
        var firstEvent = new java.util.concurrent.atomic.AtomicBoolean();
        Flux<ServerSentEvent<Object>> data =
                pollers
                        // F10：状态轮询按任务共享，多订阅者不再各拉一路。
                        .join(identity, id, () -> store.snapshot(identity, id))
                        .concatMap(
                                snapshot -> {
                                    long from = cursor.get();

                                    if (from > snapshot.lastSeq()) {
                                        return Mono.error(
                                                new ResponseStatusException(
                                                        HttpStatus.BAD_REQUEST, "事件游标无效"));
                                    }

                                    boolean terminal = TERMINAL.contains(snapshot.status());

                                    // 没有新事件时直接结束本 tick：不再为了判断终态多查一次执行行，
                                    // 也不为空窗口发一条 events 查询。
                                    if (snapshot.lastSeq() <= from) {
                                        return terminal ? Mono.just(new Batch(List.of(), true)) : Mono.empty();
                                    }

                                    return work(() -> store.events(identity, id, from, 200))
                                            .map(
                                                    events ->
                                                            new Batch(
                                                                    events,
                                                                    terminal
                                                                            && events.getLast().seq()
                                                                                    == snapshot
                                                                                            .lastSeq()));
                                })
                        .takeUntil(Batch::done)
                        .concatMap(batch -> Flux.fromIterable(batch.events()))
                        .doOnNext(
                                e -> {
                                    cursor.set(e.seq());

                                    // F14：首字耗时。只记第一条，后续事件进入同一条 timer 没有意义。
                                    if (firstEvent.compareAndSet(false, true)) {
                                        metrics.timer("agent.execution.stream.first-event")
                                                .record(
                                                        System.nanoTime() - subscribedAt,
                                                        TimeUnit.NANOSECONDS);
                                    }
                                })
                        .map(
                                e ->
                                        ServerSentEvent.<Object>builder(e)
                                                .event(e.type())
                                                .id(e.executionId() + ":" + e.seq())
                                                .build());

        return withHeartbeat(data);
    }

    /**
     * 心跳包装（F10 顺带修）。
     *
     * <p>原实现用 {@code data.publish(shared -> Flux.merge(shared, …takeUntilOther(shared.ignoreElements())))}，
     * 其中 {@code shared} 被订阅了两次——`publish` 的交由函数只允许一次订阅，第二次会排队甚至
     * 触发额外的上游订阅，这也是查询数远超"每 tick 固定几条"的原因之一。改为显式单订阅：
     * 数据流订阅一次，心跳由独立定时器写入同一个 sink，二者同生同灭。</p>
     */
    private static Flux<ServerSentEvent<Object>> withHeartbeat(Flux<ServerSentEvent<Object>> data) {
        return Flux.defer(
                () -> {
                    Sinks.Many<ServerSentEvent<Object>> sink =
                            Sinks.many().unicast().onBackpressureBuffer();
                    var heartbeat =
                            Schedulers.parallel()
                                    .schedulePeriodically(
                                            () ->
                                                    sink.tryEmitNext(
                                                            ServerSentEvent.<Object>builder()
                                                                    .comment("heartbeat")
                                                                    .build()),
                                            HEARTBEAT_SECONDS,
                                            HEARTBEAT_SECONDS,
                                            TimeUnit.SECONDS);
                    var subscription =
                            data.subscribe(
                                    event -> sink.tryEmitNext(event),
                                    error -> sink.tryEmitError(error),
                                    () -> sink.tryEmitComplete());

                    return sink.asFlux()
                            .doFinally(
                                    signal -> {
                                        heartbeat.dispose();
                                        subscription.dispose();
                                    });
                });
    }

    // 3.1. 回放游标：解析客户端最后确认的事件位置，拒绝非法序号。
    static long cursor(String id, String header, Long query) {
        if (header != null && !header.isBlank()) {
            int colon = header.lastIndexOf(':');

            if (colon < 0 || !header.substring(0, colon).equals(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件游标与任务不一致");
            }

            try {
                long n = Long.parseLong(header.substring(colon + 1));

                if (n < 0) {
                    throw new NumberFormatException();
                }

                return n;
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件游标无效");
            }
        }

        if (query != null && query < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件游标无效");
        }

        return query == null ? 0 : query;
    }

    private static <T> Mono<T> work(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    /** 执行事件的一批回放结果，同时标明执行是否已进入终态。 */
    private record Batch(List<ExecutionStore.Event> events, boolean done) {}
}
