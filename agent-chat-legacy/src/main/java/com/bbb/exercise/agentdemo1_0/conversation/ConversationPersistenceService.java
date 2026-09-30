package com.bbb.exercise.agentdemo1_0.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo1_0.utils.ConversationKeys;
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

    public ConversationPersistenceService(ConversationMapper conversationMapper,
                                           ChatMessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

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
        ConversationEntity entity = conversationMapper.selectOne(Wrappers.<ConversationEntity>lambdaQuery()
                .eq(ConversationEntity::getTenantId, identity.tenantId())
                .eq(ConversationEntity::getUserId, identity.userId())
                .eq(ConversationEntity::getConversationId, conversationId)
                .eq(ConversationEntity::getStatus, 1));
        if (entity == null) {
            // 前端会在 localStorage 中先生成 UUID，再把它作为首次请求的 sessionId。
            // 只有当该 UUID 已被其它用户占用时才拒绝，新的合法 UUID 直接建立归属记录。
            ConversationEntity occupied = conversationMapper.selectOne(Wrappers.<ConversationEntity>lambdaQuery()
                    .eq(ConversationEntity::getConversationId, conversationId));
            if (occupied != null) {
                throw new ConversationRequestException(404, "会话不存在或不属于当前用户");
            }
            return createConversation(conversationId, identity);
        }
        return new ConversationSession(entity.getId(), entity.getConversationId(), identity, false);
    }

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

    @Transactional
    public void appendUserMessage(ConversationSession session, String content) {
        insertMessage(session, "user", content, true);
    }

    @Transactional
    public void appendAssistantMessage(ConversationSession session, String content, boolean completed) {
        if (content == null || content.isBlank()) return;
        insertMessage(session, "assistant", content, completed);
    }

    /** 在同一个事务中保存一轮用户消息和服务端确认消息，避免只写入一半。 */
    @Transactional
    public void appendTurn(ConversationSession session, String userContent,
                           String assistantContent, boolean assistantCompleted) {
        insertMessage(session, "user", userContent, true);
        if (assistantContent != null && !assistantContent.isBlank()) {
            insertMessage(session, "assistant", assistantContent, assistantCompleted);
        }
    }

    private void insertMessage(ConversationSession session, String role, String content, boolean completed) {
        if (session == null) throw new IllegalArgumentException("会话不能为空");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("消息内容不能为空");
        ChatMessageEntity message = new ChatMessageEntity();
        message.setConversationDbId(session.databaseId());
        message.setRole(role);
        message.setContent(content);
        message.setCompleted(completed ? 1 : 0);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        ConversationEntity update = new ConversationEntity();
        update.setId(session.databaseId());
        update.setUpdatedAt(message.getCreatedAt());
        conversationMapper.updateById(update);
        log.info("[conversation] message_saved conversationId={} role={} receivedAt={} completedAt={} completed={}",
                session.conversationId(), role, completed ? null : message.getCreatedAt(),
                completed ? message.getCreatedAt() : null, completed);
    }

    public static final class ConversationRequestException extends RuntimeException {
        private final int status;
        public ConversationRequestException(int status, String message) {
            super(message);
            this.status = status;
        }
        public int status() { return status; }
    }
}
