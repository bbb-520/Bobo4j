package com.bbb.exercise.agentdemo1_0.chat;

import com.bbb.exercise.agentdemo1_0.conversation.ConversationPersistenceService;
import com.bbb.exercise.agentdemo1_0.conversation.ConversationSession;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.common.client.InternalServiceClient;
import com.bbb.exercise.agentdemo1_0.memory.VisionMemoryService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatServicePersistenceLifecycleTest {
    @Test
    void cancellationPersistsPartialAssistantAsIncomplete() {
        var conversations = mock(ConversationPersistenceService.class);
        var model = mock(ModelConversationService.class);
        when(model.answer(any(), eq("hello"), eq("cid"))).thenReturn(Flux.just("partial", "tail"));
        var service = new ChatService(conversations, mock(InternalServiceClient.class), model, mock(VisionMemoryService.class));
        var session = new ConversationSession(1L, "cid", new ChatIdentity("tenant", "user:1", true), false);

        service.chat("hello", session, List.of()).take(2).blockLast();

        verify(conversations, timeout(2000)).appendUserMessage(session, "hello");
        verify(conversations, timeout(2000)).appendAssistantMessage(session, "partial", false);
        verify(conversations, never()).appendTurn(any(), anyString(), anyString(), eq(true));
    }
}
