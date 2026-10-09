package com.bbb.exercise.agentdemo.chatservice.conversation;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConversationPersistenceServiceTest {
    @Test
    void createsOwnedConversationAndPersistsUserTurn() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        ChatMessageMapper messages = mock(ChatMessageMapper.class);
        when(conversations.selectOne(any())).thenReturn(null);
        when(conversations.insert(any(ConversationEntity.class))).thenAnswer(invocation -> {
            invocation.<ConversationEntity>getArgument(0).setId(42L);
            return 1;
        });
        when(messages.insert(any(ChatMessageEntity.class))).thenReturn(1);

        ConversationPersistenceService service = new ConversationPersistenceService(conversations, messages);
        ConversationSession session = service.openOrCreate(null,
                new ChatIdentity("tenant-a", "anonymous:user-a", false));
        service.appendUserMessage(session, "hello");

        assertThat(session.databaseId()).isEqualTo(42L);
        verify(conversations).insert(any(ConversationEntity.class));
        verify(messages).insert(argThat((ChatMessageEntity message) -> "hello".equals(message.getContent())
                && "user".equals(message.getRole()) && message.getConversationDbId() == 42L));
    }
}
