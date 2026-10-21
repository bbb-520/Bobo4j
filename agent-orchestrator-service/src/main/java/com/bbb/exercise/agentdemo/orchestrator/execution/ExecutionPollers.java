package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同一任务的 SSE 订阅共享轮询（F10）。
 *
 * <p>改造前每个订阅者自带一条 {@code Flux.interval} 轮询：一个任务开 3 个标签页就是 3 路独立
 * 轮询，SQL 随连接数线性放大（实测 3 订阅者正好 3×）。而这些人看的是**同一行**执行记录。</p>
 *
 * <p>所以把"读状态"这一步收到任务级：一个 {@code (tenant, user, execution)} 只跑一个轮询任务，
 * 结果广播给该任务的全部订阅者。每个订阅者仍各自持有游标、各自决定是否去拉事件——
 * 事件不能共享，因为不同客户端的续接位置本来就不同。</p>
 *
 * <p>空闲退避也放在这里：连续读到相同的 {@code (status,lastSeq)} 就把间隔从 500ms 逐级放大到
 * 2s，一旦有新事件立刻回到 500ms。终态之后订阅者会自行断开，引用归零即停止轮询。</p>
 *
 * <p>键里带租户与用户，因此共享边界不会跨 owner：不同用户的订阅永远落在不同的轮询任务上。</p>
 */
final class ExecutionPollers {

    /** 首次与"有新事件"时的间隔，与改造前的 tick 一致，避免改变客户端观感。 */
    static final long ACTIVE_MS = 500L;

    /** 空闲退避上限。取 2s：既压低空闲查询，又不至于让"任务刚结束"的感知延迟明显变差。 */
    static final long IDLE_MAX_MS = 2_000L;

    /** 空闲放大倍数。500 → 750 → 1125 → 1687 → 2000。 */
    private static final double IDLE_FACTOR = 1.5;

    private final ConcurrentHashMap<Key, Shared> active = new ConcurrentHashMap<>();

    /**
     * 加入共享轮询。
     *
     * @param identity 订阅者身份，只用于构造共享键，不参与读库（读库由 {@code reader} 自己带条件）
     * @param executionId 任务 ID
     * @param reader 实际读快照的调用（阻塞 JDBC，运行在 boundedElastic 上）
     */
    Flux<ExecutionStore.Snapshot> join(
            ChatIdentity identity, String executionId, Callable<ExecutionStore.Snapshot> reader) {
        var key = new Key(identity.tenantId(), identity.userId(), executionId);
        var shared =
                active.compute(
                        key,
                        (ignored, current) -> {
                            var target = current == null ? new Shared(key, reader) : current;

                            target.references.incrementAndGet();

                            return target;
                        });

        shared.ensurePolling();

        return shared.sink.asFlux().doFinally(signal -> release(shared));
    }

    /** 当前活跃的共享轮询任务数，供回归测试断言"3 个订阅者只有 1 个轮询器"。 */
    int activePollers() {
        return active.size();
    }

    private void release(Shared shared) {
        if (shared.references.decrementAndGet() > 0) {
            return;
        }

        // 引用归零：先停轮询，再尝试摘除。用 computeIfPresent 保证不会摘掉刚被别人 increment 的实例。
        shared.stop();

        active.computeIfPresent(
                shared.key, (ignored, current) -> current.references.get() > 0 ? current : null);
    }

    private record Key(String tenant, String user, String execution) {}

    /** 一个任务一个实例，持有单一轮询任务与广播通道。 */
    private static final class Shared {

        private final Key key;

        private final Callable<ExecutionStore.Snapshot> reader;

        private final Sinks.Many<ExecutionStore.Snapshot> sink =
                Sinks.many().multicast().directBestEffort();

        private final AtomicInteger references = new AtomicInteger();

        private final AtomicLong idleMs = new AtomicLong(ACTIVE_MS);

        private final AtomicLong previousSeq = new AtomicLong(Long.MIN_VALUE);

        private volatile String previousStatus;

        private volatile Disposable polling;

        private volatile boolean stopped;

        private Shared(Key key, Callable<ExecutionStore.Snapshot> reader) {
            this.key = key;
            this.reader = reader;
        }

        private void ensurePolling() {
            if (polling != null || stopped) {
                return;
            }

            synchronized (this) {
                if (polling == null && !stopped) {
                    schedule(0L);
                }
            }
        }

        private void schedule(long delayMs) {
            // 阻塞 JDBC 必须落在 boundedElastic：parallel 只有 CPU 核数个槽位，压满会拖死整个 reactor。
            polling =
                    Schedulers.boundedElastic()
                            .schedule(
                                    () -> {
                                        tick();

                                        if (!stopped && references.get() > 0) {
                                            schedule(idleMs.get());
                                        }
                                    },
                                    delayMs,
                                    TimeUnit.MILLISECONDS);
        }

        private void tick() {
            if (stopped) {
                return;
            }

            ExecutionStore.Snapshot snapshot;

            try {
                snapshot = reader.call();
            } catch (Exception unavailable) {
                // 读失败不终止订阅：下一次 tick 继续尝试。异常本身由订阅者的游标读取路径暴露，
                // 这里静默重试是刻意的——轮询失败不等于任务失败。
                idleMs.set(Math.min((long) (idleMs.get() * IDLE_FACTOR), IDLE_MAX_MS));

                return;
            }

            boolean changed =
                    snapshot.lastSeq() != previousSeq.get()
                            || !snapshot.status().equals(previousStatus);

            previousSeq.set(snapshot.lastSeq());
            previousStatus = snapshot.status();
            idleMs.set(changed ? ACTIVE_MS : Math.min((long) (idleMs.get() * IDLE_FACTOR), IDLE_MAX_MS));
            sink.tryEmitNext(snapshot);
        }

        private void stop() {
            stopped = true;

            var running = polling;

            if (running != null) {
                running.dispose();
            }
        }
    }
}
