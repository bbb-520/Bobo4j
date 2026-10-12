package com.bbb.exercise.agentdemo.auth;

import com.bbb.exercise.agentdemo.auth.billing.BillingException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 认证接口异常转换器，将认证失败及无效输入映射为统一 HTTP 错误响应。 */
@RestControllerAdvice
public class AuthExceptionHandler {
    @ExceptionHandler(BillingException.class)
    public ResponseEntity<Map<String, Object>> handleBilling(BillingException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("code", exception.getStatusCode().value(),
                        "message", exception.getReason() == null ? "支付或计费暂不可用" : exception.getReason()));
    }

    @ExceptionHandler(AuthService.AuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AuthService.AuthException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("code", exception.status(), "message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", 400, "message", exception.getMessage()));
    }
}
