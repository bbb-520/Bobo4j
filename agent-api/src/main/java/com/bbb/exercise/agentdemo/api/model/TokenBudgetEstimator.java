package com.bbb.exercise.agentdemo.api.model;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Conservative byte upper bounds, never reported as measured provider token usage. */
public final class TokenBudgetEstimator {
    private TokenBudgetEstimator() {}

    public static final int MAX_COMPLETION_OUTPUT = 2048;

    // 1. 文本预算估计：为输入和输出设置保守上界，供派发前准入使用。
    public static long completion(String mode, String system, String user, boolean fallback) {
        return completion(
                ModelPrompts.forMode(mode, fallback) + "\n" + (system == null ? "" : system), user);
    }

    public static long generation(String mode, String system, String user, boolean fallback) {
        return generation(mode, system, user, fallback, "VISION".equals(mode) ? 1 : 0);
    }

    public static long generation(
            String mode, String system, String user, boolean fallback, int imageCount) {
        if (imageCount < 0) {
            throw new IllegalArgumentException("图片数量无效");
        }

        return Math.addExact(
                completion(mode, system, user, fallback),
                "VISION".equals(mode) ? Math.multiplyExact(16386L, imageCount) : 0);
    }

    public static long completion(String trustedSystem, String user) {
        return Math.addExact(
                Math.addExact(bytes(trustedSystem), bytes(user)), 128L + MAX_COMPLETION_OUTPUT);
    }

    // 2. 向量预算估计：按输入文本量估计 Embedding 消耗上界。
    public static long embedding(List<String> texts) {
        long bound = 32;

        for (String text : texts) {
            bound = Math.addExact(bound, Math.addExact(bytes(text), 16));
        }

        return bound;
    }

    // 3. 排序预算估计：查询和候选文本共同计入计划用量。
    public static long rerank(String query, List<String> documents) {
        long bound = 32;

        for (String document : documents) {
            bound =
                    Math.addExact(
                            bound, Math.addExact(Math.addExact(bytes(query), bytes(document)), 64));
        }

        return bound;
    }

    private static long bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Conservative text-only bound for context selection, not measured usage or a price. */
    public static long tokens(String text) {
        return bytes(text);
    }
}
