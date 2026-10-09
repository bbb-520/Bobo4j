package com.bbb.exercise.agentdemo.chatservice.chat;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class InternalChatStreamingBoundaryTest {
    String secrets="current="+Base64.getEncoder().encodeToString(new byte[32]);
    PrincipalKeyRing keys=new PrincipalKeyRing(secrets,"current");
    String signature(String audience,String operation){return keys.sign("agent-orchestrator-service","alice","tenant",audience,operation);}
    @Test void signedStreamReturnsVersionedFramesAndRejectsWrongAudienceOrOperation() {
        var service=new FakeCompletion();var controller=new InternalChatController(service,null,secrets,"current");var http=WebTestClient.bindToController(controller).build();
        var request=new CompletionRequest("call","CHAT","rules","q",null);
        var result=http.post().uri("/internal/chat/stream").header("X-Internal-Principal",signature("agent-chat-service","POST /internal/chat/stream")).bodyValue(request).exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith("text/event-stream").returnResult(GenerationEvent.class).getResponseBody().collectList().block(java.time.Duration.ofSeconds(5));
        assertThat(result).extracting(GenerationEvent::type).containsExactly("answer_delta","answer_committed");assertThat(result.getFirst().generationVersion()).isEqualTo(1);assertThat(service.identity).isEqualTo(new ChatIdentity("tenant","alice",true));
        http.post().uri("/internal/chat/stream").header("X-Internal-Principal",signature("agent-auth-service","POST /internal/chat/stream")).bodyValue(request).exchange().expectStatus().isUnauthorized();
        http.post().uri("/internal/chat/stream").header("X-Internal-Principal",signature("agent-chat-service","POST /internal/chat/complete")).bodyValue(request).exchange().expectStatus().isUnauthorized();
    }
    @Test void subscriberCancellationInterruptsTheBlockingProviderWorker() throws Exception {
        var service=new FakeCompletion();service.wait=true;var http=WebTestClient.bindToController(new InternalChatController(service,null,secrets,"current")).build();
        http.post().uri("/internal/chat/stream").header("X-Internal-Principal",signature("agent-chat-service","POST /internal/chat/stream")).bodyValue(new CompletionRequest("call","CHAT","rules","q",null)).exchange().expectStatus().isOk().returnResult(GenerationEvent.class).getResponseBody().take(1).blockLast(java.time.Duration.ofSeconds(5));
        assertThat(service.cancelled.await(5,TimeUnit.SECONDS)).isTrue();assertThat(service.committed).isFalse();
    }
    static class FakeCompletion extends InternalCompletionService {
        boolean wait,committed;ChatIdentity identity;CountDownLatch cancelled=new CountDownLatch(1);
        FakeCompletion(){super(null,null,null,null);}
        @Override public Completion completeStreaming(ChatIdentity i,CompletionRequest r,java.util.function.Consumer<GenerationEvent> events){identity=i;events.accept(new GenerationEvent("answer_delta",1,Map.of("text","answer")));if(wait)try{Thread.sleep(10000);}catch(InterruptedException e){cancelled.countDown();throw new CancellationException();}var result=new Completion("answer",12,3,"model",false);committed=true;events.accept(com.bbb.exercise.agentdemo.runtime.client.ModelCallClient.committedEvent(result));return result;}
    }
}
