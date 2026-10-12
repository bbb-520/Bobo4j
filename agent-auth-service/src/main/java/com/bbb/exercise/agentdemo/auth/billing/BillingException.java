package com.bbb.exercise.agentdemo.auth.billing;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/** 计费业务拒绝异常，向调用方说明余额、状态或请求约束冲突。 */
public class BillingException extends ResponseStatusException {
    public BillingException(int status, String message) {
        super(HttpStatusCode.valueOf(status), message);
    }

    public BillingException(int status, String message, Throwable cause) {
        super(HttpStatusCode.valueOf(status), message, cause);
    }
}
