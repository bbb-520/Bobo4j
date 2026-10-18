package com.bbb.exercise.agentdemo.chatservice.chat;

import com.bbb.exercise.agentdemo.api.dto.ChatAttachmentRequest;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

/** Exercises the owned-media HTTP boundary and durable chat result rather than client URLs. */
class OwnedImageCompletionTest {
    final ChatIdentity identity = new ChatIdentity("tenant", "alice", true);
    final String secrets = "current=" + Base64.getEncoder().encodeToString(new byte[32]);
    final PrincipalKeyRing keys = new PrincipalKeyRing(secrets, "current");
    final JsonMapper json = JsonMapper.builder().build();
    HttpServer media;
    ExecutorService mediaThreads;
    volatile CountDownLatch firstAssetRead, resumeFirstAssetRead;
    AtomicInteger assetReads = new AtomicInteger();
    String lookupSubject, lookupTenant;
    volatile int mediaStatus = 200;
    boolean emptyDownload;
    JdbcTemplate jdbc;
    VisionModel model;
    InternalCompletionServiceTest.FakeConversations conversations;
    InternalCompletionService service;

    @BeforeEach
    void setup() throws Exception {
        media = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        media.createContext("/internal/media/assets/", exchange -> {
            var principal = keys.verify(exchange.getRequestHeaders().getFirst("X-Internal-Principal"),
                    "agent-media-service", "GET " + exchange.getRequestURI().getPath());
            lookupSubject = principal.subject();
            lookupTenant = principal.tenant();
            int revision = assetReads.incrementAndGet();
            int responseStatus = mediaStatus;
            if (revision == 1 && firstAssetRead != null) {
                firstAssetRead.countDown();
                try {
                    if (!resumeFirstAssetRead.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("first media read was not released");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
            String assetId = exchange.getRequestURI().getPath().substring("/internal/media/assets/".length());
            byte[] body = json.writeValueAsBytes(Map.of(
                    "assetId", assetId,
                    "objectKey", "tenant/alice/" + assetId,
                    "mimeType", "image/png",
                    "fileSize", 128,
                    "downloadUrl", emptyDownload ? "" : "https://owned.example/" + assetId + "?signature=" + revision));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        mediaThreads = Executors.newCachedThreadPool();
        media.setExecutor(mediaThreads);
        media.start();
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE chat_completion(call_id VARCHAR(128) PRIMARY KEY,user_id VARCHAR(128),tenant_id VARCHAR(128),parameter_hash VARCHAR(64),model_user CLOB,status VARCHAR(16),receipt CLOB,history_committed BOOLEAN DEFAULT FALSE,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        model = new VisionModel();
        conversations = new InternalCompletionServiceTest.FakeConversations();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var transport = new InternalServiceClient("http://127.0.0.1:1",
                "http://127.0.0.1:" + media.getAddress().getPort(), "agent-chat-service", secrets,
                "current", "session", "tenant");
        service = new InternalCompletionService(jdbc, tx, model, conversations, transport);
    }

    @AfterEach
    void close() {
        media.stop(0);
        mediaThreads.shutdownNow();
    }

    @Test
    void ownedImagesUseSignedOwnerLookupAndOrdinaryStreamWithHistory() {
        var events = new ArrayList<GenerationEvent>();
        service.completeStreaming(identity, request("call", "比较图片", List.of(image("a"), image("b"))), event -> {
            if (event.type().equals("answer_committed")) {
                assertThat(conversations.turns).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT status FROM chat_completion WHERE call_id='call'", String.class))
                        .isEqualTo("SUCCEEDED");
            }
            events.add(event);
        });

        assertThat(lookupSubject).isEqualTo("alice");
        assertThat(lookupTenant).isEqualTo("tenant");
        assertThat(assetReads.get()).isEqualTo(2);
        assertThat(model.visionCalls).isEqualTo(1);
        assertThat(model.lastSystem).isEqualTo("rules");
        assertThat(model.lastUser).contains("user: past", "assistant: previous", "比较图片");
        assertThat(model.lastBudget).isEqualTo(64000L);
        var images = json.valueToTree(model.images);
        assertThat(images.path(0).path("assetId").asString()).isEqualTo("a");
        assertThat(images.path(0).path("url").asString()).isEqualTo("https://owned.example/a?signature=1");
        assertThat(images.path(1).path("url").asString()).isEqualTo("https://owned.example/b?signature=2");
        assertThat(events).extracting(GenerationEvent::type).containsExactly("answer_delta", "answer_committed");
    }

    @Test
    void attachmentOnlyChatUsesADescriptionPrompt() {
        assertThatCode(() -> service.complete(identity, request("call", " ", List.of(image("a")))))
                .doesNotThrowAnyException();

        assertThat(model.visionCalls).isEqualTo(1);
        assertThat(model.lastUser).contains("描述", "图片");
        assertThat(conversations.turns).isEqualTo(1);
    }

    @Test
    void deniedAndUnreadyMediaCannotDispatchAModelOrCreateAChatReceipt() {
        for (int status : List.of(400, 403, 404, 409, 410)) {
            mediaStatus = status;
            assertThatThrownBy(() -> service.complete(identity,
                    request("call" + status, "question", List.of(image("a")))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("图片附件不可用或已过期，请重新上传。");
        }

        assertThat(model.calls).isZero();
        assertThat(model.visionCalls).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_completion WHERE receipt IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_completion WHERE status='ASSET_REJECTED'", Integer.class)).isEqualTo(5);
    }

    @Test
    void missingDownloadUrlCannotDispatchAModel() {
        emptyDownload = true;

        assertThatThrownBy(() -> service.complete(identity,
                request("call", "question", List.of(image("a")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(model.calls).isZero();
        assertThat(model.visionCalls).isZero();
    }

    @Test
    void malformedDuplicateAndExcessImagesAreRejectedBeforeMediaLookup() {
        var invalidMime = image("a");
        invalidMime.setMimeType("application/pdf");
        var tooLarge = image("a");
        tooLarge.setFileSize(20L * 1024 * 1024 + 1);
        var empty = image("a");
        empty.setFileSize(0L);
        List<List<ChatAttachmentRequest>> invalid = List.of(
                Arrays.asList((ChatAttachmentRequest) null),
                List.of(image("a"), image("a")),
                List.of(image("a"), image("b"), image("c"), image("d")),
                List.of(image("../foreign")),
                List.of(image("https://evil.example/x")),
                List.of(invalidMime), List.of(tooLarge), List.of(empty));

        for (int n = 0; n < invalid.size(); n++) {
            var request = request("invalid" + n, "question", invalid.get(n));
            assertThatThrownBy(() -> service.complete(identity, request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(assetReads.get()).isZero();
        assertThat(model.calls).isZero();
        assertThat(model.visionCalls).isZero();
    }

    @Test
    void refreshedUrlsReplayOneHistoryTurnAndAChangedAssetConflicts() {
        var request = request("call", "question", List.of(image("a"), image("b")));
        assertThat(service.complete(identity, request).text()).isEqualTo("answer");
        assertThat(service.complete(identity, request).text()).isEqualTo("answer");

        assertThat(assetReads.get()).isEqualTo(2);
        assertThat(model.visionCalls).isEqualTo(1);
        assertThat(conversations.turns).isEqualTo(1);
        assertThatThrownBy(() -> service.complete(identity,
                request("call", "question", List.of(image("a"), image("c")))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(assetReads.get()).isEqualTo(2);
    }

    @Test
    void aPaidCommittedReceiptReplaysDuringMediaOutageWithoutANewAssetRead() {
        var request = request("paid", "question", List.of(image("a")));
        var first = service.complete(identity, request);
        mediaStatus = 500;
        assertThat(service.complete(identity, request)).isEqualTo(first);
        assertThat(assetReads.get()).isEqualTo(1);
        assertThat(model.visionCalls).isEqualTo(1);
        assertThat(conversations.turns).isEqualTo(1);
    }

    @Test
    void expiredAssetHasAFixedReuploadErrorBeforeAnyModelDispatch() {
        mediaStatus = 400;
        assertThatThrownBy(() -> service.complete(identity, request("expired", "question", List.of(image("a")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重新上传");
        assertThat(model.visionCalls).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM chat_completion WHERE call_id='expired'", String.class))
                .isEqualTo("ASSET_REJECTED");
    }

    @Test
    void expiredImageStreamReturnsTheFixedSafeJsonErrorBeforeItsFirstEvent() {
        mediaStatus = 400;
        var http = WebTestClient.bindToController(
                        new InternalChatController(service, null, secrets, "current"))
                .controllerAdvice(new ChatExceptionHandler()).build();

        http.post().uri("/internal/chat/stream")
                .header("X-Internal-Principal", keys.sign("agent-orchestrator-service", "alice", "tenant",
                        "agent-chat-service", "POST /internal/chat/stream"))
                .bodyValue(request("expired-stream", "question", List.of(image("a"))))
                .exchange().expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody().jsonPath("$.code").isEqualTo("IMAGE_ATTACHMENT_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("图片附件不可用或已过期，请重新上传。");

        assertThat(model.visionCalls).isZero();
        assertThat(model.calls).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM chat_completion WHERE call_id='expired-stream'", String.class))
                .isEqualTo("ASSET_REJECTED");
    }

    @Test
    void paidModelReceiptRecoversAnUncommittedChatTurnAfterTheAssetExpires() {
        var request = request("paid-gap", "question", List.of(image("a")));
        conversations.fail = true;
        assertThatThrownBy(() -> service.complete(identity, request))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT receipt FROM chat_completion WHERE call_id='paid-gap'", String.class))
                .isNull();
        assertThat(model.authReceipt).isNotNull();
        conversations.fail = false;
        mediaStatus = 400;

        assertThatThrownBy(() -> service.complete(new ChatIdentity("tenant", "bob", true), request))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.complete(identity,
                request("paid-gap", "changed question", List.of(image("a")))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(model.recoveryCalls).isZero();

        var events = new ArrayList<GenerationEvent>();
        assertThatCode(() -> service.completeStreaming(identity, request, events::add))
                .doesNotThrowAnyException();
        assertThat(events).extracting(GenerationEvent::type).containsExactly("answer_committed");
        assertThat(events.getFirst().payload()).containsEntry("text", "answer");
        assertThat(model.visionCalls).isEqualTo(1);
        assertThat(model.recoveryCalls).isEqualTo(1);
        assertThat(assetReads.get()).isEqualTo(1);
        assertThat(conversations.turns).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM chat_completion WHERE call_id='paid-gap'", String.class))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void anUnknownDispatchedModelOutcomeCannotBeHiddenByAnExpiredAsset() {
        var request = request("unknown-gap", "question", List.of(image("a")));
        model.generationError = new ModelCallClient.ModelCallException("MODEL_OUTCOME_UNKNOWN", false, 409);
        assertThatThrownBy(() -> service.complete(identity, request))
                .isInstanceOf(ModelCallClient.ModelCallException.class);
        model.recoveryError = model.generationError;
        mediaStatus = 400;

        assertThatThrownBy(() -> service.complete(identity, request))
                .isInstanceOfSatisfying(ModelCallClient.ModelCallException.class, error -> {
                    assertThat(error.code()).isEqualTo("MODEL_OUTCOME_UNKNOWN");
                    assertThat(error.status()).isEqualTo(409);
                });
        assertThat(model.visionCalls).isEqualTo(1);
        assertThat(model.recoveryCalls).isEqualTo(1);
        assertThat(assetReads.get()).isEqualTo(1);
        assertThat(conversations.turns).isZero();
        assertThat(jdbc.queryForObject("SELECT receipt FROM chat_completion WHERE call_id='unknown-gap'", String.class))
                .isNull();
    }

    @Test
    void anUncommittedCallWithNoRecoveredReceiptRetainsItsHoldWhenTheAssetExpires() {
        var request = request("terminal-gap", "question", List.of(image("a")));
        model.generationError = new ModelCallClient.ModelCallException("ADMISSION_TIMEOUT", false, 0);
        assertThatThrownBy(() -> service.complete(identity, request))
                .isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(model.authReceipt).isNull();
        mediaStatus = 400;

        assertThatThrownBy(() -> service.complete(identity, request))
                .isInstanceOfSatisfying(ModelCallClient.ModelCallException.class, error -> {
                    assertThat(error.code()).isEqualTo("MODEL_OUTCOME_UNKNOWN");
                    assertThat(error.status()).isEqualTo(409);
                });
        assertThat(model.visionCalls).isEqualTo(1);
        assertThat(model.recoveryCalls).isEqualTo(1);
        assertThat(assetReads.get()).isEqualTo(2);
        assertThat(conversations.turns).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM chat_completion WHERE call_id='terminal-gap'", String.class))
                .isEqualTo("DISPATCHED");
        assertThat(jdbc.queryForObject("SELECT receipt FROM chat_completion WHERE call_id='terminal-gap'", String.class))
                .isNull();
    }

    @Test
    void aConcurrentFreshAssetRejectionDurablyPreventsTheWaitingRequestFromDispatching() throws Exception {
        var request = request("fresh-race", "question", List.of(image("a")));
        firstAssetRead = new CountDownLatch(1);
        resumeFirstAssetRead = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> service.complete(identity, request));
        try {
            assertThat(firstAssetRead.await(5, TimeUnit.SECONDS)).isTrue();
            mediaStatus = 400;
            assertThatThrownBy(() -> service.complete(identity, request))
                    .isInstanceOf(InternalCompletionService.ImageAttachmentUnavailable.class);
        } finally {
            resumeFirstAssetRead.countDown();
        }

        assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(InternalCompletionService.ImageAttachmentUnavailable.class);
        assertThat(model.visionCalls).isZero();
        assertThat(conversations.turns).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM chat_completion WHERE call_id='fresh-race'", String.class))
                .isEqualTo("ASSET_REJECTED");
        assertThat(jdbc.queryForObject("SELECT receipt FROM chat_completion WHERE call_id='fresh-race'", String.class))
                .isNull();
        assertThatThrownBy(() -> service.complete(identity, request))
                .isInstanceOf(InternalCompletionService.ImageAttachmentUnavailable.class);
        assertThat(assetReads.get()).isEqualTo(2);
    }

    @Test
    void aConcurrentAssetFailureCannotReleaseTheHoldAfterAnotherValidatorDispatches() throws Exception {
        var request = request("dispatch-race", "question", List.of(image("a")));
        mediaStatus = 400;
        firstAssetRead = new CountDownLatch(1);
        resumeFirstAssetRead = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> service.complete(identity, request));
        try {
            assertThat(firstAssetRead.await(5, TimeUnit.SECONDS)).isTrue();
            mediaStatus = 200;
            assertThat(service.complete(identity, request).text()).isEqualTo("answer");
        } finally {
            resumeFirstAssetRead.countDown();
        }

        assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ModelCallClient.ModelCallException.class)
                .satisfies(error -> {
                    var failure = (ModelCallClient.ModelCallException) error.getCause();
                    assertThat(failure.code()).isEqualTo("MODEL_OUTCOME_UNKNOWN");
                    assertThat(failure.status()).isEqualTo(409);
                });
        assertThat(model.visionCalls).isEqualTo(1);
        assertThat(model.lastUser).contains("user: past", "assistant: previous", "question");
        assertThat(conversations.turns).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM chat_completion WHERE call_id='dispatch-race'", String.class))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void aLegacyTextReceiptKeepsItsOriginalParameterHashAfterUpgrade() {
        jdbc.update("INSERT INTO chat_completion(call_id,user_id,tenant_id,parameter_hash,model_user,status,receipt,history_committed) VALUES (?,?,?,?,?,'SUCCEEDED',?,TRUE)",
                "call", "alice", "tenant", "75deafa2ae7668d5aed48552c8a740a40d026904580cab297cfeb50ecee87b37",
                "old model input", "{\"text\":\"legacy answer\",\"inputTokens\":12,\"outputTokens\":3,\"model\":\"model\",\"fallback\":false}");

        var legacyRequest = json.readValue(
                "{\"callId\":\"call\",\"mode\":\"CHAT\",\"system\":\"rules\",\"user\":\"question\",\"conversationId\":\"cid\",\"tokenBudget\":0}",
                CompletionRequest.class);
        assertThat(service.complete(identity, legacyRequest).text()).isEqualTo("legacy answer");
        assertThat(model.calls).isZero();
        assertThat(assetReads.get()).isZero();
    }

    private CompletionRequest request(String id, String user, List<ChatAttachmentRequest> images) {
        return new CompletionRequest(id, "CHAT", "rules", user, "cid", 64000L, images);
    }

    private static ChatAttachmentRequest image(String id) {
        var result = new ChatAttachmentRequest();
        result.setAssetId(id);
        return result;
    }

    static class VisionModel extends InternalCompletionServiceTest.FakeModel {
        int visionCalls;
        List<?> images;
        Long lastBudget;
        Completion authReceipt;
        RuntimeException generationError, recoveryError;
        int recoveryCalls;

        @Override
        public Optional<Completion> recoverCompletion(ChatIdentity identity, String id) {
            recoveryCalls++;
            if (recoveryError != null) {
                throw recoveryError;
            }
            return Optional.ofNullable(authReceipt);
        }

        @Override
        public Completion completeVision(ChatIdentity i, String id, String system, String user,
                List<VisionImage> images, Long budget, Consumer<GenerationEvent> events) {
            visionCalls++;
            if (generationError != null) {
                throw generationError;
            }
            lastId = id;
            lastSystem = system;
            lastUser = user;
            this.images = images;
            lastBudget = budget;
            events.accept(new GenerationEvent("answer_delta", 1, Map.of("text", "answer")));
            var result = new Completion("answer", 321, 8, "vision-model", false);
            authReceipt = result;
            events.accept(ModelCallClient.committedEvent(result));
            return result;
        }
    }
}
