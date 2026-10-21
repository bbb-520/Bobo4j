package com.bbb.exercise.agentdemo.api.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 聊天入口请求，携带用户问题、会话标识和已上传的图片附件。 */
@Data
@NoArgsConstructor
public class ChatRequest {
    private String question;
    private String sessionId;
    private List<ChatAttachmentRequest> attachments;
    /** Per-request user choice from the UI/API, never inferred from a model verdict. */
    private Boolean imageGenerationConfirmed;
    /**
     * 客户端幂等键（可选，UUID）。
     *
     * <p>旧客户端不传该字段，行为保持不变；新客户端传入后，同一键 + 同一参数的重试会命中同一图片
     * 任务，不会产生第二次付费生成。非空但非 UUID 的键会被明确拒绝，避免静默退化成“无键请求”。
     */
    private String requestId;

    public ChatRequest(
            String question,
            String sessionId,
            List<ChatAttachmentRequest> attachments,
            Boolean imageGenerationConfirmed) {
        this.question = question;
        this.sessionId = sessionId;
        this.attachments = attachments;
        this.imageGenerationConfirmed = imageGenerationConfirmed;
    }

    public ChatRequest(String question, String sessionId, List<ChatAttachmentRequest> attachments) {
        this(question, sessionId, attachments, false);
    }
}
