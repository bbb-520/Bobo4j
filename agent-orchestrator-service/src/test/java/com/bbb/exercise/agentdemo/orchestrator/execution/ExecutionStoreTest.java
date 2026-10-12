package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 验证执行存储的幂等、归属、租约隔离、预算检查点及生成版本一致性。 */
class ExecutionStoreTest {
    @Test
    void dispatchReservationSurvivesCrashAndReceiptReleasesOnlyUnusedTokens() {
        store.create(owner, request("reservation", "question"));
        var lease = store.claim("worker").orElseThrow();
        var step = store.beginStep(lease, "chat", "hash", "call-1");

        assertThat(store.reserveStepTokens(lease, step)).isEqualTo(64000);
        assertThat(store.reserveStepTokens(lease, step)).isEqualTo(64000);
        assertThat(store.leased(lease).budget().admit().reason()).isEqualTo("WAITING_FOR_BUDGET");
        store.settleStepTokens(lease, step, 100, 20, 2200);
        assertThat(store.leased(lease).budget().tokensUsed()).isEqualTo(120);
        assertThat(store.leased(lease).budget().reservedTokens()).isEqualTo(2200);
        store.settleStepTokens(lease, step, 100, 20, 2200);
        assertThat(store.leased(lease).budget().tokensUsed()).isEqualTo(120);
        store.settleStepTokens(lease, step, 150, 30, 0);
        assertThat(store.leased(lease).budget().tokensUsed()).isEqualTo(180);
        assertThat(store.leased(lease).budget().reservedTokens()).isZero();
    }

    @Test
    void activeTimeCheckpointIsIdempotentAndKeepsFailedAttemptCostOnRetry() {
        store.create(owner, request("time", "question"));
        var lease = store.claim("worker").orElseThrow();
        var first = store.beginStep(lease, "call", "hash", "call-1");

        store.accountStepTime(lease, first, 5000);
        store.accountStepTime(lease, first, 5000);
        assertThat(store.leased(lease).budget().activeMillis()).isEqualTo(5000);
        var retry = store.beginStep(lease, "call", "hash", "call-1");

        store.accountStepTime(lease, retry, 2000);
        assertThat(store.leased(lease).budget().activeMillis()).isEqualTo(7000);
    }

    JdbcTemplate jdbc;
    ExecutionStore store;
    ChatIdentity owner = new ChatIdentity("tenant-a", "alice", true);

    @BeforeEach
    void setup() {
        var ds = new JdbcDataSource();

        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V3__persistent_executions.sql"))
                .execute(ds);
        jdbc = new JdbcTemplate(ds);
        store =
                new ExecutionStore(
                        jdbc, new DataSourceTransactionManager(ds), JsonMapper.builder().build());
    }

    ExecutionStore.Create request(String id, String text) {
        return new ExecutionStore.Create(id, "CHAT", text, null, null, false);
    }

    @Test
    void currentModelStatusSurvivesSnapshotReloadAndOldGenerationCannotOverwriteIt() {
        var run = store.create(owner, request("models", "question"));
        var lease = store.claim("worker").orElseThrow();

        store.streamEvent(
                lease,
                "model_status",
                1,
                Map.of(
                        "provider",
                        "GPT",
                        "model",
                        "gpt-4.1",
                        "state",
                        "RUNNING",
                        "fallback",
                        false));
        store.streamEvent(
                lease,
                "model_status",
                2,
                Map.of(
                        "provider",
                        "QWEN",
                        "model",
                        "qwen-plus",
                        "state",
                        "RUNNING",
                        "fallback",
                        true));
        store.streamEvent(
                lease,
                "model_status",
                1,
                Map.of(
                        "provider",
                        "GPT",
                        "model",
                        "gpt-4.1",
                        "state",
                        "SUCCEEDED",
                        "fallback",
                        false));
        var snapshot =
                JsonMapper.builder()
                        .build()
                        .readTree(store.write(store.get(owner, run.view().executionId()).view()));

        assertThat(snapshot.path("modelStatus").path("provider").asString()).isEqualTo("QWEN");
        assertThat(snapshot.path("modelStatus").path("state").asString()).isEqualTo("RUNNING");
        assertThat(store.events(owner, run.view().executionId(), 0, 100))
                .filteredOn(e -> e.type().equals("model_status"))
                .hasSize(2);
    }

    @Test
    void requestIdReturnsSameRunAndRejectsChangedPayload() {
        var first = store.create(owner, request("request-1", "question"));

        assertThat(store.create(owner, request("request-1", "question")).view().executionId())
                .isEqualTo(first.view().executionId());
        assertThatThrownBy(() -> store.create(owner, request("request-1", "different")))
                .hasMessageContaining("幂等");
    }

