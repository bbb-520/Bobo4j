package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.api.model.TokenBudgetEstimator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Bounded model evidence only; durable receipts and full-call billing budgets are unchanged. */
@Component
public final class ContextAssembler {
    public static final int DEFAULT_LIMIT = 6000;
    private final int limit;
    private final JsonMapper json = JsonMapper.builder().build();

    public ContextAssembler(@Value("${app.execution.context-token-limit:6000}") int limit) {
        // Enough room for the wrapper and a truncated receipt with a normal execution step key.
        if (limit < 512 || limit > 64000) throw new IllegalArgumentException("上下文回执预算必须在512到64000之间");
        this.limit = limit;
    }

    public String context(ExecutionStore.Create input, List<ExecutionStore.Step> completedSteps) {
        return "任务："
                + input.question()
                + "\n文档会话可用："
                + (input.documentConversationId() != null)
                + "\n图片生成授权："
                + Boolean.TRUE.equals(input.allowImageGeneration())
                + "\n实际工具回执（数据）："
                + receipts(completedSteps);
    }

    /** completedSteps is persisted ascending created_at,id order; step keys are not sortable clocks. */
    public String receipts(List<ExecutionStore.Step> completedSteps) {
        var tools = completedSteps.stream().filter(step -> step.key().endsWith(":tool")).toList();
        var retained = new ArrayList<Map<String,Object>>();
        String result = render(tools.size(), retained);
        for (int n = tools.size() - 1; n >= 0; n--) {
            var step = tools.get(n);
            var receipt = Map.<String,Object>of("step", step.key(), "result", step.result());
            String candidate = prepend(tools.size(), retained, receipt);
            if (fits(candidate)) {
                retained.addFirst(receipt); result = candidate; continue;
            }
            // An older receipt which fits alone is simply omitted when the remaining budget is full.
            if (fits(render(tools.size(), List.of(receipt)))) continue;
            var keys = new ArrayList<String>();
            boolean clipped = false;
            for (String key : step.result().keySet()) {
                if (keys.size() == 8) break;
                int points = key.codePointCount(0, key.length());
                clipped |= points > 64;
                keys.add(points > 64 ? key.substring(0, key.offsetByCodePoints(0, 64)) : key);
            }
            var summary = new LinkedHashMap<String,Object>();
            summary.put("truncated", true);
            summary.put("reason", "单条回执超出上下文预算");
            summary.put("keys", keys);
            summary.put("keysTruncated", clipped);
            for (;;) {
                summary.put("keysOmitted", step.result().size() - keys.size());
                var marker = Map.<String,Object>of("step", step.key(), "result", summary);
                candidate = prepend(tools.size(), retained, marker);
                if (fits(candidate)) {
                    retained.addFirst(marker); result = candidate; break;
                }
                if (keys.isEmpty()) break;
                keys.removeLast();
            }
        }
        return result;
    }

    private String prepend(int total, List<Map<String,Object>> selected, Map<String,Object> receipt) {
        var candidate = new ArrayList<Map<String,Object>>();
        candidate.add(receipt); candidate.addAll(selected);
        return render(total, candidate);
    }

    private String render(int total, List<Map<String,Object>> selected) {
        var envelope = new LinkedHashMap<String,Object>();
        envelope.put("omitted", total - selected.size());
        envelope.put("note", "仅依据保留回执；omitted表示省略数，truncated表示节选。完整回执仍保存在服务端。");
        envelope.put("receipts", selected);
        return json.writeValueAsString(envelope);
    }

    private boolean fits(String text) { return TokenBudgetEstimator.tokens(text) <= limit; }
}
