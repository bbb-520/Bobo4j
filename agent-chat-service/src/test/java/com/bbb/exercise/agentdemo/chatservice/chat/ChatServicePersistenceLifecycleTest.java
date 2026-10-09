package com.bbb.exercise.agentdemo.chatservice.chat;

import com.bbb.exercise.agentdemo.chatservice.conversation.ConversationPersistenceService;
import com.bbb.exercise.agentdemo.chatservice.conversation.ConversationSession;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import com.bbb.exercise.agentdemo.chatservice.memory.VisionMemoryService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ChatServicePersistenceLifecycleTest {
    @Test void fallbackReplacesPrimaryBodyAndCommitsOnlyTheWinningAnswer() {
        var conversations=new RecordingConversations();
        var model=new ModelConversationService(null,null,null,null){
            @Override public Flux<com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent> answerEvents(ChatIdentity identity,String prompt,String cid){
                return Flux.just(event("answer_delta",1,"primary"),event("fallback_started",2,""),event("answer_replace",2,"backup"),event("answer_delta",2," tail"),event("answer_committed",2,"backup tail"));
            }
            private com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent event(String type,int version,String text){return new com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent(type,version,java.util.Map.of("text",text));}
        };
        var service=new ChatService(conversations,null,model,null);
        var events=service.chat("hello",new ConversationSession(1L,"cid",new ChatIdentity("t","u",true),false),List.of()).collectList().block();
        assertThat(events).extracting(com.bbb.exercise.agentdemo.chatservice.vo.ChatEventVO::getEventType).containsSequence(1001,1016,1015,1001,1002);
        assertThat(conversations.assistant).isEqualTo("backup tail");assertThat(conversations.completed).isTrue();
    }
    @Test
    void cancellationPersistsPartialAssistantAsIncomplete() throws Exception {
        var conversations = new RecordingConversations();
        var model = new ModelConversationService(null,null,null,null) {@Override public Flux<com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent> answerEvents(ChatIdentity identity,String prompt,String conversationId){return Flux.just(new com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent("answer_delta",1,java.util.Map.of("text","partial")),new com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent("answer_delta",1,java.util.Map.of("text","tail")));}};
        var service = new ChatService(conversations, null, model, null);
        var session = new ConversationSession(1L, "cid", new ChatIdentity("tenant", "user:1", true), false);

        service.chat("hello", session, List.of()).take(2).blockLast();

        assertThat(conversations.saved.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(conversations.user).isEqualTo("hello");assertThat(conversations.assistant).isEqualTo("partial");assertThat(conversations.completed).isFalse();
    }
    static class RecordingConversations extends ConversationPersistenceService {String user,assistant;boolean completed;java.util.concurrent.CountDownLatch saved=new java.util.concurrent.CountDownLatch(1);RecordingConversations(){super(null,null);}@Override public void appendUserMessage(ConversationSession s,String content){user=content;}@Override public void appendAssistantMessage(ConversationSession s,String content,boolean completed){assistant=content;this.completed=completed;saved.countDown();}}
}
