package com.bbb.exercise.agentdemo.api.identity;

/** Trusted identity value shared by domain services. */
public record ChatIdentity(String tenantId, String userId, boolean authenticated) {
    public ChatIdentity {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("会话身份不能为空");
        }
    }
}
