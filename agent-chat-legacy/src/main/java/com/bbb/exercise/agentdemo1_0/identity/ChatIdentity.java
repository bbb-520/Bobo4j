package com.bbb.exercise.agentdemo1_0.identity;

/** 当前请求的可信会话身份。 */
public record ChatIdentity(String tenantId, String userId, boolean authenticated) {

    public ChatIdentity {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("会话身份不能为空");
        }
    }
}
