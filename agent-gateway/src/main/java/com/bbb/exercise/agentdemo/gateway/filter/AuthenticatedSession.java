package com.bbb.exercise.agentdemo.gateway.filter;

/** Identity returned by the Auth session boundary after cookie validation. */
public record AuthenticatedSession(String userId, String tenantId) {
    public AuthenticatedSession {
        if (userId == null || userId.isBlank() || tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("认证会话身份不能为空");
        }
    }
}
