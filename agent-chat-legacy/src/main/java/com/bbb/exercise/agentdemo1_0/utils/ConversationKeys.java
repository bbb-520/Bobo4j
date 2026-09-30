package com.bbb.exercise.agentdemo1_0.utils;

import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 会话 ID 规则。
 *
 * <p>公开 ID 是服务端生成的不透明 UUID。外部输入只允许原样 UUID，绝不做
 * trim、删除空白、截断或自动加前缀；否则不同输入可能被静默合并成同一会话。</p>
 */
public final class ConversationKeys {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    /** Redis 中的内部会话键前缀。公开 conversationId 不带此前缀。 */
    public static final String MEMORY_PREFIX = "chat:";

    private ConversationKeys() {
    }

    /** 服务端为新会话生成不透明 ID。 */
    public static String newConversationId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 严格解析客户端传入的 ID。
     *
     * @return {@code null} 表示创建新会话
     * @throws IllegalArgumentException 非空输入不是完整 UUID
     */
    public static String requireExistingOrNull(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        if (!UUID_PATTERN.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("sessionId 必须是服务端返回的完整 UUID");
        }
        return sessionId.toLowerCase(Locale.ROOT);
    }

    /** Redis ChatMemory 使用的内部键，绑定租户和可信用户身份。 */
    public static String memoryKey(ChatIdentity identity, String conversationId) {
        return MEMORY_PREFIX + identity.tenantId() + ":" + identity.userId() + ":" + conversationId;
    }
}