    @Test
    void idempotentMutationsAndStopCompletionRaceKeepModelIdentity() {
        var input = request("status-race", "question");
        var run = store.create(owner, input);
        var lease = store.claim("worker").orElseThrow();

        store.streamEvent(
                lease,
                "model_status",
                1,
                Map.of("provider", "GPT", "model", "gpt-4.1", "state", "RUNNING"));
        store.complete(lease, "answer", java.util.List.of(), "NOT_APPLICABLE");
        assertThat(store.stop(owner, run.view().executionId()).view().modelStatus())
                .containsEntry("provider", "GPT");
        assertThat(store.create(owner, input).view().modelStatus())
                .containsEntry("provider", "GPT");
        var second = store.create(owner, request("resume-status", "question"));
        var active = store.claim("worker").orElseThrow();

        store.streamEvent(
                active,
                "model_status",
                1,
                Map.of("provider", "QWEN", "model", "qwen-plus", "state", "FAILED"));
        store.terminal(active, "FAILED", "model failed");
        var resume =
                new ExecutionStore.Resume(
                        "resume-once",
                        store.get(owner, second.view().executionId()).view().version(),
                        4,
                        null);

        store.resume(owner, second.view().executionId(), resume);
        assertThat(store.resume(owner, second.view().executionId(), resume).view().modelStatus())
                .containsEntry("provider", "QWEN");
    }

    @Test
    void otherUserAndOtherTenantCannotReadRunOrReplayEvents() {
        var row = store.create(owner, request("request-2", "question"));

        assertThatThrownBy(
                        () ->
                                store.get(
                                        new ChatIdentity("tenant-a", "bob", true),
                                        row.view().executionId()))
                .hasMessageContaining("不存在");
        assertThatThrownBy(
                        () ->
                                store.events(
                                        new ChatIdentity("tenant-b", "alice", true),
                                        row.view().executionId(),
                                        0,
                                        200))
                .hasMessageContaining("不存在");
    }

    @Test
    void takeoverFencesOldWorkerAndKeepsContinuousReplayCursor() {
        var row = store.create(owner, request("request-3", "question"));
        var old = store.claim("worker-1").orElseThrow();

        store.event(old, "progress", Map.of("stage", "start"));
        jdbc.update(
                "UPDATE agent_execution SET lease_until=? WHERE id=?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(120)),
                row.view().executionId());
        var fresh = store.claim("worker-2").orElseThrow();

        assertThatThrownBy(() -> store.event(old, "answer_delta", Map.of("text", "late")))
                .hasMessageContaining("租约");
        store.event(fresh, "progress", Map.of("stage", "resumed"));
        assertThat(store.events(owner, row.view().executionId(), 1, 200))
                .extracting(ExecutionStore.Event::seq)
                .containsExactly(2L, 3L);
    }

    @Test
    void stopRequestSurvivesReloadAndDoesNotFollowSubscriberLifecycle() {
        var row = store.create(owner, request("request-4", "question"));

        store.stop(owner, row.view().executionId());
        assertThat(store.get(owner, row.view().executionId()).view().status()).isEqualTo("STOPPED");
        assertThat(store.claim("worker")).isEmpty();
    }

    @Test
    void successfulStepIsReusedAndDispatchIsRecordedBeforeRemoteIo() {
        store.create(owner, request("request-5", "question"));
        var lease = store.claim("worker").orElseThrow();
        var step = store.beginStep(lease, "complete", "hash", "call-1");

        assertThat(step.status()).isEqualTo("DISPATCHED");
        store.finishStep(
                lease,
                step,
                Map.of("text", "answer"),
                ExecutionBudget.initial().charge(10, 5, 100));
        assertThat(store.beginStep(lease, "complete", "hash", "call-1").result().get("text"))
                .isEqualTo("answer");
        assertThatThrownBy(() -> store.beginStep(lease, "complete", "different", "call-1"))
                .hasMessageContaining("参数");
    }

    @Test
    void fallbackReplacesPartialAnswerAndLatePrimaryFramesCannotAppend() {
        var row = store.create(owner, request("stream", "question"));
        var lease = store.claim("worker").orElseThrow();

        store.streamEvent(lease, "answer_delta", 1, Map.of("text", "primary partial"));
        store.streamEvent(lease, "fallback_started", 2, Map.of("message", "switching"));
        store.streamEvent(lease, "answer_replace", 2, Map.of("text", "backup"));
        store.streamEvent(lease, "answer_delta", 1, Map.of("text", "late primary"));
        store.streamEvent(lease, "answer_delta", 2, Map.of("text", " complete"));
        var current = store.get(owner, row.view().executionId());

        assertThat(current.view().answer()).isEqualTo("backup complete");
        assertThat(current.view().generationVersion()).isEqualTo(2);
        assertThat(current.inputRevision()).isEqualTo(1);
        assertThat(store.events(owner, row.view().executionId(), 0, 200))
                .noneMatch(e -> "late primary".equals(e.payload().get("text")));
    }
}
