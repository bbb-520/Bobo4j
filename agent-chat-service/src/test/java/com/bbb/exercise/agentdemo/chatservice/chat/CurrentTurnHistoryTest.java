package com.bbb.exercise.agentdemo.chatservice.chat;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import com.bbb.exercise.agentdemo.chatservice.conversation.*;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CurrentTurnHistoryTest {
    final ChatIdentity owner = new ChatIdentity("tenant", "user", true);
    final ConversationSession session = new ConversationSession(1L, "conversation", owner, false);
    final History history = new History(session);
    final List<String> modelInputs = Collections.synchronizedList(new ArrayList<>());
    final AtomicBoolean failNext = new AtomicBoolean();

    @SuppressWarnings("unchecked")
    ChatService service() {
        var models = mock(ModelCallClient.class);
        when(models.completeStreaming(eq(owner), anyString(), eq("CHAT"), eq(""), anyString(), eq(true), any()))
                .thenAnswer(call -> {
                    modelInputs.add(call.getArgument(4));
                    Consumer<GenerationEvent> emit = call.getArgument(6);
                    emit.accept(new GenerationEvent("answer_delta", 1, Map.of("text", "partial")));
                    if (failNext.getAndSet(false)) throw new IllegalStateException("provider failed");
                    emit.accept(new GenerationEvent("answer_committed", 1, Map.of("text", "answer")));
                    return new Completion("answer", 5, 1, "test", false);
                });
        var model = new ModelConversationService(null, null, null, null, models, history);
        return new ChatService(history, null, model, null);
    }

    @Test void currentQuestionIsNotReadBackAsItsOwnHistory() {
        service().chat("CURRENT_QUESTION", session, List.of()).blockLast();
        assertThat(modelInputs).hasSize(1);
        assertThat(occurrences(modelInputs.getFirst(), "CURRENT_QUESTION")).isEqualTo(1);
        assertThat(history.lines).containsExactly("user: CURRENT_QUESTION", "assistant: answer");
    }

    @Test void anIdenticalEarlierQuestionIsPreservedInsteadOfTextDeduplication() {
        history.lines.addAll(List.of("user: REPEATED_QUESTION", "assistant: earlier answer"));
        service().chat("REPEATED_QUESTION", session, List.of()).blockLast();
        assertThat(occurrences(modelInputs.getFirst(), "REPEATED_QUESTION")).isEqualTo(2);
        assertThat(modelInputs.getFirst()).contains("earlier answer");
    }

    @Test void repeatedSubscriptionsHaveIndependentFreshHistorySnapshots() {
        var turn = service().chat("SUBSCRIBED_QUESTION", session, List.of());
        turn.blockLast(); turn.blockLast();
        assertThat(modelInputs).hasSize(2);
        assertThat(occurrences(modelInputs.get(0), "SUBSCRIBED_QUESTION")).isEqualTo(1);
        assertThat(occurrences(modelInputs.get(1), "SUBSCRIBED_QUESTION")).isEqualTo(2);
    }

    @Test void failureAndRetryRetainUserMessagesButNeverUsePartialAssistantHistory() {
        var service = service(); failNext.set(true);
        service.chat("RETRY_QUESTION", session, List.of()).blockLast();
        assertThat(history.lastCompleted).isFalse();
        assertThat(history.lastAssistant).isEqualTo("partial");
        service.chat("RETRY_QUESTION", session, List.of()).blockLast();
        assertThat(occurrences(modelInputs.get(0), "RETRY_QUESTION")).isEqualTo(1);
        assertThat(occurrences(modelInputs.get(1), "RETRY_QUESTION")).isEqualTo(2);
        assertThat(modelInputs.get(1)).doesNotContain("assistant: partial");
        assertThat(history.lastCompleted).isTrue();
    }

    int occurrences(String text, String needle) { return (text.length() - text.replace(needle, "").length()) / needle.length(); }

    static final class History extends ConversationPersistenceService {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        final ConversationSession session;
        boolean lastCompleted;
        String lastAssistant;
        History(ConversationSession session) { super(null, null); this.session = session; }
        @Override public ConversationSession openOrCreate(String id, ChatIdentity identity) { return session; }
        @Override public String committedHistory(ConversationSession session) { return String.join("\n", lines); }
        @Override public void appendUserMessage(ConversationSession session, String content) { lines.add("user: " + content); }
        @Override public void appendAssistantMessage(ConversationSession session, String content, boolean completed) {
            lastAssistant=content; lastCompleted=completed;
            if (completed) lines.add("assistant: " + content);
        }
    }
}
