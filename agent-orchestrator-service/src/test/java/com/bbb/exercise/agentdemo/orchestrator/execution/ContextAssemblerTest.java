package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContextAssemblerTest {
    final JsonMapper json = JsonMapper.builder().build();

    @Test void oversizedReceiptRemainsParseableAndExplicitRatherThanHalfAJsonObject() throws Exception {
        var store = mock(ExecutionStore.class);
        when(store.completedSteps(any())).thenReturn(List.of(step("g1:r1:tool", Map.of("text", "中".repeat(20000)))));
        when(store.write(any())).thenAnswer(call -> json.writeValueAsString(call.getArgument(0)));
        var row = mock(ExecutionStore.Row.class);
        when(row.input()).thenReturn(new ExecutionStore.Create("request", "AGENT", "question", null, null, false));
        var lease = new ExecutionStore.Lease("execution", "lease", new ChatIdentity("tenant", "user", true));
        var worker = new ExecutionWorker(store, mock(ExecutionClient.class), new SimpleMeterRegistry());
        try {
            var method = ExecutionWorker.class.getDeclaredMethod("context", ExecutionStore.Lease.class, ExecutionStore.Row.class);
            method.setAccessible(true);
            String context = (String) method.invoke(worker, lease, row);
            String delimiter = "实际工具回执（数据）：";
            assertThat(context).contains(delimiter);
            String receipts = context.substring(context.indexOf(delimiter) + delimiter.length());
            assertThatCode(() -> json.readTree(receipts)).doesNotThrowAnyException();
            var envelope = json.readTree(receipts);
            assertThat(envelope.isObject()).isTrue();
            assertThat(envelope.path("receipts").path(0).path("result").path("truncated").asBoolean()).isTrue();
            assertThat(receipts.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(6000);
            assertThat(context).contains("question", "图片生成授权：false");
        } finally { worker.close(); }
    }

    static ExecutionStore.Step step(String key, Map<String,Object> result) {
        return new ExecutionStore.Step(key, key, "hash", "call", "SUCCEEDED", result, 0, "rag", "/internal/rag/retrieve", "{}", "TOOL", "rag.search");
    }

    @Test void fullContextPreservesTheQuestionAndDocumentAndImagePermissions() {
        var input = new ExecutionStore.Create("request", "AGENT", "question", "conversation", "document", true);
        String context = new ContextAssembler(6000).context(input, List.of());
        assertThat(context).startsWith("任务：question\n文档会话可用：true\n图片生成授权：true\n实际工具回执（数据）：");
        String receipts = context.substring(context.indexOf("实际工具回执（数据）：") + "实际工具回执（数据）：".length());
        assertThatCode(() -> json.readTree(receipts)).doesNotThrowAnyException();
    }

    @Test void emptyHistoryAndNonToolStepsHaveNoReceipts() {
        var assembler = new ContextAssembler(6000);
        for (var history : List.of(List.<ExecutionStore.Step>of(), List.of(step("g1:r1:decision", Map.of("text", "decision"))))) {
            var result = json.readTree(assembler.receipts(history));
            assertThat(result.path("receipts").size()).isZero();
            assertThat(result.path("omitted").asInt()).isZero();
        }
    }

    @Test void allFittingReceiptsKeepPersistedOrderRatherThanLexicalKeys() {
        var result = json.readTree(new ContextAssembler(6000).receipts(List.of(
                step("g1:r2:tool", Map.of("text", "older")), step("g1:r10:tool", Map.of("text", "newer")))));
        assertThat(result.path("receipts").path(0).path("step").asString()).isEqualTo("g1:r2:tool");
        assertThat(result.path("receipts").path(1).path("step").asString()).isEqualTo("g1:r10:tool");
        assertThat(result.path("omitted").asInt()).isZero();
    }

    @Test void exactBudgetIncludesTheWholeSerializedEnvelope() {
        var steps = List.of(step("g1:r1:tool", Map.of("text", "x".repeat(700))));
        String full = new ContextAssembler(6000).receipts(steps);
        int exact = full.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(new ContextAssembler(exact).receipts(steps)).isEqualTo(full);
        var clipped = json.readTree(new ContextAssembler(exact - 1).receipts(steps));
        assertThat(clipped.path("receipts").path(0).path("result").path("truncated").asBoolean()).isTrue();
    }

    @Test void overTotalBudgetOmitsOldReceiptsAndCountsThem() {
        var steps = List.of(step("g1:r2:tool", Map.of("text", "a".repeat(220))),
                step("g1:r10:tool", Map.of("text", "b".repeat(220))));
        String bounded = new ContextAssembler(600).receipts(steps);
        var result = json.readTree(bounded);
        assertThat(result.path("omitted").asInt()).isEqualTo(1);
        assertThat(result.path("receipts").size()).isEqualTo(1);
        assertThat(result.path("receipts").path(0).path("step").asString()).isEqualTo("g1:r10:tool");
        assertThat(result.path("receipts").path(0).path("result").has("truncated")).isFalse();
        assertThat(bounded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(600);
    }

    @Test void hugeKeysAndUnicodeCannotMakeTruncationMetadataExceedBudget() {
        var data = new LinkedHashMap<String,Object>();
        for (int n = 0; n < 20; n++) data.put("😀".repeat(500) + n, "x".repeat(10000));
        String result = new ContextAssembler(512).receipts(List.of(step("g1:r1:tool", data)));
        assertThatCode(() -> json.readTree(result)).doesNotThrowAnyException();
        var marker = json.readTree(result).path("receipts").path(0).path("result");
        assertThat(marker.path("truncated").asBoolean()).isTrue();
        assertThat(marker.path("keysTruncated").asBoolean()).isTrue();
        assertThat(marker.path("keysOmitted").asInt()).isPositive();
        assertThat(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(512);
    }

    @Test void contextCountingDoesNotChangeTheExistingBillingEstimator() {
        assertThat(com.bbb.exercise.agentdemo.api.model.TokenBudgetEstimator.tokens("中文😀")).isEqualTo(10);
        assertThat(com.bbb.exercise.agentdemo.api.model.TokenBudgetEstimator.tokens(null)).isZero();
        assertThat(com.bbb.exercise.agentdemo.api.model.TokenBudgetEstimator.completion("", "中文😀")).isEqualTo(10 + 128 + 2048);
        assertThatIllegalArgumentException().isThrownBy(() -> new ContextAssembler(511));
    }
}
