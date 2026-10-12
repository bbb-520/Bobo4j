package com.bbb.exercise.agentdemo.ragservice;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.Completion;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.*;
import com.bbb.exercise.agentdemo.ragservice.answer.*;
import com.bbb.exercise.agentdemo.ragservice.chunking.SemanticChunker;
import com.bbb.exercise.agentdemo.ragservice.domain.RagData.*;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import com.bbb.exercise.agentdemo.ragservice.processing.RagModelGateway;
import com.bbb.exercise.agentdemo.ragservice.retrieval.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Image observations must be grounded separately and must participate in replay identity. */
class MixedImageAnswerTest {
    final ChatIdentity who = new ChatIdentity("tenant", "alice", true);
    final JsonMapper json = JsonMapper.builder().build();
    RagRepository repository;
    RagModelGateway gateway;
    Models protocol;
    String document, conversation;
    JdbcTemplate db;
    Chunk chunk;

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(ds);
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path migration : files.sorted().toList()) {
                for (String sql : Files.readString(migration).split(";")) if (!sql.isBlank()) db.execute(sql);
            }
        }
        repository = new RagRepository(db, new DataSourceTransactionManager(ds));
        document = repository.register(who, "upload", "budget.txt", "checksum", "/fixture").documentId();
        var job = repository.claim().orElseThrow();
        repository.bindEmbedding(job, "f".repeat(64));
        chunk = new Chunk(SemanticChunker.hash("amount"), document, 1, "本次预算金额为40元。", "预算", null, 0, 0, 12, SemanticChunker.hash("text"));
        repository.saveChunks(job, List.of(chunk));
        repository.indexed(job, repository.chunks(who, document, 1));
        repository.indexReady(job);
        conversation = repository.createConversation(who, List.of(document)).conversationId();
        protocol = new Models();
        gateway = new RagModelGateway(protocol, null, db) {
            @Override public String embeddingFingerprint(ChatIdentity identity, int dimensions) { return "f".repeat(64); }
        };
    }

    AnswerRequest request(String id, String text) {
        return json.readValue(json.writeValueAsString(Map.of(
                "callId", id, "conversationId", conversation, "question", "比较图片金额与预算", "tokenBudget", 64000,
                "imageEvidence", Map.of("text", text, "assetIds", List.of("image-a")))), AnswerRequest.class);
    }

    @Test
    void imageFactsGetTheirOwnCitedSourceAndAreIncludedInBothJudges() {
        var index = new VectorIndex() {
            public void upsert(ChatIdentity identity, List<Chunk> body, List<List<Float>> vectors) {}
            public void verify(ChatIdentity identity, String doc, int version, int count) {}
            public void remove(ChatIdentity identity, String doc) {}
            public List<Hit> dense(Scope scope, List<Float> vector, int limit) { return List.of(new Hit(chunk.id(), document, 1, "tenant", "alice", 1)); }
            public List<Hit> keyword(Scope scope, String query, int limit) { return dense(scope, List.of(), limit); }
        };
        var metrics = new SimpleMeterRegistry();
        var service = new DocumentAnswerService(repository, new HybridRetriever(index, gateway, repository, 64, .25, metrics), gateway, new GroundingEvaluator(gateway, metrics));
        try {
            var answer = service.answer(who, request("mixed", "图片中发票金额为42元，数字清晰。"));
            assertThat(answer.evaluation()).isEqualTo("PASS");
            assertThat(answer.sources()).hasSize(2);
            assertThat(answer.sources().getFirst().documentId()).isEqualTo(document);
            var source = json.readTree(json.writeValueAsString(answer.sources().getLast()));
            assertThat(source.path("kind").asString()).isEqualTo("IMAGE");
            assertThat(source.path("documentId").isNull()).isTrue();
            assertThat(source.path("excerpt").asString()).contains("42元");
            assertThat(protocol.draft).contains("40元", "42元", "图片附件");
            assertThat(protocol.facts).contains("40元", "42元");
            assertThat(protocol.retrievalQuery).contains("比较图片金额与预算", "42元");
            assertThat(protocol.retrievalQuery.length()).isLessThanOrEqualTo(4000);
            int calls = protocol.calls;
            service.answer(who, request("mixed", "图片中发票金额为42元，数字清晰。"));
            assertThat(protocol.calls).isEqualTo(calls);
        } finally { service.shutdown(); }
    }

    @Test
    void changedImageEvidenceCannotReplayAnExistingDocumentCall() {
        var first = request("idempotent", "图片金额42元");
        var lease = repository.begin(who, first);
        repository.commit(who, first, lease.fence(), new Answer("42", List.of(), "PASS", false, 1, 1));
        assertThatThrownBy(() -> repository.begin(who, request("idempotent", "图片金额99元"))).hasMessageContaining("PARAMETER_CONFLICT");
    }

    @Test
    void malformedImageEvidenceIsRejectedBeforeAnyModelDispatch() {
        var index = new VectorIndex() {
            public void upsert(ChatIdentity identity, List<Chunk> body, List<List<Float>> vectors) {}
            public void verify(ChatIdentity identity, String doc, int version, int count) {}
            public void remove(ChatIdentity identity, String doc) {}
            public List<Hit> dense(Scope scope, List<Float> vector, int limit) { throw new AssertionError("must reject before retrieval"); }
            public List<Hit> keyword(Scope scope, String query, int limit) { throw new AssertionError("must reject before retrieval"); }
        };
        var metrics = new SimpleMeterRegistry();
        var service = new DocumentAnswerService(repository, new HybridRetriever(index, gateway, repository, 64, .25, metrics), gateway, new GroundingEvaluator(gateway, metrics));
        try {
            var invalid = List.of(new ImageEvidence(" ", List.of("image-a")),
                    new ImageEvidence("42", List.of("image-a", "image-a")),
                    new ImageEvidence("42", List.of("../other")),
                    new ImageEvidence("42", List.of("a", "b", "c", "d")));
            for (ImageEvidence image : invalid) {
                assertThatThrownBy(() -> service.answer(who, new AnswerRequest("invalid", conversation, "金额？", 64000L, image)))
                        .hasMessageContaining("IMAGE_EVIDENCE_INVALID");
            }
            assertThat(protocol.calls).isZero();
        } finally { service.shutdown(); }
    }

    static class Models extends LongChineseAnswerTest.BoundedModels {
        String draft, facts, retrievalQuery;
        @Override
        public com.bbb.exercise.agentdemo.api.model.ModelContracts.EmbeddingBatch embed(
                ChatIdentity identity, String call, List<String> texts, int dimensions, Long budget) {
            retrievalQuery = texts.getFirst();
            return super.embed(identity, call, texts, dimensions, budget);
        }
        @Override
        public Completion complete(ChatIdentity identity, String call, String mode, String system, String user, boolean fallback, Long budget) {
            calls++;
            String text;
            if (call.endsWith(":relevance")) text = "YES";
            else if (call.endsWith(":facts")) { facts = user; text = "{\"supported\":true,\"unsupportedClaims\":[]}"; }
            else { draft = user; text = "文档预算金额为40元[S1]，图片识别金额为42元[S2]，相差2元[S1][S2]。"; }
            return new Completion(text, 50, 20, "fixture", false);
        }
    }
}
