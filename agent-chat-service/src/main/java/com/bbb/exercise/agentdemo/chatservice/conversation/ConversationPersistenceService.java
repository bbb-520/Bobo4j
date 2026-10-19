package com.bbb.exercise.agentdemo.chatservice.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.chatservice.utils.ConversationKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 会话归属和长期消息存储。 */
@Service
@Slf4j
public class ConversationPersistenceService {
    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final int historyTokenBudget;

    public ConversationPersistenceService(
            ConversationMapper conversationMapper, ChatMessageMapper messageMapper) {
        this(conversationMapper, messageMapper, ConversationHistory.DEFAULT_TOKEN_BUDGET);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationPersistenceService(
            ConversationMapper conversationMapper, ChatMessageMapper messageMapper,
            @org.springframework.beans.factory.annotation.Value("${app.chat.history-token-limit:96000}") int historyTokenBudget) {
        if (historyTokenBudget < 0) throw new IllegalArgumentException("历史预算不能为负数");
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.historyTokenBudget = historyTokenBudget;
    }

    // 1. 会话归属：复用当前用户的有效会话，或为尚未占用的合法 ID 建立归属。
    @Transactional
    public ConversationSession openOrCreate(String requestedId, ChatIdentity identity) {
        final String conversationId;

        try {
            conversationId = ConversationKeys.requireExistingOrNull(requestedId);
        } catch (IllegalArgumentException e) {
            throw new ConversationRequestException(400, e.getMessage());
        }

        if (conversationId == null) {
            return createConversation(ConversationKeys.newConversationId(), identity);
        }

        ConversationEntity entity =
                conversationMapper.selectOne(
                        Wrappers.<ConversationEntity>lambdaQuery()
                                .eq(ConversationEntity::getTenantId, identity.tenantId())
                                .eq(ConversationEntity::getUserId, identity.userId())
                                .eq(ConversationEntity::getConversationId, conversationId)
                                .eq(ConversationEntity::getStatus, 1));

        if (entity == null) {
            // 前端会在 localStorage 中先生成 UUID，再把它作为首次请求的 sessionId。
            // 只有当该 UUID 已被其它用户占用时才拒绝，新的合法 UUID 直接建立归属记录。
            // 1.1. 占用检查：未查到当前用户会话时，仍需排除 ID 已属于其他用户的情况。
            ConversationEntity occupied =
                    conversationMapper.selectOne(
                            Wrappers.<ConversationEntity>lambdaQuery()
                                    .eq(ConversationEntity::getConversationId, conversationId));

            if (occupied != null) {
                throw new ConversationRequestException(404, "会话不存在或不属于当前用户");
            }

            return createConversation(conversationId, identity);
        }

        return new ConversationSession(entity.getId(), entity.getConversationId(), identity, false);
    }

    // 1.2. 新建会话：同时保存租户、用户和会话 ID，作为后续消息写入的归属依据。
    private ConversationSession createConversation(String conversationId, ChatIdentity identity) {
        ConversationEntity entity = new ConversationEntity();

        entity.setTenantId(identity.tenantId());
        entity.setUserId(identity.userId());
        entity.setConversationId(conversationId);
        entity.setTitle("新会话");
        entity.setStatus(1);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        conversationMapper.insert(entity);

        return new ConversationSession(entity.getId(), entity.getConversationId(), identity, true);
    }

    // 2. 消息追加：用户消息视为已提交，助手消息额外记录生成是否完成。
    @Transactional
    public void appendUserMessage(ConversationSession session, String content) {
        appendUserMessage(session, content, null);
    }

    @Transactional
    public void appendUserMessage(ConversationSession session, String content, String attemptId) {
        insertMessage(session, "user", content, true, attemptId);
    }

    @Transactional
    public void appendAssistantMessage(
            ConversationSession session, String content, boolean completed) {
        appendAssistantMessage(session, content, completed, null);
    }

    @Transactional
    public void appendAssistantMessage(
            ConversationSession session, String content, boolean completed, String attemptId) {
        if (content == null || content.isBlank()) {
            return;
        }

        insertMessage(session, "assistant", content, completed, attemptId);
    }

    // 2.1. 成对消息：同一事务保存用户输入与助手确认，避免只写入半轮。
    /** 在同一个事务中保存一轮用户消息和服务端确认消息，避免只写入一半。 */
    @Transactional
    public void appendTurn(
            ConversationSession session,
            String userContent,
            String assistantContent,
            boolean assistantCompleted) {
        appendTurn(session, userContent, assistantContent, assistantCompleted, java.util.UUID.randomUUID().toString());
    }

    @Transactional
    public void appendTurn(ConversationSession session, String userContent, String assistantContent,
            boolean assistantCompleted, String attemptId) {
        insertMessage(session, "user", userContent, true, attemptId);

        if (assistantContent != null && !assistantContent.isBlank()) {
            insertMessage(session, "assistant", assistantContent, assistantCompleted, attemptId);
        }
    }

    // 2.2. 消息落库：保存内容和完成状态，同时更新会话的最近活动时间。
    private void insertMessage(
            ConversationSession session, String role, String content, boolean completed, String attemptId) {
        if (session == null) {
            throw new IllegalArgumentException("会话不能为空");
        }

        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }

        ChatMessageEntity message = new ChatMessageEntity();

        message.setConversationDbId(session.databaseId());
        message.setAttemptId(attemptId);
        message.setRole(role);
        message.setContent(content);
        message.setCompleted(completed ? 1 : 0);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        ConversationEntity update = new ConversationEntity();

        update.setId(session.databaseId());
        update.setUpdatedAt(message.getCreatedAt());
        conversationMapper.updateById(update);
        log.info(
                "[conversation] message_saved conversationId={} role={} receivedAt={} completedAt={} completed={}",
                session.conversationId(),
                role,
                completed ? null : message.getCreatedAt(),
                completed ? message.getCreatedAt() : null,
                completed);
    }

    /** 会话请求业务异常，携带用于区分无效请求和权限失败的 HTTP 状态。 */
    public static final class ConversationRequestException extends RuntimeException {
        private final int status;

        public ConversationRequestException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    // 3. 兼容文本入口：历史选择统一委托完整轮次策略。
    public String committedHistory(ConversationSession session) {
        return ConversationHistory.render(recentTurns(session, historyTokenBudget));
    }

    /** New calls share the runtime budget; existing durable inputs must never be reselected. */
    public String committedHistory(
            ConversationSession session, String system, String question, int imageCount, Long tokenBudget) {
        int budget = ConversationHistory.budgetForCall(
                historyTokenBudget, system, question, imageCount, tokenBudget);
        return ConversationHistory.render(recentTurns(session, budget));
    }

    /** Returns a recent whole-attempt suffix; tokenBudget is a conservative UTF-8 byte bound. */
    public java.util.List<ChatTurn> recentTurns(ConversationSession session, int tokenBudget) {
        if (tokenBudget < 0) throw new IllegalArgumentException("历史预算不能为负数");
        if (session == null || tokenBudget == 0) return java.util.List.of();
        var messages = messageMapper.selectList(
                        Wrappers.<ChatMessageEntity>lambdaQuery()
                                .eq(ChatMessageEntity::getConversationDbId, session.databaseId())
                                .in(ChatMessageEntity::getRole, "user", "assistant")
                                .orderByDesc(ChatMessageEntity::getCreatedAt)
                                .orderByDesc(ChatMessageEntity::getId)
                                .last("LIMIT " + ConversationHistory.CANDIDATE_LIMIT));
        return ConversationHistory.select(messages, tokenBudget);
    }
}
