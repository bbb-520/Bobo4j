package com.bbb.exercise.agentdemo1_0.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class AuthExceptionHandler {
    @ExceptionHandler(AuthService.AuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AuthService.AuthException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("code", exception.status(), "message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", 400, "message", exception.getMessage()));
    }
}
