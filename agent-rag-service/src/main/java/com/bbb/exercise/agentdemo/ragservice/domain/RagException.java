package com.bbb.exercise.agentdemo.ragservice.domain;
import org.springframework.http.HttpStatus;
public class RagException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public RagException(String code, HttpStatus status) { super(code); this.code=code; this.status=status; }
    public String code() { return code; }
    public HttpStatus status() { return status; }
}
