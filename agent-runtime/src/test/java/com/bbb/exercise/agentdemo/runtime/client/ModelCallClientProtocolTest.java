package com.bbb.exercise.agentdemo.runtime.client;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.*;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.VisionImage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** 验证模型协议、用量真实性、幂等回执、主备回退、流式提交及预算约束。 */
class ModelCallClientProtocolTest {
    HttpServer provider;
    AuthModelClient models;
    FakeBilling billing;
    ModelCallClient client;
    ChatIdentity identity = new ChatIdentity("tenant", "alice", true);
    String base;
    AtomicInteger calls = new AtomicInteger();
    String response;
    int status = 200;
    String body;
    boolean primaryFails, stream;
    com.sun.net.httpserver.HttpHandler custom;

    @BeforeEach
    void setup() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext(
                "/",
                exchange -> {
                    int attempt = calls.incrementAndGet();

                    body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);

                    if (custom != null) {
                        custom.handle(exchange);
                        return;
                    }

                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

                    exchange.getResponseHeaders()
                            .set("Content-Type", stream ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(
                            primaryFails && attempt == 1 ? 503 : status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        provider.start();
        base = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
        models =
                new AuthModelClient(null) {
                    @Override
                    public SelectedModel resolve(ChatIdentity i, ModelCapability c) {
                        return new SelectedModel(
                                ModelProvider.QWEN,
                                c,
                                c == ModelCapability.FALLBACK ? "backup" : "model",
                                "key",
                                base,
                                "operator-key");
                    }
                };
        billing = new FakeBilling();
        client = new ModelCallClient(models, billing, WebClient.builder());
    }

    @AfterEach
    void close() {
        provider.stop(0);
    }
    @Test void disconnectAfterProviderAcceptsBodyMustNotTransparentlyReplayPaidPost() {
        custom=exchange->exchange.close();
        assertThatThrownBy(()->client.rerank(identity,"disconnect-once","question",List.of("evidence"))).isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(billing.unknown).isEqualTo("PRIMARY");assertThat(billing.rejected).isNull();
    }
    @Test void openCircuitAlsoStopsEmbeddingBeforeProviderDispatchAndReleasesUnsentGroup() {
        var circuit=new ModelCircuit();var model=models.resolve(identity,ModelCapability.EMBEDDING);
        for(int n=0;n<5;n++)circuit.failure(model);
        client=new ModelCallClient(models,billing,WebClient.builder(),circuit,15,20,60,30);
        assertThatThrownBy(()->client.embed(identity,"embedding-circuit",List.of("text"),64)).isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isZero();assertThat(billing.cancelled).isTrue();assertThat(billing.unknown).isNull();
    }

    @Test void billingReceiptConnectionFailureMustNotBecomeAZeroUsageProviderRejection() {
        billing=new FakeBilling(){@Override public Mono<Void> receipt(ChatIdentity i,String id,AttemptReceipt r){return Mono.error(new java.net.ConnectException("billing unavailable"));}};
        client=new ModelCallClient(models,billing,WebClient.builder());
        response="{\"results\":[{\"index\":0,\"relevance_score\":0.8}],\"usage\":{\"total_tokens\":5}}";
        assertThatThrownBy(()->client.rerank(identity,"receipt-failed","q",List.of("a"))).isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(billing.rejected).isNull();assertThat(billing.unknown).isEqualTo("PRIMARY");
    }

    @Test void providerRejectionIsNotUnknownAndUnconfiguredModelsReleaseAdmission() {
        response="{}";status=401;
        assertThatThrownBy(()->client.embed(identity,"reject",List.of("a"),64)).isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(billing.unknown).isNull();
        assertThat(billing.rejected).isEqualTo("PRIMARY");
        models=new AuthModelClient(null){@Override public SelectedModel resolve(ChatIdentity i,ModelCapability c){return null;}};
        client=new ModelCallClient(models,billing,WebClient.builder());
        assertThatThrownBy(()->client.embed(identity,"config",List.of("a"),64)).isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(billing.cancelled).isTrue();
    }

    @Test
    void completedReceiptReplaysWithoutProviderDispatch() {
        billing.state =
                new GroupState(
                        "call",
                        "RECEIVED",
                        "{\"text\":\"cached\",\"inputTokens\":12,\"outputTokens\":3,\"model\":\"model\",\"fallback\":false}",
                        "PRIMARY");
        var replayed = client.complete(identity, "call", "CHAT", "rules", "question", false, 0L);

        assertThat(replayed.text()).isEqualTo("cached");
        assertThat(replayed.reservedTokens()).isEqualTo(15);
        assertThat(replayed.unknownTokens()).isZero();
        assertThat(calls.get()).isZero();
    }

    @Test
    void validatesEmbeddingCardinalityAndDimensionsRatherThanAcceptingBrokenIndexVectors() {
        response =
                "{\"data\":[{\"index\":0,\"embedding\":[1.0]}],\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}";
        assertThatThrownBy(() -> client.embed(identity, "call", List.of("a", "b"), 1024))
                .isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void successfulEmbeddingReceiptPinsTheActuallySelectedConfigurationAndDimensions() {
        response =
                "{\"data\":[{\"index\":0,\"embedding\":"
                        + Collections.nCopies(64, 0.1f)
                        + "}],\"usage\":{\"prompt_tokens\":2}}";
        assertThat(client.embed(identity, "embed", "a".lines().toList(), 64).modelFingerprint())
                .isEqualTo(ModelFingerprint.embedding("QWEN", "model", base, "operator-key", 64));
    }

    @Test
    void rejectsRerankIndicesOutsideTheCandidateSet() {
        response =
                "{\"results\":[{\"index\":9,\"relevance_score\":0.8}],\"usage\":{\"total_tokens\":5}}";
        assertThatThrownBy(() -> client.rerank(identity, "call", "q", List.of("a")))
                .isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void rerankEmitsMeasuredTokensAndADurationMetricWithoutProviderSecretsInLabels() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();

        factory.addBean("metrics", registry);
        client =
                new ModelCallClient(
                        models,
                        billing,
                        WebClient.builder(),
                        new ModelCircuit(),
                        15,
                        20,
                        60,
                        30,
                        factory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        response =
                "{\"results\":[{\"index\":0,\"relevance_score\":0.8}],\"usage\":{\"total_tokens\":5}}";
        client.rerank(identity, "rank", "q", List.of("a"));
        assertThat(
                        registry.get("ai.model.tokens")
                                .tag("capability", "RERANK")
                                .tag("direction", "input")
                                .counter()
                                .count())
                .isEqualTo(5);
        assertThat(
                        registry.get("ai.model.duration")
                                .tag("capability", "RERANK")
                                .tag("outcome", "success")
                                .timer()
                                .count())
                .isEqualTo(1);
        assertThat(registry.getMeters().toString()).doesNotContain(base, "operator-key");
    }

    @Test
    void missingUsageRemainsUnknownAndNeverInventsZeroTokens() {
        response = "{\"id\":\"r\",\"choices\":[{\"message\":{\"content\":\"answer\"}}]}";
        assertThatThrownBy(() -> client.complete(identity, "call", "CHAT", "rules", "q", false))
                .isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void measuredCompletionUsesSeparateSystemAndUserFieldsAndQwenOnlyExtension() {
        response =
                "{\"id\":\"r\",\"choices\":[{\"message\":{\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";
        var result =
                client.complete(
                        identity,
                        "call",
                        "DOCUMENT_QA",
                        "trusted rules",
                        "untrusted evidence",
                        false);

        assertThat(result.text()).isEqualTo("answer");
        assertThat(result.inputTokens()).isEqualTo(12);
        assertThat(result.outputTokens()).isEqualTo(3);
        assertThat(body).contains("trusted rules", "untrusted evidence", "enable_thinking");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void recoveredDispatchedAttemptDoesNotBlindlyRepeatOrFailOver() {
        billing.state = new GroupState("call", "UNKNOWN", null, null);
        assertThatThrownBy(() -> client.complete(identity, "call", "CHAT", "rules", "q", true))
                .isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void serverFailureFailsOverAtMostOnceWithTheSameStableCallAndFreshPrompt() {
        primaryFails = true;
        response =
                "{\"id\":\"r\",\"choices\":[{\"message\":{\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";
        var answer =
                client.complete(identity, "call", "DOCUMENT_QA", "rules", "[S1] evidence", true);

        assertThat(answer.fallback()).isTrue();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(body).contains("[S1] evidence", "备用", "重新生成");
        assertThat(billing.saved.role()).isEqualTo("FALLBACK");
    }

    @Test
    void modelStatusNamesTheActualAttemptAndNeverExposesCredentials() {
        models =
                new AuthModelClient(null) {
                    @Override
                    public SelectedModel resolve(ChatIdentity i, ModelCapability c) {
                        return new SelectedModel(
                                c == ModelCapability.FALLBACK
                                        ? ModelProvider.QWEN
                                        : ModelProvider.GPT,
                                c,
                                c == ModelCapability.FALLBACK ? "qwen-plus" : "gpt-4.1",
                                "private-key",
                                base,
                                "credential-hash");
                    }
                };
        client = new ModelCallClient(models, billing, WebClient.builder());
        primaryFails = true;
        response =
                "{\"id\":\"r\",\"choices\":[{\"message\":{\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";
        var events =
                new ArrayList<
                        com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>();

        client.completeStreaming(identity, "call", "CHAT", "rules", "q", true, events::add);
        var statuses = events.stream().filter(e -> e.type().equals("model_status")).toList();

        assertThat(statuses).hasSize(4);
        assertThat(statuses.get(0).payload())
                .containsEntry("provider", "GPT")
                .containsEntry("model", "gpt-4.1")
                .containsEntry("state", "RUNNING");
        assertThat(statuses.get(1).payload()).containsEntry("state", "FAILED");
        assertThat(statuses.get(2).payload())
                .containsEntry("provider", "QWEN")
                .containsEntry("model", "qwen-plus")
                .containsEntry("fallback", true);
        assertThat(statuses.get(3).payload()).containsEntry("state", "SUCCEEDED");
        assertThat(events.toString()).doesNotContain("private-key", "credential-hash", base);
    }

    @Test
    void visionFallbackRetainsTheImageAndUsesTheVisualBackup() {
        models =
                new AuthModelClient(null) {
                    @Override
                    public SelectedModel resolve(ChatIdentity i, ModelCapability c) {
                        return new SelectedModel(
                                c == ModelCapability.VISION_FALLBACK
                                        ? ModelProvider.QWEN
                                        : ModelProvider.GPT,
                                c,
                                c == ModelCapability.VISION_FALLBACK ? "qwen-vl-plus" : "gpt-4.1",
                                "key",
                                base,
                                "operator-key");
                    }
                };
        client = new ModelCallClient(models, billing, WebClient.builder());
        primaryFails = true;
        response =
                "{\"id\":\"r\",\"choices\":[{\"message\":{\"content\":\"画面是猫\"}}],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":3}}";
        var events =
                new ArrayList<
                        com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>();
        var result =
                client.completeVision(
                        identity,
                        "vision",
                        "rules",
                        "描述图片",
                        "https://assets.example/cat.png",
                        events::add);

        assertThat(result.fallback()).isTrue();
        assertThat(result.model()).isEqualTo("qwen-vl-plus");
        assertThat(body).contains("image_url", "https://assets.example/cat.png", "描述图片");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(billing.lastAttempt.plannedTokenUpper()).isGreaterThan(16384);
        assertThat(events)
                .filteredOn(e -> e.type().equals("model_status"))
                .extracting(e -> e.payload().get("capability"))
                .containsOnly("VISION");
    }

    @Test
    void invalidRequestAndSameCredentialAuthenticationFailureNeverSwitchModels() {
        response = "{}";
        status = 400;
        assertThatThrownBy(() -> client.complete(identity, "call", "CHAT", "rules", "q", true))
                .isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isEqualTo(1);
        calls.set(0);
        status = 401;
        assertThatThrownBy(() -> client.complete(identity, "call", "CHAT", "rules", "q", true))
                .isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void doubleServerFailureStopsAfterTwoAttemptsAndEvaluationNeverChangesJudge() {
        response = "{}";
        status = 503;
        assertThatThrownBy(() -> client.complete(identity, "call", "CHAT", "rules", "q", true))
                .isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isEqualTo(2);
        calls.set(0);
        assertThatThrownBy(
                        () -> client.complete(identity, "eval", "EVALUATION", "rules", "q", true))
                .isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void streamingUsageFramesNeverBecomeAnswerText() {
        stream = true;
        response =
                "data: {\"id\":\"r\",\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\ndata: {\"id\":\"r\",\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n";
        var answer = client.complete(identity, "call", "CHAT", "rules", "q", false);

        assertThat(answer.text()).isEqualTo("answer");
        assertThat(answer.outputTokens()).isEqualTo(3);
    }

    @Test
    void cancellationBeforeAdmissionDoesNotDispatchOrSwitch() {
        Thread.currentThread().interrupt();

        try {
            assertThatThrownBy(() -> client.complete(identity, "call", "CHAT", "rules", "q", true))
                    .isInstanceOf(ModelCallClient.ModelCallException.class);
            assertThat(calls.get()).isZero();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void insufficientMoneyBeforeDispatchReleasesUnsentHoldAndNeverFallsBack() {
        billing.attemptError =
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.PAYMENT_REQUIRED);
        assertThatThrownBy(() -> client.complete(identity, "call", "CHAT", "rules", "q", true))
                .isInstanceOfSatisfying(
                        ModelCallClient.ModelCallException.class,
                        e -> assertThat(e.status()).isEqualTo(402));
        assertThat(calls.get()).isZero();
        assertThat(billing.cancelled).isTrue();
        assertThat(billing.unknown).isNull();
    }

    @Test
    void openCircuitWithoutAuthorizedFallbackReleasesUndispatchedWalletHold() {
        var circuit = new ModelCircuit();
        var selected = models.resolve(identity, ModelCapability.CHAT);

        for (int n = 0; n < 5; n++) {
            circuit.failure(selected);
        }

        client = new ModelCallClient(models, billing, WebClient.builder(), circuit, 15, 20, 60, 30);
        assertThatThrownBy(() -> client.complete(identity, "call", "CHAT", "rules", "q", false))
                .isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isZero();
        assertThat(billing.cancelled).isTrue();
    }

    @Test
    void streamingCommitsOnlyAfterDurableMeasuredReceiptAndSettlement() {
        stream = true;
        response =
                "data: {\"id\":\"r\",\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\ndata: {\"id\":\"r\",\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n";
        var events =
                new ArrayList<
                        com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>();

        client.completeStreaming(
                identity,
                "call",
                "CHAT",
                "rules",
                "q",
                true,
                event -> {
                    if (event.type().equals("answer_committed")) {
                        assertThat(billing.saved).isNotNull();
                        assertThat(billing.finished).isTrue();
                    }

                    events.add(event);
                });
        assertThat(events)
                .filteredOn(e -> !e.type().equals("model_status"))
                .extracting(
                        com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent::type)
                .containsExactly("answer_delta", "answer_committed");
        assertThat(events.getLast().payload())
                .containsEntry("text", "answer")
                .containsEntry("inputTokens", 12L)
                .containsEntry("outputTokens", 3L);
    }

    @Test
    void missingUsageCannotEmitACommittedEvent() {
        response = "{\"choices\":[{\"message\":{\"content\":\"partial\"}}]}";
        var events =
                new ArrayList<
                        com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>();

        assertThatThrownBy(
                        () ->
                                client.completeStreaming(
                                        identity, "call", "CHAT", "rules", "q", true, events::add))
                .isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(events)
                .extracting(
                        com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent::type)
                .doesNotContain("answer_committed");
        assertThat(billing.unknown).isEqualTo("PRIMARY");
    }

    @Test
    void partialPrimaryIdleTimeoutReplacesAnswerWithBackupAndFencesLatePrimaryOutput() {
        client = new ModelCallClient(models, billing, WebClient.builder(), null, 1, 1, 4, 3);
        custom =
                exchange -> {
                    if (calls.get() == 1) {
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody()
                                .write(
                                        "data: {\"choices\":[{\"delta\":{\"content\":\"partial-primary\"}}]}\n\n"
                                                .getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();

                        try {
                            Thread.sleep(1500);
                            exchange.getResponseBody()
                                    .write(
                                            "data: {\"choices\":[{\"delta\":{\"content\":\"late-primary\"}}]}\n\n"
                                                    .getBytes(StandardCharsets.UTF_8));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (java.io.IOException ignored) {
                        } finally {
                            exchange.close();
                        }
                    } else {
                        byte[] bytes =
                                "{\"id\":\"b\",\"choices\":[{\"message\":{\"content\":\"backup-answer\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}"
                                        .getBytes(StandardCharsets.UTF_8);

                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    }
                };
        var events =
                new java.util.concurrent.CopyOnWriteArrayList<
                        com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>();
        var result =
                client.completeStreaming(identity, "call", "CHAT", "rules", "q", true, events::add);

        assertThat(result.text()).isEqualTo("backup-answer");
        assertThat(events)
                .filteredOn(e -> !e.type().equals("model_status"))
                .extracting(
                        com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent::type)
                .containsExactly(
                        "answer_delta", "fallback_started", "answer_replace", "answer_committed");
        assertThat(
                        events.stream()
                                .filter(e -> e.type().equals("answer_replace"))
                                .findFirst()
                                .orElseThrow()
                                .generationVersion())
                .isEqualTo(2);
        assertThat(events.toString()).doesNotContain("late-primary");
    }

    @Test
    void cancellingActiveStreamCancelsProviderSubscriptionAndRetainsUnknownWithoutCommit()
            throws Exception {
        var output = new java.util.concurrent.CountDownLatch(1);
        var events =
                new java.util.concurrent.CopyOnWriteArrayList<
                        com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>();

        custom =
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody()
                            .write(
                                    "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                                            .getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();

                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                };

        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var future =
                    executor.submit(
                            () ->
                                    client.completeStreaming(
                                            identity,
                                            "call",
                                            "CHAT",
                                            "rules",
                                            "q",
                                            true,
                                            event -> {
                                                events.add(event);

                                                if (event.type().equals("answer_delta")) {
                                                    output.countDown();
                                                }
                                            }));

            assertThat(output.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            future.cancel(true);
            assertThat(billing.unknownSignal.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
        }

        assertThat(events)
                .extracting(
                        com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent::type)
                .doesNotContain("answer_committed", "fallback_started");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void incomingTokenBudgetRejectsBeforeProviderAndReceiptReplayIgnoresReducedBudget() {
        assertThatThrownBy(
                        () -> client.complete(identity, "call", "CHAT", "rules", "q", true, 100L))
                .isInstanceOfSatisfying(
                        ModelCallClient.ModelCallException.class,
                        e -> {
                            assertThat(e.code()).isEqualTo("MODEL_TOKEN_BUDGET");
                            assertThat(e.status()).isEqualTo(429);
                        });
        assertThat(calls.get()).isZero();
        assertThat(billing.cancelled).isTrue();
        billing.state =
                new GroupState(
                        "call",
                        "SUCCEEDED",
                        "{\"text\":\"cached\",\"inputTokens\":12,\"outputTokens\":3,\"model\":\"model\",\"fallback\":false,\"reservedTokens\":15,\"unknownTokens\":0}",
                        "PRIMARY");
        assertThat(client.complete(identity, "call", "CHAT", "rules", "q", true, 0L).text())
                .isEqualTo("cached");
        assertThat(calls.get()).isZero();
    }

    @Test
    void budgetEnoughForPrimaryOnlyDisablesBackupAndUnknownPrimaryBoundIsHonestInReceipt() {
        long primary =
                com.bbb.exercise.agentdemo.api.model.TokenBudgetEstimator.completion(
                        "CHAT", "rules", "q", false);

        response = "{}";
        status = 503;
        assertThatThrownBy(
                        () ->
                                client.complete(
                                        identity, "call", "CHAT", "rules", "q", true, primary))
                .isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isEqualTo(1);
        calls.set(0);
        status = 200;
        primaryFails = true;
        response =
                "{\"choices\":[{\"message\":{\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";
        var result = client.complete(identity, "call", "CHAT", "rules", "q", true, 64000L);

        assertThat(result.unknownTokens()).isEqualTo(primary);
        assertThat(result.reservedTokens()).isEqualTo(primary + 15);
        assertThat(result.inputTokens()).isEqualTo(12);
        assertThat(billing.lastAttempt.plannedTokenUpper()).isGreaterThan(2048);
    }

    @Test
    void multipleImagesBecomeSeparateContentBlocksAndStreamAnOrdinaryAnswer() {
        stream = true;
        response =
                "data: {\"id\":\"vision\",\"choices\":[{\"delta\":{\"content\":\"两张图片的区别\"}}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":321,\"completion_tokens\":8}}\n\n"
                        + "data: [DONE]\n\n";
        var events = new ArrayList<com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>();

        var answer = multiVision(
                List.of("a", "b"),
                List.of("https://assets.example/a?signature=first", "https://assets.example/b?signature=first"),
                64000L,
                events::add);

        var request = tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);
        var content = request.path("messages").path(1).path("content");
        assertThat(content.size()).isEqualTo(3);
        assertThat(content.path(0).path("text").asString()).isEqualTo("比较这两张图片");
        assertThat(content.path(1).path("image_url").path("url").asString())
                .isEqualTo("https://assets.example/a?signature=first");
        assertThat(content.path(2).path("image_url").path("url").asString())
                .isEqualTo("https://assets.example/b?signature=first");
        assertThat(answer.text()).isEqualTo("两张图片的区别");
        assertThat(answer.inputTokens()).isEqualTo(321);
        assertThat(billing.lastGroup.capability()).isEqualTo("VISION");
        assertThat(billing.lastAttempt.plannedTokenUpper()).isBetween(35000L, 40000L);
        assertThat(events)
                .filteredOn(e -> !e.type().equals("model_status"))
                .extracting(com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent::type)
                .containsExactly("answer_delta", "answer_committed");
    }

    @Test
    void legacyTextAndSingleUrlCallsKeepTheirExistingBillingIdentity() {
        response = "{\"choices\":[{\"message\":{\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";
        client.complete(identity, "text", "CHAT", "rules", "q", true);
        assertThat(billing.lastGroup.parameterHash())
                .isEqualTo("2fe9a726eb257badcb9a82d3418fc3383ce2d6876e7b04cc51d67688ba680180");
        client.completeVision(identity, "single", "rules", "q", "https://assets.example/a.png", event -> {});
        assertThat(billing.lastGroup.parameterHash())
                .isEqualTo("78f9fe03af3fcb6d95c613b8c4fc28136f2b1ac6574b48597b930fabc0469bf7");
    }

    @Test
    void twoImageUpperBoundRejectsAnInsufficientBudgetBeforeDispatch() {
        assertThatThrownBy(() -> multiVision(
                        List.of("a", "b"),
                        List.of("https://assets.example/a", "https://assets.example/b"),
                        25000L,
                        event -> {}))
                .isInstanceOfSatisfying(ModelCallClient.ModelCallException.class,
                        e -> assertThat(e.code()).isEqualTo("MODEL_TOKEN_BUDGET"));
        assertThat(calls.get()).isZero();
        assertThat(billing.cancelled).isTrue();
    }

    @Test
    void threeImagesCannotUseAnOverlargeCallerBudgetToDispatchASecondAttempt() {
        response = "{}";
        status = 503;

        assertThatThrownBy(() -> multiVision(
                        List.of("a", "b", "c"),
                        List.of("https://assets.example/a", "https://assets.example/b", "https://assets.example/c"),
                        200000L,
                        event -> {}))
                .isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(billing.lastAttempt.plannedTokenUpper()).isBetween(51000L, 64000L);
    }

    @Test
    void refreshedSignedUrlsReplayTheSameAssetReceiptAndChangedAssetsConflict() {
        response = "{\"choices\":[{\"message\":{\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":3}}";
        multiVision(List.of("a", "b"),
                List.of("https://assets.example/a?signature=old", "https://assets.example/b?signature=old"),
                64000L, event -> {});
        billing.state = new GroupState("vision", "SUCCEEDED", billing.saved.resultJson(), "PRIMARY");

        assertThat(multiVision(List.of("a", "b"),
                        List.of("https://assets.example/a?signature=new", "https://assets.example/b?signature=new"),
                        0L, event -> {}).text())
                .isEqualTo("answer");
        assertThat(calls.get()).isEqualTo(1);
        assertThatThrownBy(() -> multiVision(List.of("a", "changed"),
                        List.of("https://assets.example/a?signature=new", "https://assets.example/b?signature=new"),
                        64000L, event -> {}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recoveryFinishesTheOwnedPaidReceiptWithoutAdmissionOrProviderDispatch() {
        billing.state = new GroupState("vision", "RECEIVED",
                "{\"text\":\"paid answer\",\"inputTokens\":32,\"outputTokens\":7,\"model\":\"vision-model\",\"fallback\":false,\"reservedTokens\":140,\"unknownTokens\":101}",
                "PRIMARY", 1, 32, 7, 101);

        var result = recoverVisionReceipt().orElseThrow();

        assertThat(result.text()).isEqualTo("paid answer");
        assertThat(result.inputTokens()).isEqualTo(32);
        assertThat(result.outputTokens()).isEqualTo(7);
        assertThat(result.reservedTokens()).isEqualTo(140);
        assertThat(result.unknownTokens()).isEqualTo(101);
        assertThat(billing.lookupIdentity).isEqualTo(identity);
        assertThat(billing.lookupId).isEqualTo("vision");
        assertThat(billing.finished).isTrue();
        assertThat(billing.lastGroup).isNull();
        assertThat(billing.lastAttempt).isNull();
        assertThat(calls.get()).isZero();
    }

    @Test
    void recoveryNeverRedispatchesWhenAnExistingGroupHasAnUnknownOutcome() {
        for (String status : List.of("UNKNOWN", "RUNNING", "DISPATCHED", "RECEIVED", "SUCCEEDED")) {
            billing.state = new GroupState("vision", status, null, null);
            assertThatThrownBy(this::recoverVisionReceipt)
                    .isInstanceOfSatisfying(ModelCallClient.ModelCallException.class, error -> {
                        assertThat(error.code()).isEqualTo("MODEL_OUTCOME_UNKNOWN");
                        assertThat(error.status()).isEqualTo(409);
                    });
        }

        assertThat(billing.lastGroup).isNull();
        assertThat(billing.lastAttempt).isNull();
        assertThat(billing.finished).isFalse();
        assertThat(calls.get()).isZero();
    }

    @Test
    void recoveryAllowsFreshDispatchOnlyForDefinitelyUndispatchedGroups() {
        for (String status : List.of("RESERVED", "QUEUED", "CANCELLED", "FAILED_RESOLVED")) {
            billing.state = new GroupState("vision", status, null, null);
            assertThat(recoverVisionReceipt()).isEmpty();
        }
        billing.lookupError = new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND);
        assertThatThrownBy(this::recoverVisionReceipt)
                .isInstanceOfSatisfying(ModelCallClient.ModelCallException.class, error -> {
                    assertThat(error.code()).isEqualTo("MODEL_OUTCOME_UNKNOWN");
                    assertThat(error.status()).isEqualTo(409);
                });
        billing.lookupError = new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        assertThatThrownBy(this::recoverVisionReceipt)
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(billing.lastGroup).isNull();
        assertThat(billing.lastAttempt).isNull();
        assertThat(calls.get()).isZero();
    }

    @Test
    void recoveryReleasesOnlyAFencedUndispatchedReservationAndRetainsUnknownUsage() {
        for (String status : List.of("QUEUED", "RESERVED")) {
            billing.cancelled = false;
            billing.state = new GroupState("vision", status, null, null, 7, 0, 0, 0);
            assertThat(recoverVisionReceipt()).isEmpty();
            assertThat(billing.cancelled).isTrue();
            assertThat(billing.cancelledGeneration).isEqualTo(7);
        }
        billing.cancelled = false;
        billing.state = new GroupState("vision", "RESERVED", null, null, 8, 0, 0, 101);

        assertThatThrownBy(this::recoverVisionReceipt)
                .isInstanceOfSatisfying(ModelCallClient.ModelCallException.class, error -> {
                    assertThat(error.code()).isEqualTo("MODEL_OUTCOME_UNKNOWN");
                    assertThat(error.status()).isEqualTo(409);
                });
        assertThat(billing.cancelled).isFalse();
        assertThat(billing.lastGroup).isNull();
        assertThat(calls.get()).isZero();
    }

    @Test
    void cancelledQueuedPollCannotReopenItsGenerationOrDispatchAfterRecovery() {
        var queued = new CancelledQueuedBilling();
        billing = queued;
        client = new ModelCallClient(models, billing, WebClient.builder());
        response = "{\"choices\":[{\"message\":{\"content\":\"must not dispatch\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";

        assertThatThrownBy(() -> client.complete(identity, "queued", "CHAT", "rules", "q", false))
                .isInstanceOfSatisfying(ModelCallClient.ModelCallException.class, error -> {
                    assertThat(error.code()).isEqualTo("MODEL_OUTCOME_UNKNOWN");
                    assertThat(error.status()).isEqualTo(409);
                });
        assertThat(queued.polls).isEqualTo(2);
        assertThat(queued.generation).isEqualTo(1);
        assertThat(queued.walletHeld).isFalse();
        assertThat(queued.lastAttempt).isNull();
        assertThat(calls.get()).isZero();
    }

    @Test
    void admissionRejectsAnUnexpectedGenerationEvenWhenTheServerIgnoresItsFence() {
        billing = new FakeBilling() {
            int polls;

            @Override
            public Mono<GroupState> reserveGroup(ChatIdentity identity, GroupReserve request) {
                state = ++polls == 1
                        ? new GroupState("queued", "QUEUED", null, null, 1)
                        : new GroupState("queued", "RESERVED", null, null, 2);
                return Mono.just(state);
            }
        };
        client = new ModelCallClient(models, billing, WebClient.builder());
        response = "{\"choices\":[{\"message\":{\"content\":\"must not dispatch\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";

        assertThatThrownBy(() -> client.complete(identity, "queued", "CHAT", "rules", "q", false))
                .isInstanceOfSatisfying(ModelCallClient.ModelCallException.class,
                        error -> assertThat(error.code()).isEqualTo("MODEL_OUTCOME_UNKNOWN"));
        assertThat(billing.lastAttempt).isNull();
        assertThat(calls.get()).isZero();
    }

    private Optional<com.bbb.exercise.agentdemo.api.model.ModelContracts.Completion> recoverVisionReceipt() {
        return client.recoverCompletion(identity, "vision");
    }

    private com.bbb.exercise.agentdemo.api.model.ModelContracts.Completion multiVision(
            List<String> ids,
            List<String> urls,
            Long budget,
            java.util.function.Consumer<com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent> events) {
        var images = new ArrayList<VisionImage>();
        for (int n = 0; n < ids.size(); n++) {
            images.add(new VisionImage(ids.get(n), urls.get(n)));
        }
        return client.completeVision(identity, "vision", "rules", "比较这两张图片", images, budget, events);
    }

    static class CancelledQueuedBilling extends FakeBilling {
        int polls, generation = 1;
        boolean walletHeld;

        @Override
        public Mono<GroupState> reserveGroup(ChatIdentity identity, GroupReserve request) {
            if (++polls == 1) {
                return Mono.just(new GroupState("queued", "QUEUED", null, null, generation));
            }

            // Recovery cancelled generation 1 while its original caller was between polls.
            var expected = tools.jackson.databind.json.JsonMapper.builder().build()
                    .valueToTree(request).path("expectedGeneration");
            if (expected.isIntegralNumber()) {
                return Mono.error(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT));
            }

            generation++;
            walletHeld = true;
            state = new GroupState("queued", "RESERVED", null, null, generation);
            return Mono.just(state);
        }
    }

    /** 模型协议测试的计费替身，记录预留、派发、未知状态、回执和结算操作。 */
    static class FakeBilling extends BillingClient {
        GroupState state = new GroupState("call", "RESERVED", null, null);
        GroupReserve lastGroup;
        Map<String, String> groupHashes = new HashMap<>();
        String unknown, rejected;
        AttemptReceipt saved;
        boolean finished, cancelled;
        AttemptReserve lastAttempt;
        RuntimeException attemptError;
        RuntimeException lookupError;
        ChatIdentity lookupIdentity;
        String lookupId;
        int cancelledGeneration;
        java.util.concurrent.CountDownLatch unknownSignal =
                new java.util.concurrent.CountDownLatch(1);

        FakeBilling() {
            super(null);
        }

        @Override
        public Mono<GroupState> group(ChatIdentity i, String id) {
            lookupIdentity = i;
            lookupId = id;
            return lookupError == null ? Mono.just(state) : Mono.error(lookupError);
        }

        @Override
        public Mono<GroupState> reserveGroup(ChatIdentity i, GroupReserve r) {
            lastGroup = r;
            String previous = groupHashes.putIfAbsent(r.callId(), r.parameterHash());
            if (previous != null && !previous.equals(r.parameterHash())) {
                return Mono.error(new IllegalStateException("group parameter conflict"));
            }
            return Mono.just(state);
        }

        @Override
        public Mono<AttemptState> attempt(ChatIdentity i, String id, AttemptReserve r) {
            lastAttempt = r;
            return attemptError == null
                    ? Mono.just(new AttemptState(r.role(), "RESERVED", state.generation()))
                    : Mono.error(attemptError);
        }

        @Override
        public Mono<Void> dispatchAttempt(ChatIdentity i, String id, String role) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> unknownAttempt(ChatIdentity i, String id, String role) {
            unknown = role;
            return Mono.empty();
        }

        @Override
        public Mono<Void> dispatchAttempt(ChatIdentity i, String id, String role, int generation) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> unknownAttempt(ChatIdentity i, String id, String role, int generation) {
            unknown = role;
            unknownSignal.countDown();

            return Mono.empty();
        }

        @Override public Mono<GroupState> rejectAttempt(ChatIdentity i,String id,String role,int generation,String evidence,boolean retain) {
            rejected=role;return Mono.just(new GroupState(id,"FAILED_RESOLVED",null,null,generation));
        }

        @Override
        public Mono<Void> skipAttempt(ChatIdentity i, String id, String role, int generation) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> receipt(ChatIdentity i, String id, AttemptReceipt r) {
            saved = r;
            return Mono.empty();
        }

        @Override
        public Mono<GroupState> finish(ChatIdentity i, String id) {
            finished = true;
            return Mono.just(new GroupState("call", "SUCCEEDED", state.receipt(), "PRIMARY"));
        }

        @Override
        public Mono<Void> cancelGroup(ChatIdentity i, String id, int generation) {
            cancelled = true;
            cancelledGeneration = generation;
            return Mono.empty();
        }
    }
}
