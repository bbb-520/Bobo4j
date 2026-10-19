package com.bbb.exercise.agentdemo.chatservice.utils;

import org.springframework.util.StringUtils;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** 会话标识工具，生成并校验服务端会话 UUID。 */
public final class ConversationKeys {
    private static final Pattern UUID_PATTERN =
            Pattern.compile(
                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    private ConversationKeys() {}

    public static String newConversationId() {
        return UUID.randomUUID().toString();
    }

    public static String requireExistingOrNull(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }

        if (!UUID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("sessionId 必须是服务端返回的完整 UUID");
        }

        return id.toLowerCase(Locale.ROOT);
    }

}
