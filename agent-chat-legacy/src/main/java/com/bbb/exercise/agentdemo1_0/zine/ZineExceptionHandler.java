package com.bbb.exercise.agentdemo1_0.zine;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Converts provider/configuration errors into a stable JSON API response. */
@RestControllerAdvice
public class ZineExceptionHandler {

    @ExceptionHandler(ZineProviderException.class)
    public ResponseEntity<Map<String, Object>> handleProvider(ZineProviderException exception) {
        return ResponseEntity.status(502).body(Map.of("code", 502, "message", exception.getMessage()));
    }

}
