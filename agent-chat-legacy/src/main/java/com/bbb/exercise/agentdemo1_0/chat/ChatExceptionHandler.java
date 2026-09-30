package com.bbb.exercise.agentdemo1_0.chat;

import com.bbb.exercise.agentdemo1_0.conversation.ConversationPersistenceService.ConversationRequestException;
import com.bbb.exercise.agentdemo1_0.auth.AuthService.AuthException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 在 SSE 响应开始前把非法会话请求转换为明确的 HTTP 错误。 */
@RestControllerAdvice
public class ChatExceptionHandler {

    @ExceptionHandler(ConversationRequestException.class)
    public ResponseEntity<Map<String, Object>> handleConversationRequest(ConversationRequestException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("code", exception.status(), "message", messageOrDefault(exception, "会话请求无效")));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AuthException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("code", exception.status(), "message", messageOrDefault(exception, "认证失败")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", 400, "message", exception.getMessage() == null ? "请求参数错误" : exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConfigurationError(IllegalStateException exception) {
        return ResponseEntity.status(503)
                .body(Map.of("code", 503, "message", messageOrDefault(exception, "服务配置错误")));
    }

    private static String messageOrDefault(RuntimeException exception, String fallback) {
        return exception.getMessage() == null || exception.getMessage().isBlank() ? fallback : exception.getMessage();
    }
}
