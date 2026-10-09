package com.bbb.exercise.agentdemo.ragservice.web;
import com.bbb.exercise.agentdemo.ragservice.domain.RagException;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.Map;
@RestControllerAdvice
public class RagExceptionHandler {
    @ExceptionHandler(RagException.class) public ResponseEntity<Map<String,String>> rag(RagException e){return ResponseEntity.status(e.status()).body(Map.of("code",e.code(),"message",e.code()));}
    @ExceptionHandler(ModelCallClient.ModelCallException.class) public ResponseEntity<Map<String,String>> model(ModelCallClient.ModelCallException e){return ResponseEntity.status(e.status()==409?409:e.status()==429?429:503).body(Map.of("code",e.code(),"message",e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,String>> state(IllegalStateException e){if("请先登录".equals(e.getMessage()))return ResponseEntity.status(401).body(Map.of("code","AUTHENTICATION_REQUIRED","message","请先登录"));return ResponseEntity.status(500).body(Map.of("code","RAG_INTERNAL_ERROR","message","文档服务暂时不可用"));}
}
