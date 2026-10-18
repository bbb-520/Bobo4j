package com.bbb.exercise.agentdemo.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 聊天入口请求，携带用户问题、会话标识和已上传的图片附件。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    private String question;
    private String sessionId;
    private List<ChatAttachmentRequest> attachments;
    /** Per-request user choice from the UI/API, never inferred from a model verdict. */
    private Boolean imageGenerationConfirmed;

    public ChatRequest(String question, String sessionId, List<ChatAttachmentRequest> attachments) {
        this(question, sessionId, attachments, false);
    }
}
