package com.bbb.exercise.agentdemo.mediaservice;

import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import com.bbb.exercise.agentdemo.common.security.SignedPrincipal;
import com.bbb.exercise.agentdemo1_0.image.ImageAssetService;
import com.bbb.exercise.agentdemo1_0.image.ImageJobService;
import com.bbb.exercise.agentdemo1_0.oss.OssStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InternalMediaBoundaryTest {
    @Test
    void isolatesBlockingAssetLookupFromReactiveEventLoop() {
        byte[] key = new byte[32];
        var assets = mock(ImageAssetService.class);
        var storage = mock(OssStorageService.class);
        var lookupThread = new AtomicReference<Thread>();
        when(assets.requireReady(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("asset-1")))
                .thenAnswer(invocation -> {
                    lookupThread.set(Thread.currentThread());
                    return new ImageAssetService.AssetRecord("asset-1", "source/object.png", "image/png", 12,
                            "READY", LocalDateTime.now().plusMinutes(5));
                });
        when(storage.signedGetUrl("source/object.png")).thenReturn("https://oss.example/source/object.png");
        var client = WebTestClient.bindToController(new InternalMediaController(assets, storage,
                new PrincipalKeyRing("current=" + Base64.getEncoder().encodeToString(key), "current"))).build();
        String token = SignedPrincipal.issueScoped("current", "agent-chat-service", "user-1", "tenant-1",
                "agent-media-service", "GET /internal/media/assets/asset-1", Instant.now(), key);

        client.get().uri("/internal/media/assets/asset-1")
                .header("X-Internal-Principal", token)
                .exchange().expectStatus().isOk();

        assertFalse(lookupThread.get() instanceof reactor.core.scheduler.NonBlocking,
                "blocking media lookup must not run on a Reactor non-blocking thread");
    }

    @Test
    void returnsOwnedReadyAssetOnlyForSignedPrincipal() {
        byte[] key = new byte[32];
        var assets = mock(ImageAssetService.class);
        var storage = mock(OssStorageService.class);
        when(assets.requireReady(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("asset-1")))
                .thenReturn(new ImageAssetService.AssetRecord("asset-1", "source/object.png", "image/png", 12,
                        "READY", LocalDateTime.now().plusMinutes(5)));
        when(storage.signedGetUrl("source/object.png")).thenReturn("https://oss.example/source/object.png");
        var client = WebTestClient.bindToController(new InternalMediaController(assets, storage,
                new PrincipalKeyRing("current=" + Base64.getEncoder().encodeToString(key), "current"))).build();
        String token = SignedPrincipal.issueScoped("current", "agent-chat-service", "user-1", "tenant-1",
                "agent-media-service", "GET /internal/media/assets/asset-1", Instant.now(), key);

        client.get().uri("/internal/media/assets/asset-1")
                .header("X-Internal-Principal", token)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.assetId").isEqualTo("asset-1")
                .jsonPath("$.downloadUrl").isEqualTo("https://oss.example/source/object.png");
    }

    @Test
    void returnsPublishableOutputThroughSignedMediaBoundary() {
        byte[] key = new byte[32];
        var assets = mock(ImageAssetService.class);
        var storage = mock(OssStorageService.class);
        var jobs = mock(ImageJobService.class);
        when(jobs.requirePublishableOutput(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("job-1")))
                .thenReturn(new ImageJobService.PublishSource("job-1", "output/job-1.png", "make it vivid"));
        var client = WebTestClient.bindToController(new InternalMediaController(assets, storage, jobs,
                new PrincipalKeyRing("current=" + Base64.getEncoder().encodeToString(key), "current"))).build();
        String token = SignedPrincipal.issueScoped("current", "agent-content-service", "user-1", "tenant-1",
                "agent-media-service", "GET /internal/media/image-jobs/job-1/publish-source", Instant.now(), key);

        client.get().uri("/internal/media/image-jobs/job-1/publish-source")
                .header("X-Internal-Principal", token)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.jobId").isEqualTo("job-1")
                .jsonPath("$.outputObjectKey").isEqualTo("output/job-1.png");
    }

    @Test
    void createsImageJobThroughSignedMediaBoundary() {
        byte[] key = new byte[32];
        var assets = mock(ImageAssetService.class);
        var storage = mock(OssStorageService.class);
        var jobs = mock(ImageJobService.class);
        when(jobs.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("conversation-1"),
                org.mockito.ArgumentMatchers.eq("make it vivid"), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new ImageJobService.JobView("job-1", "QUEUED", "make it vivid", "gathered",
                        LocalDateTime.now(), null, null, null, null, null));
        var client = WebTestClient.bindToController(new InternalMediaController(assets, storage, jobs,
                new PrincipalKeyRing("current=" + Base64.getEncoder().encodeToString(key), "current"))).build();
        String token = SignedPrincipal.issueScoped("current", "agent-chat-service", "user-1", "tenant-1",
                "agent-media-service", "POST /internal/media/image-jobs", Instant.now(), key);

        client.post().uri("/internal/media/image-jobs").header("X-Internal-Principal", token)
                .bodyValue(java.util.Map.of("conversationId", "conversation-1", "prompt", "make it vivid", "assetId", "asset-1"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.jobId").isEqualTo("job-1");
    }
}
