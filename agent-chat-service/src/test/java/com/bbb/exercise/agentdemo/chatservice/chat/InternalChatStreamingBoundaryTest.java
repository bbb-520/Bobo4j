package com.bbb.exercise.agentdemo.chatservice.chat;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 验证内部聊天流的签名作用域、版本事件以及订阅取消对供应商工作的中断。 */
class InternalChatStreamingBoundaryTest {
    String secrets = "current=" + Base64.getEncoder().encodeToString(new byte[32]);
    PrincipalKeyRing keys = new PrincipalKeyRing(secrets, "current");

    String signature(String audience, String operation) {
        return keys.sign("agent-orchestrator-service", "alice", "tenant", audience, operation);
    }

    @Test
    void signedStreamReturnsVersionedFramesAndRejectsWrongAudienceOrOperation() {
        var service = new FakeCompletion();
        var controller = new InternalChatController(service, null, secrets, "current");
        var http = WebTestClient.bindToController(controller).build();
        var request = new CompletionRequest("call", "CHAT", "rules", "q", null);
        var result =
                http.post()
                        .uri("/internal/chat/stream")
                        .header(
                                "X-Internal-Principal",
                                signature("agent-chat-service", "POST /internal/chat/stream"))
                        .bodyValue(request)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .contentTypeCompatibleWith("text/event-stream")
                        .returnResult(GenerationEvent.class)
                        .getResponseBody()
                        .collectList()
                        .block(java.time.Duration.ofSeconds(5));

        assertThat(result)
                .extracting(GenerationEvent::type)
                .containsExactly("answer_delta", "answer_committed");
        assertThat(result.getFirst().generationVersion()).isEqualTo(1);
        assertThat(service.identity).isEqualTo(new ChatIdentity("tenant", "alice", true));
        http.post()
                .uri("/internal/chat/stream")
                .header(
                        "X-Internal-Principal",
                        signature("agent-auth-service", "POST /internal/chat/stream"))
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isUnauthorized();
        http.post()
                .uri("/internal/chat/stream")
                .header(
                        "X-Internal-Principal",
                        signature("agent-chat-service", "POST /internal/chat/complete"))
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void subscriberCancellationInterruptsTheBlockingProviderWorker() throws Exception {
        var service = new FakeCompletion();

        service.wait = true;
        var http =
                WebTestClient.bindToController(
                                new InternalChatController(service, null, secrets, "current"))
                        .build();

        http.post()
                .uri("/internal/chat/stream")
                .header(
                        "X-Internal-Principal",
                        signature("agent-chat-service", "POST /internal/chat/stream"))
                .bodyValue(new CompletionRequest("call", "CHAT", "rules", "q", null))
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(GenerationEvent.class)
                .getResponseBody()
                .take(1)
                .blockLast(java.time.Duration.ofSeconds(5));
        assertThat(service.cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(service.committed).isFalse();
    }

    @Test
    void providerFailureAfterACommittedStatusEndsWithAnErrorFrameWithoutRewritingHttp() {
        var failure = new ModelCallClient.ModelCallException("PROVIDER_ERROR", false, 404);
        var frames = failingStreamAfterStatus(failure);

        assertThat(frames).extracting(GenerationEvent::type).containsExactly("model_status", "error");
        assertThat(frames.getLast().generationVersion()).isEqualTo(2);
        assertThat(frames.getLast().payload())
                .containsEntry("code", "PROVIDER_ERROR")
                .containsEntry("message", failure.getMessage());
    }

    @Test
    void anUnexpectedFailureAfterACommittedStatusDoesNotExposeUpstreamDetails() {
        var frames = failingStreamAfterStatus(
                new IllegalStateException("https://upstream.example/?api_key=private-secret"));

        assertThat(frames).extracting(GenerationEvent::type).containsExactly("model_status", "error");
        assertThat(frames.getLast().payload())
                .containsEntry("code", "MODEL_STREAM_FAILED")
                .containsEntry("message", "当前回答服务暂时不可用，你的问题和处理进度已保留。可以稍后重试。");
        assertThat(frames.getLast().payload().toString()).doesNotContain("upstream.example", "private-secret");
    }

    @Test
    void aModelRejectionBeforeAnyEventRetainsItsHttpErrorBoundary() {
        var advice = new TrackingAdvice();
        var service = new FakeCompletion() {
            @Override
            public Completion completeStreaming(ChatIdentity identity, CompletionRequest request,
                    java.util.function.Consumer<GenerationEvent> events) {
                throw new ModelCallClient.ModelCallException("MODEL_TOKEN_BUDGET", false, 429);
            }
        };
        var http = WebTestClient.bindToController(
                        new InternalChatController(service, null, secrets, "current"))
                .controllerAdvice(advice).build();

        http.post().uri("/internal/chat/stream")
                .header("X-Internal-Principal", signature("agent-chat-service", "POST /internal/chat/stream"))
                .bodyValue(new CompletionRequest("call", "CHAT", "rules", "q", null))
                .exchange().expectStatus().isEqualTo(429);
        assertThat(advice.modelErrors.get()).isEqualTo(1);
    }

    private List<GenerationEvent> failingStreamAfterStatus(RuntimeException failure) {
        var firstFrameRead = new CountDownLatch(1);
        var advice = new TrackingAdvice();
        var service = new FakeCompletion() {
            @Override
            public Completion completeStreaming(ChatIdentity identity, CompletionRequest request,
                    java.util.function.Consumer<GenerationEvent> events) {
                events.accept(new GenerationEvent("model_status", 2,
                        Map.of("provider", "QWEN", "state", "RUNNING")));
                try {
                    if (!firstFrameRead.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("client did not consume the committed status frame");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                }
                throw failure;
            }
        };
        var http = WebTestClient.bindToController(
                        new InternalChatController(service, null, secrets, "current"))
                .controllerAdvice(advice).build();
        var frames = http.post().uri("/internal/chat/stream")
                .header("X-Internal-Principal", signature("agent-chat-service", "POST /internal/chat/stream"))
                .bodyValue(new CompletionRequest("call", "CHAT", "rules", "q", null))
                .exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/event-stream")
                .returnResult(GenerationEvent.class).getResponseBody()
                .doOnNext(event -> firstFrameRead.countDown())
                .collectList().block(java.time.Duration.ofSeconds(5));
        assertThat(advice.modelErrors.get()).isZero();
        assertThat(service.committed).isFalse();
        return frames;
    }

    static class TrackingAdvice extends ChatExceptionHandler {
        final java.util.concurrent.atomic.AtomicInteger modelErrors = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        @ExceptionHandler(ModelCallClient.ModelCallException.class)
        public ResponseEntity<Map<String, Object>> handleModel(ModelCallClient.ModelCallException error) {
            modelErrors.incrementAndGet();
            return super.handleModel(error);
        }
    }

    /** 内部聊天流测试替身，模拟提交和阻塞生成以观察身份传递及取消。 */
    static class FakeCompletion extends InternalCompletionService {
        boolean wait, committed;
        ChatIdentity identity;
        CountDownLatch cancelled = new CountDownLatch(1);

        FakeCompletion() {
            super(null, null, null, null);
        }

        @Override
        public Completion completeStreaming(
                ChatIdentity i,
                CompletionRequest r,
                java.util.function.Consumer<GenerationEvent> events) {
            identity = i;
            events.accept(new GenerationEvent("answer_delta", 1, Map.of("text", "answer")));

            if (wait) {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    cancelled.countDown();
                    throw new CancellationException();
                }
            }

            var result = new Completion("answer", 12, 3, "model", false);

            committed = true;
            events.accept(
                    com.bbb.exercise.agentdemo.runtime.client.ModelCallClient.committedEvent(
                            result));

            return result;
        }
    }
}
