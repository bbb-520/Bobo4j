package com.bbb.exercise.agentdemo.api.rag;

import java.util.List;
import java.util.Map;

/** Unwrapped transport values; identity is resolved from trusted HTTP context. */
public final class RagContracts {
    private RagContracts() {}

    /** 答案引用的来源视图，定位文档、分块、页码和引用摘录。 */
    public record Source(
            String id,
            String documentId,
            String chunkId,
            String title,
            Integer page,
            String excerpt,
            String kind) {
        public Source(String id, String documentId, String chunkId, String title, Integer page, String excerpt) {
            this(id, documentId, chunkId, title, page, excerpt, "DOCUMENT");
        }
    }

    /** 检索得到的证据片段，保留索引版本、来源顺序及相关性得分。 */
    public record Evidence(
            String chunkId,
            String documentId,
            int indexVersion,
            String text,
            String title,
            Integer page,
            int sourceOrdinal,
            double score) {}

    /** 文档检索请求，在指定会话范围内按问题和预算寻找证据。 */
    public record RetrieveRequest(
            String conversationId, String question, String callId, Long tokenBudget) {
        public RetrieveRequest(String conversationId, String question) {
            this(conversationId, question, null, null);
        }

        public RetrieveRequest(String conversationId, String question, String callId) {
            this(conversationId, question, callId, null);
        }
    }

    /** 文档检索结果，汇总证据、降级状态及实际和预留 Token 用量。 */
    public record Retrieval(
            String query,
            List<Evidence> evidence,
            boolean degraded,
            long inputTokens,
            long outputTokens,
            long budgetTokens,
            long unknownReservedTokens) {
        public Retrieval(String query, List<Evidence> evidence, boolean degraded) {
            this(query, evidence, degraded, 0, 0, 0, 0);
        }

        public Retrieval(
                String query, List<Evidence> evidence, boolean degraded, long input, long output) {
            this(query, evidence, degraded, input, output, input + output, 0);
        }
    }

    /** 文档问答请求，以调用号绑定问题、文档会话及预算。 */
    public record AnswerRequest(
            String callId, String conversationId, String question, Long tokenBudget, ImageEvidence imageEvidence) {
        public AnswerRequest(String callId, String conversationId, String question, Long tokenBudget) {
            this(callId, conversationId, question, tokenBudget, null);
        }
        public AnswerRequest(String callId, String conversationId, String question) {
            this(callId, conversationId, question, null, null);
        }
    }

    /** 由已鉴权图片调用产生的观察；与文档证据分别标注，不作为用户问题执行。 */
    public record ImageEvidence(String text, List<String> assetIds) {}

    /** 经过证据评估的文档答案，包含来源、评估结论及模型与用量信息。 */
    public record Answer(
            String text,
            List<Source> sources,
            String evaluation,
            boolean degraded,
            long inputTokens,
            long outputTokens,
            long budgetTokens,
            long unknownReservedTokens,
            String provider,
            String model,
            boolean fallback) {
        public Answer(
                String text,
                List<Source> sources,
                String evaluation,
                boolean degraded,
                long input,
                long output) {
            this(
                    text,
                    sources,
                    evaluation,
                    degraded,
                    input,
                    output,
                    input + output,
                    0,
                    null,
                    null,
                    false);
        }

        public Answer(
                String text,
                List<Source> sources,
                String evaluation,
                boolean degraded,
                long input,
                long output,
                long budget,
                long unknown) {
            this(
                    text,
                    sources,
                    evaluation,
                    degraded,
                    input,
                    output,
                    budget,
                    unknown,
                    null,
                    null,
                    false);
        }
    }

    /** 文档处理状态视图，展示索引、摘要、分块进度和失败原因。 */
    public record DocumentView(
            String documentId,
            String filename,
            String status,
            String indexStatus,
            String summaryStatus,
            int chunkCount,
            int completedChunks,
            String error,
            int indexVersion,
            String updatedAt) {}

    /** 文档摘要视图，展示生成状态、摘要正文及覆盖情况。 */
    public record SummaryView(String status, String text, double coverage) {}

    /** 创建文档会话的请求，指定本次问答允许使用的文档集合。 */
    public record ConversationRequest(List<String> documentIds) {}

    /** 文档会话快照，固定文档及索引版本并返回历史问答。 */
    public record ConversationView(
            String conversationId,
            List<String> documentIds,
            Map<String, Integer> indexVersions,
            List<History> history) {}

    /** 文档会话中的一轮问答记录，以调用号关联问题和最终答案。 */
    public record History(String callId, String question, Answer answer) {}
}
