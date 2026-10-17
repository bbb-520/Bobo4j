package com.bbb.exercise.agentdemo.chatservice.conversation;

import java.util.Objects;

/** One eligible persisted message; a complete attempt may contain several such messages. */
public record ChatTurn(String role, String content) {
    public ChatTurn {
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("历史消息角色无效");
        }
        Objects.requireNonNull(content, "历史消息内容不能为空");
    }
}
