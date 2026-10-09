package com.bbb.exercise.agentdemo.mediaservice;

import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import com.bbb.exercise.agentdemo.common.security.SignedPrincipal;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.mediaservice.image.ImageAssetService;
import com.bbb.exercise.agentdemo.mediaservice.image.ImageJobService;
import com.bbb.exercise.agentdemo.api.dto.ChatAttachmentRequest;
import com.bbb.exercise.agentdemo.runtime.storage.OssStorageService;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/internal/media")
public class InternalMediaController {
    private final ImageAssetService assets;
    private final OssStorageService storage;
    private final ImageJobService jobs;
    private final PrincipalKeyRing keys;

    @Autowired
    public InternalMediaController(ImageAssetService assets, OssStorageService storage,
                                   ImageJobService jobs,
                                   @Value("${app.security.internal-principal-secrets:}") String secrets,
                                   @Value("${app.security.internal-principal-active-key-id:current}") String active) {
        this(assets, storage, jobs, new PrincipalKeyRing(secrets, active));
    }

    public InternalMediaController(ImageAssetService assets, OssStorageService storage,
                                   PrincipalKeyRing keys) {
        this(assets, storage, null, keys);
    }

    public InternalMediaController(ImageAssetService assets, OssStorageService storage,
                                   ImageJobService jobs, PrincipalKeyRing keys) {
        this.assets = assets;
        this.storage = storage;
        this.jobs = jobs;
        this.keys = keys;
    }

    @GetMapping("/assets/{assetId}")
    public Mono<RemoteAsset> asset(@PathVariable String assetId,
                                   @RequestHeader("X-Internal-Principal") String token) {
        return Mono.fromCallable(() -> {
            var principal = verify(token, "GET /internal/media/assets/" + assetId);
            var asset = assets.requireReady(new ChatIdentity(principal.tenant(), principal.subject(), true), assetId);
            return new RemoteAsset(asset.id(), asset.objectKey(), asset.mimeType(), asset.fileSize(),
                    storage.signedGetUrl(asset.objectKey()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/image-jobs/{jobId}/publish-source")
    public Mono<ImageJobService.PublishSource> publishSource(@PathVariable String jobId,
                                                             @RequestHeader("X-Internal-Principal") String token) {
        return Mono.fromCallable(() -> {
            var principal = verify(token, "GET /internal/media/image-jobs/" + jobId + "/publish-source");
            if (jobs == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "媒体任务服务未配置");
            return jobs.requirePublishableOutput(new ChatIdentity(principal.tenant(), principal.subject(), true), jobId);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/image-jobs")
    public Mono<ImageJobService.JobView> createJob(@RequestBody CreateJobRequest request,
                                                   @RequestHeader("X-Internal-Principal") String token) {
        return Mono.fromCallable(() -> {
            var principal = verify(token, "POST /internal/media/image-jobs");
            if (jobs == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "媒体任务服务未配置");
            var attachment = new ChatAttachmentRequest();
            attachment.setAssetId(request.assetId());
            return jobs.create(new ChatIdentity(principal.tenant(), principal.subject(), true), request.conversationId(),
                    request.prompt(), java.util.List.of(attachment));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private SignedPrincipal.Scoped verify(String token, String operation) {
        try {
            return keys.verify(token, "agent-media-service", operation);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部身份签名无效");
        }
    }

    public record RemoteAsset(String assetId, String objectKey, String mimeType,
                              long fileSize, String downloadUrl) {}

    public record CreateJobRequest(String conversationId, String prompt, String assetId) {}
}
