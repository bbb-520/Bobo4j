package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Tests durable step replay and recovery against real execution tables. */
class ExecutionStepRunnerTest {
    private ExecutionStore store;
    private ExecutionClient client;
    private ExecutionStepRunner runner;
    private ExecutionStore.Lease lease;
    private SimpleMeterRegistry metrics;

    @BeforeEach
    void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V3__persistent_executions.sql"))
                .execute(source);
        store = new ExecutionStore(new JdbcTemplate(source),
                new DataSourceTransactionManager(source), JsonMapper.builder().build());
        client = mock(ExecutionClient.class);
        metrics = new SimpleMeterRegistry();
        runner = new ExecutionStepRunner(store, client, metrics);
        store.create(new ChatIdentity("tenant", "user", true),
                new ExecutionStore.Create("request", "CHAT", "question", null, null, false));
        lease = store.claim("worker").orElseThrow();
    }

    @Test
    void completedStepReplaysWithoutAnotherProviderRequestOrTokenCharge() throws Exception {
        when(client.post(any(), eq("rag"), eq("/internal/rag/retrieve"), any(), eq(false)))
                .thenReturn(CompletableFuture.completedFuture(
                        Map.of("text", "evidence", "inputTokens", 12, "outputTokens", 3)));
        var body = Map.of("question", "question");

        var result = runner.invoke(lease, "g1:r0:tool", "rag", "/internal/rag/retrieve",
                body, false, "TOOL", "rag.search");
        var replay = runner.invoke(lease, "g1:r0:tool", "rag", "/internal/rag/retrieve",
                body, false, "TOOL", "rag.search");

        assertThat(replay).isEqualTo(result);
        assertThat(store.completedSteps(lease)).hasSize(1);
        assertThat(store.leased(lease).budget().tokensUsed()).isEqualTo(15);
        assertThat(store.leased(lease).budget().toolCalls()).isEqualTo(1);
        verify(client, times(1)).post(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void unknownPaidOutcomeRemainsReservedAndIsNotRedispatchedByRecovery() {
        when(client.post(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(CompletableFuture.failedFuture(new ExecutionClient.RemoteFailure(503)));
        when(client.get(any(), eq("auth"), any()))
                .thenReturn(CompletableFuture.completedFuture(Map.of("status", "UNKNOWN")));

        assertThatThrownBy(() -> runner.invoke(lease, "g1:r0:chat", "chat",
                "/internal/chat/complete", Map.of("user", "question"), false, "DIRECT", null))
                .hasCauseInstanceOf(ExecutionClient.RemoteFailure.class);
        assertThatThrownBy(() -> runner.recoverPending(lease, store.leased(lease)))
                .isInstanceOf(ExecutionClient.RemoteFailure.class)
                .satisfies(error -> assertThat(((ExecutionClient.RemoteFailure) error).status)
                        .isEqualTo(409));

        assertThat(store.leased(lease).budget().reservedTokens()).isPositive();
        assertThat(store.completedSteps(lease)).isEmpty();
        verify(client, times(1)).post(any(), any(), any(), any(), anyBoolean());
        verify(client, never()).stream(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ragStreamPersistsGenerationEventsAndSettlesTheDurableReceipt() throws Exception {
        when(client.streamRag(any(), any(), any())).thenAnswer(invocation -> {
            Consumer<Map<String, Object>> forward = invocation.getArgument(2);
            forward.accept(Map.of("type", "answer_delta", "generationVersion", 1,
                    "payload", Map.of("text", "draft")));
            forward.accept(Map.of("type", "answer_replace", "generationVersion", 2,
                    "payload", Map.of("text", "verified")));
            return CompletableFuture.completedFuture(
                    Map.of("text", "verified", "inputTokens", 20, "outputTokens", 5));
        });

        var result = runner.invokeStreamCore(lease, "g1:r0:document", "rag",
                Map.of("question", "question"));

        assertThat(result).containsEntry("text", "verified");
        assertThat(store.leased(lease).view().answer()).isEqualTo("verified");
        assertThat(store.leased(lease).view().generationVersion()).isEqualTo(2);
        assertThat(store.leased(lease).budget().tokensUsed()).isEqualTo(25);
        assertThat(store.leased(lease).budget().reservedTokens()).isZero();
        assertThat(store.completedSteps(lease)).hasSize(1);
        verify(client, never()).stream(any(), any(), any());
    }
}
