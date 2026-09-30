package com.bbb.exercise.agentdemo1_0.dto;
import lombok.*; import java.util.List;
@Data @NoArgsConstructor @AllArgsConstructor public class ChatRequest { private String question; private String sessionId; private List<ChatAttachmentRequest> attachments; }
