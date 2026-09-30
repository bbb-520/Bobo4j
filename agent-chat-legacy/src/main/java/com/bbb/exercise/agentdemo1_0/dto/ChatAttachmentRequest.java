package com.bbb.exercise.agentdemo1_0.dto;

import lombok.Data;

/** 对话中引用的 OSS 图片资产。 */
@Data
public class ChatAttachmentRequest {
    private String assetId;
    private String mimeType;
    private String fileName;
    private Long fileSize;
}
