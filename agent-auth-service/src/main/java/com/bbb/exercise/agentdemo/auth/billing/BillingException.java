package com.bbb.exercise.agentdemo.auth.billing;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

public class BillingException extends ResponseStatusException {
    public BillingException(int status, String message) { super(HttpStatusCode.valueOf(status), message); }
}
