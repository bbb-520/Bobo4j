package com.bbb.exercise.agentdemo.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    private String question;
    private String sessionId;
    private List<ChatAttachmentRequest> attachments;
}
