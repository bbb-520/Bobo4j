package com.bbb.exercise.agentdemo.api.dto;

import lombok.Data;

/** Shared reference to an already-uploaded image asset. */
@Data
public class ChatAttachmentRequest {
    private String assetId;
    private String mimeType;
    private String fileName;
    private Long fileSize;
}
