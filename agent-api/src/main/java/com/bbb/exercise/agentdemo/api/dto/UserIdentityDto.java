package com.bbb.exercise.agentdemo.api.dto;

/** 跨服务传递的用户身份视图，包含租户、认证状态及角色权限快照。 */
public record UserIdentityDto(
        String userId,
        String tenantId,
        boolean authenticated,
        String username,
        java.util.Set<String> roles,
        java.util.Set<String> permissions) {
    public UserIdentityDto(String id, String tenant, boolean authenticated) {
        this(id, tenant, authenticated, null, java.util.Set.of(), java.util.Set.of());
    }
}
