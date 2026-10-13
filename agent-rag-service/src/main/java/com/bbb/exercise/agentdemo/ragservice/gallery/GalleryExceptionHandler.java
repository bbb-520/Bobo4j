package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import java.io.IOException;
import java.util.Map;

@RestControllerAdvice(assignableTypes=GalleryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class GalleryExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error","GALLERY_REQUEST_INVALID","message",e.getMessage())); }
    @ExceptionHandler(GalleryNotReadyException.class)
    ResponseEntity<Map<String,String>> unavailable(GalleryNotReadyException e) { return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error","GALLERY_NOT_READY","message",e.getMessage())); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String,String>> provider(IllegalStateException e) { return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error","GALLERY_PROVIDER_FAILURE","message","Gallery provider operation failed; verify model and Milvus configuration")); }
    @ExceptionHandler(IOException.class)
    ResponseEntity<Map<String,String>> source(IOException e) { return ResponseEntity.badRequest().body(Map.of("error","GALLERY_SOURCE_INVALID","message","Cannot read the configured gallery checkout; check PROMPT_GALLERY_SOURCE_ROOT and pinned source commit")); }
}
