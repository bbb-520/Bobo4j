package com.bbb.exercise.agentdemo1_0.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话请求参数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /** 用户的问题；仅上传图片并等待下一轮指令时可以为空。 */
    private String question;

    /**
     * 会话 id，用于串联多轮对话记忆。
     * 不传或传空白时由服务端创建新会话；继续会话时必须原样传回服务端上次返回的 conversationId。
     * 非服务端生成的值会被拒绝，不会被清洗或静默改写。
     */
    private String sessionId;

    /** 已经直传到 OSS 的图片资产，不在聊天请求中携带二进制。 */
    private List<ChatAttachmentRequest> attachments;
}
