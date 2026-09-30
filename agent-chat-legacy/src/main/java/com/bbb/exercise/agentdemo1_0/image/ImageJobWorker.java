package com.bbb.exercise.agentdemo1_0.image;

import com.bbb.exercise.agentdemo1_0.oss.OssStorageService;
import com.bbb.exercise.agentdemo1_0.auth.UserApiKeyService;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo1_0.model.ModelCapability;
import com.bbb.exercise.agentdemo1_0.model.ModelProfileService;
import com.bbb.exercise.agentdemo1_0.model.ModelProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** 单实例 MVP Worker：从 MySQL 队列领取任务，生成完成后把结果保存回私有 OSS。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.image-jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "app.image-jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ImageJobWorker {

    private final ImageJobService jobs;
    private final OssStorageService storage;
    private final UserApiKeyService userKeys;
    private final ModelProfileService modelProfiles;
    private final ImageGenerationProviderRegistry providers;

    @Value("${app.image-jobs.max-attempts:2}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${app.image-jobs.poll-interval:3000ms}", initialDelay = 5000)
    public void poll() {
        // OSS 未配置时不领取任务，避免生成结果无法保存。
        if (!storage.isConfigured()) return;
        ImageJobService.JobRecord job = jobs.claimNext();
        if (job == null) return;
        Instant receivedAt = Instant.now();
        long startedNanos = System.nanoTime();
        String result = "FAILED";
        log.info("[image-job] worker_received jobId={} queuedAt={} receivedAt={}",
                job.id(), job.createdAt(), receivedAt);
        try {
            ChatIdentity identity = new ChatIdentity(job.tenantId(), job.userId(), true);
            ModelProvider provider = ModelProvider.parse(job.provider());
            ModelProfileService.SelectedModel selected = modelProfiles.resolve(identity, ModelCapability.IMAGE);
            String apiKey = selected == null ? userKeys.get(identity).qwenApiKey() : selected.apiKey();
            if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("图片模型 API Key 已失效，请重新配置");
            String sourceUrl = storage.signedGetUrl(job.sourceObjectKey());
            ImageGenerationProvider.Result generated = providers.resolve(provider)
                    .generate(new ImageGenerationProvider.Request(sourceUrl, job.prompt(), apiKey, job.model()))                     
                    .block(Duration.ofMinutes(4));
            if (generated == null || generated.imageUrl() == null || generated.imageUrl().isBlank()) {
                throw new IllegalStateException("图片模型没有返回结果");
            }
            storage.copyRemoteImageToObject(generated.imageUrl(), job.outputObjectKey());
            jobs.succeed(job.id(), job.outputObjectKey(), generated.rationale(), generated.providerRequestId());
            result = "SUCCEEDED";
        } catch (Exception error) {
            log.warn("[image-job] worker_failed jobId={} errorType={} message={}",
                    job.id(), error.getClass().getSimpleName(), safeMessage(error));
            jobs.fail(job.id(), safeMessage(error), Math.max(1, maxAttempts));
        } finally {
            Instant completedAt = Instant.now();
            log.info("[image-job] worker_completed jobId={} result={} receivedAt={} completedAt={} durationMs={}",
                    job.id(), result, receivedAt, completedAt,
                    Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000));
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank()
                ? (error == null ? "unknown" : error.getClass().getSimpleName())
                : message.replaceAll("[\\r\\n]+", " ");
    }
}
