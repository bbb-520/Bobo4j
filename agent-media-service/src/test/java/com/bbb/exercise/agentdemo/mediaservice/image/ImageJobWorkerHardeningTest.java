package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import com.bbb.exercise.agentdemo.api.model.ModelProvider;
import com.bbb.exercise.agentdemo.runtime.client.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import java.lang.annotation.Annotation;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ImageJobWorkerHardeningTest {
    @Test void scheduledPollReturnsWhileTwoProvidersRunAndNeverBuildsExecutorBacklog() throws Exception {
        exerciseBoundedWorker(2, false);
    }

    @Test void configuredParallelismControlsCapacityAndMaintainsIndependentHeartbeats() throws Exception {
        exerciseBoundedWorker(3, true);
    }

    private void exerciseBoundedWorker(int capacity, boolean configure) throws Exception {
        var db = new ImageJobDatabase();
        for (int i = 0; i < capacity + 1; i++) db.insert("user-" + i, "QUEUED", db.now().plusDays(1), null, false);
        var provider = mock(ImageGenerationProvider.class);
        var registry = mock(ImageGenerationProviderRegistry.class);
        when(registry.resolve(ModelProvider.QWEN)).thenReturn(provider);
        when(db.storage.isConfigured()).thenReturn(true);
        when(db.billing.reserve(any(), anyString(), eq("IMAGE"), anyString()))
                .thenAnswer(call -> Mono.just(new Reservation(call.getArgument(1), false, 0, "RESERVED")));
        Set<String> dispatched = ConcurrentHashMap.newKeySet();
        when(db.billing.dispatch(any(), anyString())).thenAnswer(call -> {
            if (!dispatched.add(call.getArgument(1))) return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT));
            return Mono.empty();
        });
        when(db.billing.settle(any(), anyString(), any())).thenReturn(Mono.just(new Wallet(100, 0, 0, false, true, false, null)));
        CountDownLatch activeProviders = new CountDownLatch(capacity), release = new CountDownLatch(1);
        CountDownLatch renewed = new CountDownLatch(capacity), completed = new CountDownLatch(capacity);
        Set<String> renewedJobs = ConcurrentHashMap.newKeySet();
        var jobs = spy(db.jobs);
        doAnswer(call -> {
            boolean result = (boolean) call.callRealMethod();
            if (result && renewedJobs.add(call.getArgument(0))) renewed.countDown();
            return result;
        }).when(jobs).renewLease(anyString(), anyString(), any());
        doAnswer(call -> { Object result = call.callRealMethod(); completed.countDown(); return result; })
                .when(jobs).succeed(anyString(), anyString(), anyString(), any(), any());
        AtomicInteger providerCalls = new AtomicInteger();
        when(provider.generate(any())).thenAnswer(call -> Mono.fromCallable(() -> {
            providerCalls.incrementAndGet();
            activeProviders.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test did not release provider");
            return new ImageGenerationProvider.Result("https://image", "provider-id", "ok", 1L, 2L);
        }));
        var worker = new ImageJobWorker(jobs, db.storage, mock(AuthCredentialClient.class), db.models, registry, db.billing);
        ReflectionTestUtils.setField(worker, "workerId", "parallel-test");
        ReflectionTestUtils.setField(worker, "leaseDuration", Duration.ofSeconds(2));
        if (configure) ReflectionTestUtils.setField(worker, "parallelism", capacity);
        lifecycle(worker, PostConstruct.class);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> tick = executor.submit(() -> scheduledTick(worker));
            try {
                assertThat(activeProviders.await(2, TimeUnit.SECONDS)).as("all slots execute while the provider is blocked").isTrue();
                tick.get(1, TimeUnit.SECONDS);
                assertThat(renewed.await(3, TimeUnit.SECONDS)).as("every concurrent job renews its own lease").isTrue();
                for (int i = 0; i < 20; i++) scheduledTick(worker);
                assertThat(providerCalls.get()).isEqualTo(capacity);
                assertThat(dispatched).hasSize(capacity);
                assertThat(db.jdbc.queryForObject("SELECT COUNT(DISTINCT lease_owner) FROM image_job WHERE status='PROCESSING'", Integer.class)).isEqualTo(capacity);
            } finally {
                release.countDown();
                tick.get(5, TimeUnit.SECONDS);
                assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM image_job WHERE status='SUCCEEDED'", Integer.class)).isEqualTo(capacity);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM image_job WHERE status='QUEUED'", Integer.class)).isEqualTo(1);
        } finally {
            lifecycle(worker, PreDestroy.class);
        }
    }

    @Test void expenseCircuitRejectsWithoutDispatchAndShowsCuratedRetryMessage() {
        var fixture = new ImageJobWorkerRecoveryTest();
        var worker = fixture.worker();
        when(fixture.models.resolve(any(), any())).thenReturn(
                new AuthModelClient.SelectedModel(ModelProvider.QWEN, com.bbb.exercise.agentdemo.api.model.ModelCapability.IMAGE, "qwen-image", "key"));
        when(fixture.billing.reserve(any(), eq("job"), eq("IMAGE"), anyString()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "internal details")));
        worker.poll();
        verify(fixture.jobs).failWithoutReceipt(eq("job"), anyString(), contains("图片服务暂时繁忙"), eq(1));
        verify(fixture.billing, never()).dispatch(any(), anyString());
        verify(fixture.billing, never()).unknown(any(), anyString());
        verifyNoInteractions(fixture.providers);
    }

    @Test void receiptArrivingAfterLastRereadSurvivesFailureAndCompletesWithoutAnotherProviderCall() throws Exception {
        var db = new ImageJobDatabase();
        String id = db.insert("alice", "QUEUED", db.now().plusDays(1), null, false);
        when(db.storage.isConfigured()).thenReturn(true);
        when(db.billing.reserve(any(), eq(id), eq("IMAGE"), anyString()))
                .thenReturn(Mono.just(new Reservation(id, false, 0, "RESERVED")));
        when(db.billing.dispatch(any(), eq(id))).thenReturn(Mono.empty());
        var registry = mock(ImageGenerationProviderRegistry.class);
        var provider = mock(ImageGenerationProvider.class);
        when(registry.resolve(ModelProvider.QWEN)).thenReturn(provider);
        when(provider.generate(any())).thenReturn(Mono.error(new IllegalStateException("response lost")));
        when(db.billing.unknown(any(), eq(id))).thenReturn(Mono.fromRunnable(() ->
                db.jobs.recordResult(db.identity("alice"), id,
                        new ImageGenerationProvider.Result("https://late", "provider-id", "ok", 10L, 20L)))
                .then(Mono.error(new IllegalStateException("billing transport lost"))));
        var worker = new ImageJobWorker(db.jobs, db.storage, mock(AuthCredentialClient.class), db.models, registry, db.billing);
        ReflectionTestUtils.setField(worker, "workerId", "late-test");
        ReflectionTestUtils.setField(worker, "leaseDuration", Duration.ofMinutes(5));
        worker.poll();
        assertThat(db.status(id)).isEqualTo("QUEUED");
        assertThat(db.jobs.receipt(db.identity("alice"), id).inputTokens()).isEqualTo(10L);

        db.jdbc.update("UPDATE image_job SET next_retry_at=NULL WHERE id=?", id);
        when(db.billing.status(any(), eq(id))).thenReturn(Mono.just(new BillingClient.CallStatus("DISPATCHED")));
        when(db.billing.settle(any(), eq(id), any())).thenReturn(Mono.just(new Wallet(100, 0, 0, false, true, false, null)));
        worker.poll();
        assertThat(db.status(id)).isEqualTo("SUCCEEDED");
        verify(provider, times(1)).generate(any());
        verify(db.billing, times(1)).dispatch(any(), eq(id));
        verify(db.storage).copyRemoteImageToObject("https://late", "output");
    }

    private static void scheduledTick(ImageJobWorker worker) {
        try {
            for (var method : ImageJobWorker.class.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Scheduled.class)) { method.invoke(worker); return; }
            }
            throw new AssertionError("missing scheduled worker entry point");
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void lifecycle(ImageJobWorker worker, Class<? extends Annotation> annotation) throws Exception {
        for (var method : ImageJobWorker.class.getDeclaredMethods()) if (method.isAnnotationPresent(annotation)) {
            method.setAccessible(true); method.invoke(worker);
        }
    }
}
