package com.bbb.exercise.agentdemo.ragservice;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.*;
import com.bbb.exercise.agentdemo.ragservice.domain.RagData.*;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import com.bbb.exercise.agentdemo.ragservice.processing.RagModelGateway;
import com.bbb.exercise.agentdemo.ragservice.retrieval.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

/** Real SQL verifies that retrieval materializes recalled IDs rather than entire documents. */
class HybridCandidateReadTest {
    final ChatIdentity owner = new ChatIdentity("tenant", "user", true);
    final String fingerprint = "f".repeat(64);
    RecordingJdbc db;
    RagRepository repository;
    String document;
    String conversation;

    @BeforeEach
    void initialize() throws Exception {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        db = new RecordingJdbc(source);
        try (var migrations = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path migration : migrations.sorted().toList()) {
                for (String sql : Files.readString(migration).split(";")) {
                    if (!sql.isBlank()) db.execute(sql);
                }
            }
        }
        repository = new RagRepository(db, new DataSourceTransactionManager(source));
        document = repository.register(owner, "upload", "large.txt", "hash", "/test").documentId();
        var job = repository.claim().orElseThrow();
        repository.bindEmbedding(job, fingerprint);
        repository.indexReady(job);
        conversation = repository.createConversation(owner, List.of(document)).conversationId();
        for (int i = 1; i <= 1000; i++) {
            insert(i, document, 1, owner, fingerprint);
        }
        db.chunkRows.clear();
    }

    @Test
    void onlyReadsRecallIdsWhilePreservingRerankEvidenceAndEvaluationTrace() {
        var models = new FixtureModels(db);
        var retriever = retriever(models, hits(1, 30), hits(31, 60));

        var result = retriever.retrieve(owner,
                new RetrieveRequest(conversation, "Fact", "retrieval", 64000L), "attempt");

        assertThat(db.chunkRows).containsExactly(60);
        assertThat(models.rerankTexts).containsExactly(
                "Fact 1", "Fact 31", "Fact 2", "Fact 32", "Fact 3", "Fact 33",
                "Fact 4", "Fact 34", "Fact 5", "Fact 35", "Fact 6", "Fact 36",
                "Fact 7", "Fact 37", "Fact 8", "Fact 38", "Fact 9", "Fact 39",
                "Fact 10", "Fact 40");
        assertThat(result.evidence()).extracting(Evidence::chunkId).containsExactly(id(1), id(31));
        assertThat(result.evidence()).extracting(Evidence::text).containsExactly("Fact 1", "Fact 31");
        @SuppressWarnings("unchecked")
        Map<String, Object> trace = (Map<String, Object>) repository.retrievalTraces(owner, conversation)
                .getFirst().get("payload");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recalled = (List<Map<String, Object>>) trace.get("recallEvidence");
        assertThat(recalled).extracting(c -> c.get("id"))
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 60).mapToObj(this::id).toList());
        assertThat((List<?>) trace.get("candidates")).hasSize(20);
        assertThat(trace.get("rerankInputs")).isEqualTo(models.rerankTexts);
        db.chunkRows.clear();
        assertThat(retriever.retrieve(owner,
                new RetrieveRequest(conversation, "Fact", "retrieval", 0L), "retry"))
                .isEqualTo(result);
        assertThat(db.chunkRows).isEmpty();
    }

    @Test
    void emptyRecallDoesNotReadChunksOrRerank() {
        var models = new FixtureModels(db);
        var result = retriever(models, List.of(), List.of()).retrieve(owner,
                new RetrieveRequest(conversation, "Fact", "empty", 64000L), "attempt");

        assertThat(result.evidence()).isEmpty();
        assertThat(db.chunkRows).isEmpty();
        assertThat(models.rerankTexts).isNull();
    }

    @Test
    void recalledIdsCannotSmuggleRowsOutsideTheExactScopeOrEmbeddingSpace() {
        db.update("UPDATE rag_chunk SET tenant_id='foreign-tenant' WHERE chunk_id=?", id(1));
        db.update("UPDATE rag_chunk SET user_id='foreign-user' WHERE chunk_id=?", id(2));
        db.update("UPDATE rag_chunk SET document_id='unselected-document' WHERE chunk_id=?", id(3));
        db.update("UPDATE rag_chunk SET index_version=2 WHERE chunk_id=?", id(4));
        db.update("UPDATE rag_chunk SET embedding_fingerprint=? WHERE chunk_id=?", "e".repeat(64), id(5));
        var models = new FixtureModels(db);

        var result = retriever(models, hits(1, 6), List.of()).retrieve(owner,
                new RetrieveRequest(conversation, "Fact", "scoped", 64000L), "attempt");

        assertThat(result.evidence()).extracting(Evidence::chunkId).containsExactly(id(6));
        assertThat(models.rerankTexts).containsExactly("Fact 6");
        assertThat(db.chunkRows).containsExactly(1);
    }

    @Test
    void candidateReadBatchesAndDeduplicatesIdsWithoutMaterializingUnrequestedRows() {
        var requested = new ArrayList<>(IntStream.rangeClosed(1, 245).mapToObj(this::id).toList());
        requested.add(id(1));
        requested.add(id(100));
        requested.add(id(2000)); // Missing IDs do not produce extra rows.

        var chunks = repository.candidateChunks(new Scope(owner, Map.of(document, 1), fingerprint), requested);

        assertThat(chunks).extracting(Chunk::id)
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 245).mapToObj(this::id).toList());
        assertThat(db.chunkRows).hasSize(3).allSatisfy(n -> assertThat(n).isLessThanOrEqualTo(100));
        assertThat(db.chunkRows.stream().mapToInt(Integer::intValue).sum()).isEqualTo(245);
    }

    @Test
    void candidateReadIntersectsEveryScopeDimensionAndKeepsDocumentVersionPairs() {
        db.update("UPDATE rag_chunk SET tenant_id='foreign-tenant' WHERE chunk_id=?", id(1));
        db.update("UPDATE rag_chunk SET user_id='foreign-user' WHERE chunk_id=?", id(2));
        db.update("UPDATE rag_chunk SET document_id='unselected-document' WHERE chunk_id=?", id(3));
        db.update("UPDATE rag_chunk SET index_version=2 WHERE chunk_id=?", id(4));
        db.update("UPDATE rag_chunk SET embedding_fingerprint=? WHERE chunk_id=?", "e".repeat(64), id(5));
        insert(1001, "second-document", 2, owner, fingerprint);
        insert(1002, "second-document", 1, owner, fingerprint);
        var scope = new Scope(owner, Map.of(document, 1, "second-document", 2), fingerprint);

        var chunks = repository.candidateChunks(scope, List.of(
                id(1), id(2), id(3), id(4), id(5), id(6), id(1001), id(1002)));

        assertThat(chunks).extracting(Chunk::id).containsExactlyInAnyOrder(id(6), id(1001));
        assertThat(db.chunkRows).containsExactly(2);
    }

    @Test
    void emptyCandidateListDoesNotQueryAndMissingFingerprintCannotReadLegacyRows() {
        var scope = new Scope(owner, Map.of(document, 1), fingerprint);
        assertThat(repository.candidateChunks(scope, List.of())).isEmpty();
        assertThat(db.chunkRows).isEmpty();

        db.update("UPDATE rag_chunk SET embedding_fingerprint=NULL WHERE chunk_id=?", id(1));
        assertThat(repository.candidateChunks(scope, List.of(id(1)))).isEmpty();
        assertThat(repository.candidateChunks(new Scope(owner, Map.of(document, 1)), List.of(id(1))))
                .isEmpty();
        assertThat(db.chunkRows).containsExactly(0, 0);
    }

    private HybridRetriever retriever(FixtureModels models, List<Hit> dense, List<Hit> keyword) {
        var index = new VectorIndex() {
            public void upsert(ChatIdentity who, List<Chunk> chunks, List<List<Float>> vectors) {
                throw new AssertionError("retrieval must not write the index");
            }
            public void verify(ChatIdentity who, String doc, int version, int count) {
                throw new AssertionError("unexpected verify");
            }
            public void remove(ChatIdentity who, String doc) {
                throw new AssertionError("unexpected remove");
            }
            public List<Hit> dense(Scope scope, List<Float> vector, int limit) { return dense; }
            public List<Hit> keyword(Scope scope, String query, int limit) { return keyword; }
        };
        return new HybridRetriever(index, models, repository, 64, .25, new SimpleMeterRegistry());
    }

    private List<Hit> hits(int first, int last) {
        return IntStream.rangeClosed(first, last)
                .mapToObj(i -> new Hit(id(i), document, 1, "tenant", "user", 1))
                .toList();
    }

    private String id(int i) { return String.format("%064d", i); }

    private void insert(int i, String doc, int version, ChatIdentity who, String space) {
        db.update("INSERT INTO rag_chunk(chunk_id,document_id,tenant_id,user_id,index_version,text,title,"
                        + "source_ordinal,char_start,char_end,content_hash,embedding_fingerprint)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id(i), doc, who.tenantId(), who.userId(), version, "Fact " + i, "Heading " + i,
                i, i * 100, i * 100 + 20, id(i), space);
    }

    /** Executes real H2 SQL and records how many chunk rows cross the repository boundary. */
    static class RecordingJdbc extends JdbcTemplate {
        final List<Integer> chunkRows = new ArrayList<>();
        RecordingJdbc(javax.sql.DataSource source) { super(source); }
        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
            List<T> result = super.query(sql, mapper, arguments);
            if (sql.contains("FROM rag_chunk")) chunkRows.add(result.size());
            return result;
        }
    }

    /** Only the external model boundary is replaced; budgets, snapshots and SQL remain real. */
    class FixtureModels extends RagModelGateway {
        List<String> rerankTexts;
        FixtureModels(JdbcTemplate db) { super(null, null, db); }
        @Override
        public String embeddingFingerprint(ChatIdentity who, int dimensions) { return fingerprint; }
        @Override
        public EmbeddingBatch embed(ChatIdentity who, String composite, String call,
                List<String> texts, int dimensions) {
            return new EmbeddingBatch(List.of(Collections.nCopies(dimensions, 1f)), 5, fingerprint);
        }
        @Override
        public Ranking rerank(ChatIdentity who, String composite, String call,
                String query, List<String> texts) {
            rerankTexts = texts;
            return new Ranking(texts.size() == 1
                    ? List.of(new Ranked(0, .9))
                    : List.of(new Ranked(1, .7), new Ranked(0, .9)), 7);
        }
    }
}
