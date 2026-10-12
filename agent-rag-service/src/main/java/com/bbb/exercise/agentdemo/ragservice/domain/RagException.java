package com.bbb.exercise.agentdemo.ragservice.domain;

import org.springframework.http.HttpStatus;

/** RAG 业务异常，通过稳定错误码和 HTTP 状态描述处理失败。 */
public class RagException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public RagException(String code, HttpStatus status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
