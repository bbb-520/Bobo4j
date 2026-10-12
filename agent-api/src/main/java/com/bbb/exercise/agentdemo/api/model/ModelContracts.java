package com.bbb.exercise.agentdemo.api.model;

import com.bbb.exercise.agentdemo.api.dto.ChatAttachmentRequest;
import java.util.List;

/** Stable, provider-neutral results. Token counts always represent measured provider usage. */
public final class ModelContracts {
    private ModelContracts() {}

    /** 文本或视觉生成请求，明确系统指令、用户输入、会话和 Token 预算。 */
    public record CompletionRequest(
            String callId,
            String mode,
            String system,
            String user,
            String conversationId,
            Long tokenBudget,
            List<ChatAttachmentRequest> attachments) {
        public CompletionRequest(
                String callId, String mode, String system, String user, String conversationId) {
            this(callId, mode, system, user, conversationId, null, null);
        }

        public CompletionRequest(
                String callId,
                String mode,
                String system,
                String user,
                String conversationId,
                Long tokenBudget) {
            this(callId, mode, system, user, conversationId, tokenBudget, null);
        }
    }

    /** Owned image identity is stable; the resolved URL may expire or change between retries. */
    public record VisionImage(String assetId, String url) {}

    /** 模型生成的统一结果，携带正文、实际模型、回退标记和用量核算信息。 */
    public record Completion(
            String text,
            long inputTokens,
            long outputTokens,
            String model,
            boolean fallback,
            long reservedTokens,
            long unknownTokens,
            String provider) {
        public Completion(
                String text, long inputTokens, long outputTokens, String model, boolean fallback) {
            this(
                    text,
                    inputTokens,
                    outputTokens,
                    model,
                    fallback,
                    Math.addExact(inputTokens, outputTokens),
                    0,
                    null);
        }

        public Completion(
                String text,
                long inputTokens,
                long outputTokens,
                String model,
                boolean fallback,
                long reservedTokens,
                long unknownTokens) {
            this(
                    text,
                    inputTokens,
                    outputTokens,
                    model,
                    fallback,
                    reservedTokens,
                    unknownTokens,
                    null);
        }
    }

    /** 一批文本的向量化结果，通过模型指纹标识向量所属的嵌入空间。 */
    public record EmbeddingBatch(
            List<List<Float>> vectors, long inputTokens, String modelFingerprint) {
        public EmbeddingBatch(List<List<Float>> vectors, long inputTokens) {
            this(vectors, inputTokens, null);
        }
    }

    /** 重排后的单个候选项，以原始候选下标关联评分。 */
    public record Ranked(int index, double score) {}

    /** 候选重排结果，汇总排序项及本次调用的输入 Token 用量。 */
    public record Ranking(List<Ranked> results, long inputTokens) {}

    /** 模型生成事件，通过事件类型和生成版本传递增量、替换及提交信息。 */
    public record GenerationEvent(
            String type, int generationVersion, java.util.Map<String, Object> payload) {}
}
