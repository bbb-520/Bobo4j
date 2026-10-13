package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.runtime.storage.OssStorageService;
import com.bbb.exercise.agentdemo.runtime.client.*;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.*;
import com.bbb.exercise.agentdemo.api.billing.BillingContracts.Settlement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Leased worker; durable provider receipts allow copying/settling after crashes without generating twice. */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "app.image-jobs",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class ImageJobWorker {
    private final ImageJobService jobs;
    private final OssStorageService storage;
    private final AuthCredentialClient userKeys;
    private final AuthModelClient modelProfiles;
    private final ImageGenerationProviderRegistry providers;
    private final BillingClient billing;

    @Value("${app.image-jobs.worker-id:media-worker}")
    private String workerId;

    @Value("${app.image-jobs.lease-duration:5m}")
    private Duration leaseDuration;

    // 1. 图片执行流程：领取租约后，优先恢复供应商回执，再复制结果并结算。
    @Scheduled(fixedDelayString = "${app.image-jobs.poll-interval:3000ms}", initialDelay = 5000)
    public void poll() {
        // 1.1. 存储准入：OSS 未就绪时不领取任务，避免占用无法交付结果的作业。
        if (!storage.isConfigured()) {
            return;
        }

        // 1.2. 领取与续租：为本次领取生成独立 owner，并在生成期间维持租约。
        String owner = workerId + ":" + UUID.randomUUID();
        ImageJobService.JobRecord job = jobs.claimNext(owner, leaseDuration);

        if (job == null) {
            return;
        }

        ChatIdentity identity = new ChatIdentity(job.tenantId(), job.userId(), true);
        var heartbeat =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon(true).name("image-lease-").factory());
        long interval = Math.max(100, Math.min(30000, leaseDuration.toMillis() / 3));

        heartbeat.scheduleWithFixedDelay(
                () -> {
                    try {
                        jobs.renewLease(job.id(), owner, leaseDuration);
                    } catch (Exception e) {
                        log.warn("[image-job] lease heartbeat failed jobId={}", job.id());
                    }
                },
                interval,
                interval,
                TimeUnit.MILLISECONDS);
        boolean dispatched = false;
        ImageJobService.Receipt receipt = null;

        try {
            // 1.3. 恢复检查：有持久回执时跳过供应商生成，并先检查消费是否已取消或退款。
            receipt = jobs.receipt(identity, job.id());

            if (receipt != null) {
                var state = billing.status(identity, job.id()).block(Duration.ofSeconds(6));

                if (state != null
                        && ("REFUNDED".equals(state.status())
                                || "ABANDONED".equals(state.status())
                                || "CANCELLED".equals(state.status()))) {
                    jobs.fail(job.id(), owner, "调用已结束，占用已解除；请重新提交图片创作", 1);
                    return;
                }
            }

            // 1.4. 首次生成：按任务供应商快照校验当前模型，再取得计费准入。
            if (receipt == null) {
                ModelProvider provider = ModelProvider.parse(job.provider());
                AuthModelClient.SelectedModel selected =
                        modelProfiles.resolve(identity, ModelCapability.IMAGE);

                validateSelectedProvider(provider, selected);
                String key =
                        selected == null ? userKeys.get(identity).qwenApiKey() : selected.apiKey();

                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("平台图片凭据未配置");
                }

                String source = job.sourceObjectKey() == null || job.sourceObjectKey().isBlank()
                        ? null : storage.signedGetUrl(job.sourceObjectKey());

                try {
                    billing.reserve(identity, job.id(), "IMAGE", job.model())
                            .block(Duration.ofSeconds(6));
                } catch (Exception admission) {
                    var state = billing.status(identity, job.id()).block(Duration.ofSeconds(6));

                    if (state != null
                            && ("DISPATCHED".equals(state.status())
                                    || "SUCCEEDED".equals(state.status()))) {
                        jobs.defer(job.id(), owner);
                        return;
                    }

                    throw admission;
                }

                // 1.4.1. 先登记派发再调用供应商，使异常路径能识别已产生的不明消费。
                billing.dispatch(identity, job.id()).block(Duration.ofSeconds(6));
                dispatched = true;
                var generated =
                        providers
                                .resolve(provider)
                                .generate(
                                        new ImageGenerationProvider.Request(
                                                source, job.prompt(), key, job.model()))
                                .block(Duration.ofMinutes(4));

                if (generated == null
                        || generated.imageUrl() == null
                        || generated.imageUrl().isBlank()) {
                    throw new IllegalStateException("模型没有返回图片");
                }

                // 1.4.2. 先保存供应商结果；复制图片或结算失败时可沿用该回执恢复。
                jobs.recordResult(identity, job.id(), generated);
                receipt = jobs.receipt(identity, job.id());

                if (receipt == null) {
                    throw new IllegalStateException("供应商结果未持久化");
                }
            }

            // 1.5. 交付与结算：复制到固定 OSS 输出位置，再按回执用量结算并标记成功。
            storage.copyRemoteImageToObject(receipt.imageUrl(), job.outputObjectKey());
            billing.settle(
                            identity,
                            job.id(),
                            new Settlement(
                                    receipt.inputTokens(),
                                    receipt.outputTokens(),
                                    receipt.providerRequestId()))
                    .block(Duration.ofSeconds(6));

            if (!jobs.succeed(
                    job.id(),
                    owner,
                    job.outputObjectKey(),
                    receipt.rationale(),
                    receipt.providerRequestId())) {
                log.info("[image-job] result retained for lease successor jobId={}", job.id());
            }
        } catch (Exception error) {
            log.warn(
                    "[image-job] worker failed jobId={} errorType={}",
                    job.id(),
                    error.getClass().getSimpleName());

            if (receipt != null) {
                if (error instanceof org.springframework.web.server.ResponseStatusException response
                        && response.getStatusCode().is4xxClientError()) {
                    jobs.fail(job.id(), owner, "结果结算状态冲突，请联系管理员核对", 1);
                } else {
                    jobs.defer(job.id(), owner);
                }

                return;
            }

            try {
                // 1.6. 无明确回执的异常：登记消费待核对，避免把未知派发结果当成免费失败。
                billing.unknown(identity, job.id()).block(Duration.ofSeconds(6));
            } catch (Exception ignored) {
            }

            jobs.fail(
                    job.id(), owner, dispatched ? "供应商结果不明确，消费待对账" : "余额不足、账户忙碌或模型配置失效，请查看消费记录", 1);
        } finally {
            heartbeat.shutdownNow();
        }
    }

    // 2. 模型快照约束：已创建任务不得静默切换到不同供应商。
    static void validateSelectedProvider(
            ModelProvider jobProvider, AuthModelClient.SelectedModel selected) {
        if (selected != null && selected.provider() != jobProvider) {
            throw new IllegalStateException("图片任务的模型提供商已变更，请重新提交任务");
        }

        if (selected == null && jobProvider != ModelProvider.QWEN) {
            throw new IllegalStateException("图片任务的模型配置已失效，请重新配置后提交");
        }
    }
}
