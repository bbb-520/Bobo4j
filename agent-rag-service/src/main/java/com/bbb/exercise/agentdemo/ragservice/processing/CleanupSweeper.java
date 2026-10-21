package com.bbb.exercise.agentdemo.ragservice.processing;

import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import com.bbb.exercise.agentdemo.ragservice.retrieval.VectorIndex;
import com.bbb.exercise.agentdemo.ragservice.storage.DocumentStorage;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 删除后的延迟二次扫尾（F12）。
 *
 * <p>同步清理路径（{@code IngestionWorker} 的 CLEANUP 阶段）只能清掉"当下已经存在"的东西。
 * 真正难处理的是迟到写入：删除请求到达时索引阶段可能正在把向量推给 Milvus，那次 upsert
 * 会在清理之后才落地，于是刚清干净的文档又出现孤儿向量。</p>
 *
 * <p>本类负责把这条缝补上：读取 {@code rag_cleanup_ledger} 里到期的条目，再清一次向量与
 * 整个文档工作目录，然后用索引计数确认是否真的为 0，确认了才销账；一直清不干净就按上限
 * 转 {@code GAVE_UP} 留痕，交给人工而不是无限重试。</p>
 *
 * <p>为什么必须落库而不是放在内存队列里：进程重启、多实例部署、清理本身失败三种情况都会让
 * 内存队列丢失，而残留数据不会因此消失。</p>
 */
@Component
public class CleanupSweeper {

    private final RagRepository repository;

    private final DocumentStorage storage;

    private final VectorIndex index;

    private final MeterRegistry metrics;

    private final int batch;

    private final int maxAttempts;

    private final Duration lease;

    private final Duration retryDelay;

    public CleanupSweeper(
            RagRepository repository,
            DocumentStorage storage,
            VectorIndex index,
            MeterRegistry metrics,
            @Value("${rag.cleanup.batch:20}") int batch,
            @Value("${rag.cleanup.max-attempts:5}") int maxAttempts,
            @Value("${rag.cleanup.lease-seconds:300}") long leaseSeconds,
            @Value("${rag.cleanup.retry-delay-seconds:60}") long retryDelaySeconds) {
        this.repository = repository;
        this.storage = storage;
        this.index = index;
        this.metrics = metrics;
        this.batch = batch;
        this.maxAttempts = maxAttempts;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.retryDelay = Duration.ofSeconds(retryDelaySeconds);
    }

    /** 默认 30s 扫一次。与 {@code IngestionWorker.CLEANUP_RECHECK_DELAY=60s} 叠加，实际首次扫尾在 60s 之后。 */
    @Scheduled(fixedDelayString = "${rag.cleanup.sweep-ms:30000}")
    public void sweep() {
        for (RagRepository.CleanupTask task : repository.dueCleanups(batch, lease)) {
            sweepOne(task);
        }
    }

    /** 单条扫尾。返回是否已确认清干净；调度方不依赖返回值，测试用它断言重试分支。 */
    public boolean sweepOne(RagRepository.CleanupTask task) {
        try {
            // 第二次删除：迟到写入此刻已经落地，这一刀才真正有效。
            index.remove(task.identity(), task.documentId());
            storage.deleteAllWork(task.documentId());

            int remaining = index.count(task.identity(), task.documentId());

            if (remaining == 0) {
                repository.finishCleanup(task.documentId());
                metrics.counter("rag.cleanup.reconciled", "verified", "true").increment();

                return true;
            }

            if (remaining < 0) {
                // 索引实现不提供计数：以"第二次 remove 成功"为准，但显式标记未做条数确认，
                // 避免监控里把"无法确认"读成"已确认干净"。
                repository.finishCleanup(task.documentId());
                metrics.counter("rag.cleanup.reconciled", "verified", "false").increment();

                return true;
            }

            repository.retryCleanup(task, retryDelay, maxAttempts, "VECTORS_REMAIN:" + remaining);
            metrics.counter("rag.cleanup.retry").increment();

            return false;
        } catch (RuntimeException error) {
            repository.retryCleanup(task, retryDelay, maxAttempts, String.valueOf(error.getMessage()));
            metrics.counter("rag.cleanup.retry").increment();

            return false;
        }
    }
}
