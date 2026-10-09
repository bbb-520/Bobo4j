package com.bbb.exercise.agentdemo.chatservice.chat;

import com.bbb.exercise.agentdemo.chatservice.conversation.ConversationPersistenceService.ConversationRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 在 SSE 响应开始前把非法会话请求转换为明确的 HTTP 错误。 */
@RestControllerAdvice
public class ChatExceptionHandler {
    @ExceptionHandler(com.bbb.exercise.agentdemo.runtime.client.ModelCallClient.ModelCallException.class)
    public ResponseEntity<Map<String,Object>> handleModel(com.bbb.exercise.agentdemo.runtime.client.ModelCallClient.ModelCallException exception) {
        int status=exception.status()==409?409:exception.status()==400?400:exception.status()==402?402:exception.status()==429?429:503;
        return ResponseEntity.status(status).body(Map.of("code",exception.code(),"message",exception.getMessage()));
    }

    @ExceptionHandler(ConversationRequestException.class)
    public ResponseEntity<Map<String, Object>> handleConversationRequest(ConversationRequestException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("code", exception.status(), "message", messageOrDefault(exception, "会话请求无效")));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(IllegalStateException exception) {
        return ResponseEntity.status(401)
                .body(Map.of("code", 401, "message", messageOrDefault(exception, "认证失败")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", 400, "message", exception.getMessage() == null ? "请求参数错误" : exception.getMessage()));
    }

    private static String messageOrDefault(RuntimeException exception, String fallback) {
        return exception.getMessage() == null || exception.getMessage().isBlank() ? fallback : exception.getMessage();
    }
}
