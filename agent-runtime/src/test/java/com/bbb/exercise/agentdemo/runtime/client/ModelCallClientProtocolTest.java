package com.bbb.exercise.agentdemo.runtime.client;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ModelCallClientProtocolTest {
    HttpServer provider; AuthModelClient models; FakeBilling billing; ModelCallClient client;
    ChatIdentity identity = new ChatIdentity("tenant","alice",true); String base;
    AtomicInteger calls = new AtomicInteger(); String response; int status=200; String body; boolean primaryFails,stream;com.sun.net.httpserver.HttpHandler custom;
    @BeforeEach void setup() throws Exception {
        provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/",exchange -> {int attempt=calls.incrementAndGet();body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);if(custom!=null){custom.handle(exchange);return;}byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");exchange.sendResponseHeaders(primaryFails&&attempt==1?503:status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}); provider.start();
        base="http://127.0.0.1:"+provider.getAddress().getPort()+"/v1";
        models=new AuthModelClient(null) {@Override public SelectedModel resolve(ChatIdentity i,ModelCapability c) {return new SelectedModel(ModelProvider.QWEN,c,c==ModelCapability.FALLBACK?"backup":"model","key",base,"operator-key");}};
        billing=new FakeBilling();
        client=new ModelCallClient(models,billing,WebClient.builder());
    }
    @AfterEach void close() {provider.stop(0);}
    @Test void completedReceiptReplaysWithoutProviderDispatch() {
        billing.state=new GroupState("call","RECEIVED","{\"text\":\"cached\",\"inputTokens\":12,\"outputTokens\":3,\"model\":\"model\",\"fallback\":false}","PRIMARY");
        var replayed=client.complete(identity,"call","CHAT","rules","question",false,0L);
        assertThat(replayed.text()).isEqualTo("cached");assertThat(replayed.reservedTokens()).isEqualTo(15);assertThat(replayed.unknownTokens()).isZero();
        assertThat(calls.get()).isZero();
    }
    @Test void validatesEmbeddingCardinalityAndDimensionsRatherThanAcceptingBrokenIndexVectors() {
        response="{\"data\":[{\"index\":0,\"embedding\":[1.0]}],\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}";
        assertThatThrownBy(() -> client.embed(identity,"call",List.of("a","b"),1024)).isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void successfulEmbeddingReceiptPinsTheActuallySelectedConfigurationAndDimensions() {
        response="{\"data\":[{\"index\":0,\"embedding\":"+Collections.nCopies(64,0.1f)+"}],\"usage\":{\"prompt_tokens\":2}}";
        assertThat(client.embed(identity,"embed","a".lines().toList(),64).modelFingerprint()).isEqualTo(ModelFingerprint.embedding("QWEN","model",base,"operator-key",64));
    }
    @Test void rejectsRerankIndicesOutsideTheCandidateSet() {
        response="{\"results\":[{\"index\":9,\"relevance_score\":0.8}],\"usage\":{\"total_tokens\":5}}";
        assertThatThrownBy(() -> client.rerank(identity,"call","q",List.of("a"))).isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void rerankEmitsMeasuredTokensAndADurationMetricWithoutProviderSecretsInLabels() {
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();factory.addBean("metrics",registry);
        client=new ModelCallClient(models,billing,WebClient.builder(),new ModelCircuit(),15,20,60,30,factory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        response="{\"results\":[{\"index\":0,\"relevance_score\":0.8}],\"usage\":{\"total_tokens\":5}}";
        client.rerank(identity,"rank","q",List.of("a"));
        assertThat(registry.get("ai.model.tokens").tag("capability","RERANK").tag("direction","input").counter().count()).isEqualTo(5);
        assertThat(registry.get("ai.model.duration").tag("capability","RERANK").tag("outcome","success").timer().count()).isEqualTo(1);
        assertThat(registry.getMeters().toString()).doesNotContain(base,"operator-key");
    }
    @Test void missingUsageRemainsUnknownAndNeverInventsZeroTokens() {
        response="{\"id\":\"r\",\"choices\":[{\"message\":{\"content\":\"answer\"}}]}";
        assertThatThrownBy(() -> client.complete(identity,"call","CHAT","rules","q",false)).isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void measuredCompletionUsesSeparateSystemAndUserFieldsAndQwenOnlyExtension() {
        response="{\"id\":\"r\",\"choices\":[{\"message\":{\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";
        var result=client.complete(identity,"call","DOCUMENT_QA","trusted rules","untrusted evidence",false);
        assertThat(result.text()).isEqualTo("answer");assertThat(result.inputTokens()).isEqualTo(12);assertThat(result.outputTokens()).isEqualTo(3);
        assertThat(body).contains("trusted rules","untrusted evidence","enable_thinking");assertThat(calls.get()).isEqualTo(1);
    }
    @Test void recoveredDispatchedAttemptDoesNotBlindlyRepeatOrFailOver() {
        billing.state=new GroupState("call","UNKNOWN",null,null);
        assertThatThrownBy(() -> client.complete(identity,"call","CHAT","rules","q",true)).isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isZero();
    }
    @Test void serverFailureFailsOverAtMostOnceWithTheSameStableCallAndFreshPrompt() {
        primaryFails=true;response="{\"id\":\"r\",\"choices\":[{\"message\":{\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";
        var answer=client.complete(identity,"call","DOCUMENT_QA","rules","[S1] evidence",true);
        assertThat(answer.fallback()).isTrue();assertThat(calls.get()).isEqualTo(2);assertThat(body).contains("[S1] evidence","备用","重新生成");assertThat(billing.saved.role()).isEqualTo("FALLBACK");
    }
    @Test void invalidRequestAndSameCredentialAuthenticationFailureNeverSwitchModels() {
        response="{}";status=400;
        assertThatThrownBy(() -> client.complete(identity,"call","CHAT","rules","q",true)).isInstanceOf(ModelCallClient.ModelCallException.class);assertThat(calls.get()).isEqualTo(1);
        calls.set(0);status=401;
        assertThatThrownBy(() -> client.complete(identity,"call","CHAT","rules","q",true)).isInstanceOf(ModelCallClient.ModelCallException.class);assertThat(calls.get()).isEqualTo(1);
    }
    @Test void doubleServerFailureStopsAfterTwoAttemptsAndEvaluationNeverChangesJudge() {
        response="{}";status=503;
        assertThatThrownBy(() -> client.complete(identity,"call","CHAT","rules","q",true)).isInstanceOf(ModelCallClient.ModelCallException.class);assertThat(calls.get()).isEqualTo(2);
        calls.set(0);assertThatThrownBy(() -> client.complete(identity,"eval","EVALUATION","rules","q",true)).isInstanceOf(ModelCallClient.ModelCallException.class);assertThat(calls.get()).isEqualTo(1);
    }
    @Test void streamingUsageFramesNeverBecomeAnswerText() {
        stream=true;response="data: {\"id\":\"r\",\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\ndata: {\"id\":\"r\",\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n";
        var answer=client.complete(identity,"call","CHAT","rules","q",false);assertThat(answer.text()).isEqualTo("answer");assertThat(answer.outputTokens()).isEqualTo(3);
    }
    @Test void cancellationBeforeAdmissionDoesNotDispatchOrSwitch() {
        Thread.currentThread().interrupt();try{assertThatThrownBy(() -> client.complete(identity,"call","CHAT","rules","q",true)).isInstanceOf(ModelCallClient.ModelCallException.class);assertThat(calls.get()).isZero();}finally{Thread.interrupted();}
    }
    @Test void insufficientMoneyBeforeDispatchReleasesUnsentHoldAndNeverFallsBack() {
        billing.attemptError=new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYMENT_REQUIRED);
        assertThatThrownBy(() -> client.complete(identity,"call","CHAT","rules","q",true)).isInstanceOfSatisfying(ModelCallClient.ModelCallException.class,e -> assertThat(e.status()).isEqualTo(402));
        assertThat(calls.get()).isZero();assertThat(billing.cancelled).isTrue();assertThat(billing.unknown).isNull();
    }
    @Test void openCircuitWithoutAuthorizedFallbackReleasesUndispatchedWalletHold() {
        var circuit=new ModelCircuit();var selected=models.resolve(identity,ModelCapability.CHAT);for(int n=0;n<5;n++)circuit.failure(selected);
        client=new ModelCallClient(models,billing,WebClient.builder(),circuit,15,20,60,30);
        assertThatThrownBy(() -> client.complete(identity,"call","CHAT","rules","q",false)).isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(calls.get()).isZero();assertThat(billing.cancelled).isTrue();
    }
    @Test void streamingCommitsOnlyAfterDurableMeasuredReceiptAndSettlement() {
        stream=true;response="data: {\"id\":\"r\",\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\ndata: {\"id\":\"r\",\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n";
        var events=new ArrayList<com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>();
        client.completeStreaming(identity,"call","CHAT","rules","q",true,event -> {if(event.type().equals("answer_committed")){assertThat(billing.saved).isNotNull();assertThat(billing.finished).isTrue();}events.add(event);});
        assertThat(events).extracting(com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent::type).containsExactly("answer_delta","answer_committed");
        assertThat(events.getLast().payload()).containsEntry("text","answer").containsEntry("inputTokens",12L).containsEntry("outputTokens",3L);
    }
    @Test void missingUsageCannotEmitACommittedEvent() {
        response="{\"choices\":[{\"message\":{\"content\":\"partial\"}}]}";var events=new ArrayList<com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>();
        assertThatThrownBy(() -> client.completeStreaming(identity,"call","CHAT","rules","q",true,events::add)).isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(events).extracting(com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent::type).doesNotContain("answer_committed");assertThat(billing.unknown).isEqualTo("PRIMARY");
    }
    @Test void partialPrimaryIdleTimeoutReplacesAnswerWithBackupAndFencesLatePrimaryOutput() {
        client=new ModelCallClient(models,billing,WebClient.builder(),null,1,1,4,3);
        custom=exchange -> {
            if(calls.get()==1){exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"partial-primary\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();try{Thread.sleep(1500);exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"late-primary\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(java.io.IOException ignored){}finally{exchange.close();}}
            else{byte[] bytes="{\"id\":\"b\",\"choices\":[{\"message\":{\"content\":\"backup-answer\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
        };
        var events=new java.util.concurrent.CopyOnWriteArrayList<com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>();
        var result=client.completeStreaming(identity,"call","CHAT","rules","q",true,events::add);
        assertThat(result.text()).isEqualTo("backup-answer");assertThat(events).extracting(com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent::type).containsExactly("answer_delta","fallback_started","answer_replace","answer_committed");assertThat(events.get(2).generationVersion()).isEqualTo(2);assertThat(events.toString()).doesNotContain("late-primary");
    }
    @Test void cancellingActiveStreamCancelsProviderSubscriptionAndRetainsUnknownWithoutCommit() throws Exception {
        var output=new java.util.concurrent.CountDownLatch(1);var events=new java.util.concurrent.CopyOnWriteArrayList<com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>();
        custom=exchange -> {exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();try{Thread.sleep(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}};
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){var future=executor.submit(() -> client.completeStreaming(identity,"call","CHAT","rules","q",true,event -> {events.add(event);output.countDown();}));assertThat(output.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();future.cancel(true);assertThat(billing.unknownSignal.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
        assertThat(events).extracting(com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent::type).doesNotContain("answer_committed","fallback_started");assertThat(calls.get()).isEqualTo(1);
    }
    @Test void incomingTokenBudgetRejectsBeforeProviderAndReceiptReplayIgnoresReducedBudget() {
        assertThatThrownBy(() -> client.complete(identity,"call","CHAT","rules","q",true,100L)).isInstanceOfSatisfying(ModelCallClient.ModelCallException.class,e -> {assertThat(e.code()).isEqualTo("MODEL_TOKEN_BUDGET");assertThat(e.status()).isEqualTo(429);});assertThat(calls.get()).isZero();assertThat(billing.cancelled).isTrue();
        billing.state=new GroupState("call","SUCCEEDED","{\"text\":\"cached\",\"inputTokens\":12,\"outputTokens\":3,\"model\":\"model\",\"fallback\":false,\"reservedTokens\":15,\"unknownTokens\":0}","PRIMARY");assertThat(client.complete(identity,"call","CHAT","rules","q",true,0L).text()).isEqualTo("cached");assertThat(calls.get()).isZero();
    }
    @Test void budgetEnoughForPrimaryOnlyDisablesBackupAndUnknownPrimaryBoundIsHonestInReceipt() {
        long primary=com.bbb.exercise.agentdemo.api.model.TokenBudgetEstimator.completion("CHAT","rules","q",false);
        response="{}";status=503;assertThatThrownBy(() -> client.complete(identity,"call","CHAT","rules","q",true,primary)).isInstanceOf(ModelCallClient.ModelCallException.class);assertThat(calls.get()).isEqualTo(1);
        calls.set(0);status=200;primaryFails=true;response="{\"choices\":[{\"message\":{\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";
        var result=client.complete(identity,"call","CHAT","rules","q",true,64000L);assertThat(result.unknownTokens()).isEqualTo(primary);assertThat(result.reservedTokens()).isEqualTo(primary+15);assertThat(result.inputTokens()).isEqualTo(12);assertThat(billing.lastAttempt.plannedTokenUpper()).isGreaterThan(2048);
    }
    static class FakeBilling extends BillingClient {
        GroupState state=new GroupState("call","RESERVED",null,null);String unknown;AttemptReceipt saved;boolean finished,cancelled;AttemptReserve lastAttempt;RuntimeException attemptError;java.util.concurrent.CountDownLatch unknownSignal=new java.util.concurrent.CountDownLatch(1);
        FakeBilling(){super(null);}
        @Override public Mono<GroupState> reserveGroup(ChatIdentity i,GroupReserve r){return Mono.just(state);}
        @Override public Mono<AttemptState> attempt(ChatIdentity i,String id,AttemptReserve r){lastAttempt=r;return attemptError==null?Mono.just(new AttemptState(r.role(),"RESERVED")):Mono.error(attemptError);}
        @Override public Mono<Void> dispatchAttempt(ChatIdentity i,String id,String role){return Mono.empty();}
        @Override public Mono<Void> unknownAttempt(ChatIdentity i,String id,String role){unknown=role;return Mono.empty();}
        @Override public Mono<Void> dispatchAttempt(ChatIdentity i,String id,String role,int generation){return Mono.empty();}
        @Override public Mono<Void> unknownAttempt(ChatIdentity i,String id,String role,int generation){unknown=role;unknownSignal.countDown();return Mono.empty();}
        @Override public Mono<Void> skipAttempt(ChatIdentity i,String id,String role,int generation){return Mono.empty();}
        @Override public Mono<Void> receipt(ChatIdentity i,String id,AttemptReceipt r){saved=r;return Mono.empty();}
        @Override public Mono<GroupState> finish(ChatIdentity i,String id){finished=true;return Mono.just(new GroupState("call","SUCCEEDED",state.receipt(),"PRIMARY"));}
        @Override public Mono<Void> cancelGroup(ChatIdentity i,String id,int generation){cancelled=true;return Mono.empty();}
    }
}
