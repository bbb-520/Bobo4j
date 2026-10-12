package com.bbb.exercise.agentdemo.runtime.security;

import reactor.core.publisher.Mono;
import java.util.Set;

/** Only Auth or its signed internal session response can supply this identity. */
public interface BrowserIdentityProvider {
    String USER_ATTRIBUTE = BrowserIdentityProvider.class.getName() + ".user";

    Mono<BrowserUser> session(String token);

    /** 浏览器认证后的用户快照，将用户与租户及当前角色权限绑定。 */
    record BrowserUser(String userId, String tenantId, Set<String> roles, Set<String> permissions) {
        public BrowserUser {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }
}
