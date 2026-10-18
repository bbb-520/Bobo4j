package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelPrompts;
import com.bbb.exercise.agentdemo.api.dto.ChatAttachmentRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutionPromptTest {
    private ExecutionStore store;
    private final ExecutionClient client = mock(ExecutionClient.class);
    private final ChatIdentity owner = new ChatIdentity("tenant", "user", true);

    @BeforeEach
    void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__persistent_executions.sql")).execute(source);
        store = new ExecutionStore(new JdbcTemplate(source), new DataSourceTransactionManager(source), JsonMapper.builder().build());
    }

    @Test
    void directChatUsesSharedRulesWithoutDuplicatingThemInTheSupplement() {
        var captured = new AtomicReference<Map<?, ?>>();
        when(client.stream(eq(owner), any(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return CompletableFuture.completedFuture(Map.of("text", "answer", "inputTokens", 10, "outputTokens", 5));
        });
        var run = store.create(owner, new ExecutionStore.Create("chat", "CHAT", "question", null, null, false));
        var worker = new ExecutionWorker(store, client, new SimpleMeterRegistry());
        try { worker.run(store.claim("worker").orElseThrow()); }
        finally { worker.close(); }
        assertThat(store.get(owner, run.view().executionId()).view().status()).isEqualTo("COMPLETED");
        assertThat(captured.get().get("mode")).isEqualTo("CHAT");
        assertThat(captured.get().get("system")).isEqualTo("");
        assertThat(captured.get().get("user")).isEqualTo("question");
        assertThat(ModelPrompts.assemble("CHAT", (String) captured.get().get("system"), false))
                .contains("没有实际工具回执时不声称执行过工具");
    }

    @Test
    void decisionSupplementRetainsOnlyTheCurrentScopeToolCatalog() {
        var tool = new ExecutionToolRegistry.Registration("mcp.test.scoped", "Tenant-scoped evidence", Set.of("query"),
                (arguments, scope) -> { throw new AssertionError("FINAL must not execute tools"); });
        var registry = new ExecutionToolRegistry(List.of(), List.of(scope ->
                scope.identity().equals(owner) ? List.of(tool) : List.of()));
        var captured = new AtomicReference<Map<?, ?>>();
        when(client.post(eq(owner), eq("chat"), eq("/internal/chat/complete"), any(), eq(false))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(3));
            return CompletableFuture.completedFuture(Map.of("text", "{\"type\":\"FINAL\",\"answer\":\"answer\"}",
                    "inputTokens", 10, "outputTokens", 5));
        });
        var run = store.create(owner, new ExecutionStore.Create("agent", "AGENT", "question", null, null, false));
        var lease = store.claim("worker").orElseThrow();
        String toolCallId = ExecutionWorker.callId(lease, "g1:r0:tool");
        var scope = new ExecutionToolRegistry.Scope(owner, null, null, false, toolCallId, "question", Set.of());
        String expectedCatalog = "\n服务端已注册工具及参数：\n" + registry.descriptions(scope);
        var worker = new ExecutionWorker(store, client, new SimpleMeterRegistry(), ObservationRegistry.NOOP, registry);
        try { worker.run(lease); }
        finally { worker.close(); }
        assertThat(store.get(owner, run.view().executionId()).view().status()).isEqualTo("COMPLETED");
        assertThat(captured.get().get("mode")).isEqualTo("DECISION");
        assertThat(captured.get().get("system")).isEqualTo(expectedCatalog);
        assertThat(expectedCatalog).contains("mcp.test.scoped", "\"properties\"", "\"query\"");
        var other = new ExecutionToolRegistry.Scope(new ChatIdentity("other", "user", true), null, null, false, "other", "q", Set.of());
        assertThat(registry.descriptions(other)).doesNotContain("mcp.test.scoped");
        assertThat(ModelPrompts.assemble("DECISION", expectedCatalog, false))
                .contains("media.create必须用户明确授权", "\"type\":\"PAUSE\"")
                .endsWith(expectedCatalog);
    }

    @Test
    void oldPendingChatImageChatAndDecisionRetryTheirExactSavedRequestAfterReconciliation() {
        for (String kind : List.of("CHAT", "IMAGE_CHAT", "DECISION")) {
            for (boolean missingGroup : new boolean[]{true, false}) {
                setup();
                reset(client);
                boolean decision = kind.equals("DECISION"), image = kind.equals("IMAGE_CHAT");
                var attachment = new ChatAttachmentRequest();
                attachment.setAssetId("asset-a");
                attachment.setMimeType("image/png");
                var run = store.create(owner, new ExecutionStore.Create("old-" + kind,
                        decision ? "AGENT" : "CHAT", "original question", null, null, false,
                        image ? List.of(attachment) : null));
                var lease = store.claim("worker").orElseThrow();
                String key = "g1:r0:" + (decision ? "decision" : "chat");
                String callId = ExecutionWorker.callId(lease, key);
                var original = new LinkedHashMap<String, Object>();
                original.put("callId", callId);
                original.put("mode", decision ? "DECISION" : "CHAT");
                original.put("system", decision
                        ? "你是任务决策器。只返回一个JSON对象，不输出思维链或Markdown。\n服务端已注册工具及参数：\n旧版工具说明"
                        : "你是bobo文字助手。根据用户问题及已提交历史准确回答，默认中文。信息不足时说明。没有实际工具回执时不声称执行过工具，不编造文档、来源、图片内容或任务完成情况。");
                original.put("user", "original question");
                original.put("conversationId", null);
                if (image) original.put("attachments", store.leased(lease).input().attachments());
                String oldHash = ExecutionStore.hash(store.write(original));
                store.beginStep(lease, key, oldHash, callId, "chat", "/internal/chat/complete", original,
                        decision ? "DECISION" : "DIRECT", null);
                if (missingGroup) {
                    when(client.get(eq(owner), eq("auth"), any())).thenReturn(
                            CompletableFuture.failedFuture(new ExecutionClient.RemoteFailure(404)));
                } else {
                    when(client.get(eq(owner), eq("auth"), any())).thenReturn(
                            CompletableFuture.completedFuture(Map.of("status", "FAILED_RESOLVED", "unknownTokens", 0)));
                }
                var captured = new AtomicReference<Map<String, Object>>();
                var receipt = Map.<String, Object>of("text", decision ? "{\"type\":\"FINAL\",\"answer\":\"done\"}" : "done",
                        "inputTokens", 10, "outputTokens", 5);
                if (decision) {
                    when(client.post(eq(owner), eq("chat"), eq("/internal/chat/complete"), any(), eq(false)))
                            .thenAnswer(invocation -> { captured.set(invocation.getArgument(3)); return CompletableFuture.completedFuture(receipt); });
                } else {
                    when(client.stream(eq(owner), any(), any()))
                            .thenAnswer(invocation -> { captured.set(invocation.getArgument(1)); return CompletableFuture.completedFuture(receipt); });
                }
                var worker = new ExecutionWorker(store, client, new SimpleMeterRegistry());
                try { worker.run(lease); }
                finally { worker.close(); }
                var done = store.get(owner, run.view().executionId());
                assertThat(done.view().status()).as("legacy %s, missingGroup=%s", kind, missingGroup).isEqualTo("COMPLETED");
                assertThat(done.view().answer()).isEqualTo("done");
                var actual = new LinkedHashMap<>(captured.get());
                assertThat(actual.remove("tokenBudget")).isInstanceOf(Number.class);
                assertThat(actual).isEqualTo(store.map(store.write(original)));
                assertThat(ExecutionStore.hash(store.write(actual))).isEqualTo(oldHash);
                verify(client, times(1)).get(eq(owner), eq("auth"), any());
                if (decision) {
                    verify(client, times(1)).post(eq(owner), eq("chat"), eq("/internal/chat/complete"), any(), eq(false));
                    verify(client, never()).stream(any(), any(), any());
                } else {
                    verify(client, times(1)).stream(eq(owner), any(), any());
                    verify(client, never()).post(any(), any(), any(), any(), anyBoolean());
                }
            }
        }
    }
}
