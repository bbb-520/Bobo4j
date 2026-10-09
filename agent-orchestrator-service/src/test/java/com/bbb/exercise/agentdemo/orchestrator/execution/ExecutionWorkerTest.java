package com.bbb.exercise.agentdemo.orchestrator.execution;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionWorkerTest {
    @Test void reconciledUnknownHoldIsReleasedWithoutRepeatingSuccessfulStep() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__persistent_executions.sql")).execute(ds);
        var db=new JdbcTemplate(ds);var store=new ExecutionStore(db,new DataSourceTransactionManager(ds),JsonMapper.builder().build());
        var owner=new ChatIdentity("a","u",true);var client=mock(ExecutionClient.class);
        var run=store.create(owner,new ExecutionStore.Create("key","CHAT","question",null,null,false));
        var lease=store.claim("before-crash").orElseThrow();String key="g1:r0:chat",id=ExecutionWorker.callId(lease,key);
        var step=store.beginStep(lease,key,"hash",id,"chat","/internal/chat/complete",Map.of("user","question"),"DIRECT",null);
        store.reserveStepTokens(lease,step);store.settleStepTokens(lease,step,12,3,2200);
        store.finishStep(lease,step,Map.of("text","answer","inputTokens",12,"outputTokens",3),store.leased(lease).budget().recordDecision(0,0,0,"answer",true,false));
        db.update("UPDATE agent_execution SET lease_until=DATEADD('SECOND',-60,CURRENT_TIMESTAMP) WHERE id=?",lease.executionId());
        when(client.get(eq(owner),eq("auth"),any())).thenReturn(CompletableFuture.completedFuture(Map.of("status","SUCCEEDED","inputTokens",12,"outputTokens",3,"unknownTokens",0)));
        var worker=new ExecutionWorker(store,client,new SimpleMeterRegistry());
        try{worker.run(store.claim("after-crash").orElseThrow());}finally{worker.close();}
        var done=store.get(owner,run.view().executionId());
        assertThat(done.view().status()).isEqualTo("COMPLETED");assertThat(done.budget().reservedTokens()).isZero();
        assertThat(done.budget().tokensUsed()).isEqualTo(15);assertThat(done.budget().roundsUsed()).isEqualTo(1);
        verify(client,never()).post(any(),any(),any(),any(),anyBoolean());verify(client,never()).stream(any(),any(),any());
    }
    @Test void billingReceiptRecoveryMustFinishTheChatOwnerHistoryBeforeExecutionCommit() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__persistent_executions.sql")).execute(ds);
        var store=new ExecutionStore(new JdbcTemplate(ds),new DataSourceTransactionManager(ds),JsonMapper.builder().build());
        var client=mock(ExecutionClient.class);var owner=new ChatIdentity("a","u",true);
        var run=store.create(owner,new ExecutionStore.Create("key","CHAT","question",null,null,false));
        var lease=store.claim("worker").orElseThrow();String key="g1:r0:chat",id=ExecutionWorker.callId(lease,key);
        var body=Map.of("callId",id,"mode","CHAT","system","trusted","user","question");
        var step=store.beginStep(lease,key,ExecutionStore.hash(store.write(body)),id,"chat","/internal/chat/complete",body,"DIRECT",null);store.reserveStepTokens(lease,step);
        var receipt=Map.<String,Object>of("text","answer","inputTokens",12,"outputTokens",3);
        when(client.get(eq(owner),eq("auth"),any())).thenReturn(CompletableFuture.completedFuture(Map.of("status","SUCCEEDED","receipt",store.write(receipt),"inputTokens",12,"outputTokens",3,"unknownTokens",0)));
        var history=new java.util.concurrent.atomic.AtomicBoolean(false);
        when(client.post(eq(owner),eq("chat"),eq("/internal/chat/complete"),any(),eq(false))).thenAnswer(invocation->{history.set(true);return CompletableFuture.completedFuture(receipt);});
        var worker=new ExecutionWorker(store,client,new SimpleMeterRegistry());
        try{worker.run(lease);}finally{worker.close();}
        assertThat(history).isTrue();assertThat(store.get(owner,run.view().executionId()).view().status()).isEqualTo("COMPLETED");
        assertThat(store.get(owner,run.view().executionId()).budget().tokensUsed()).isEqualTo(15);
        verify(client,never()).stream(any(),any(),any());
    }
    @Test void ragUnknownUsageRemainsHeldAfterTheVerifiedAnswerCommits() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__persistent_executions.sql")).execute(ds);
        var store=new ExecutionStore(new JdbcTemplate(ds),new DataSourceTransactionManager(ds),JsonMapper.builder().build());
        var client=mock(ExecutionClient.class);var owner=new ChatIdentity("a","u",true);
        var run=store.create(owner,new ExecutionStore.Create("key","DOCUMENT_QA","question",null,UUID.randomUUID().toString(),false));
        when(client.post(eq(owner),eq("rag"),eq("/internal/rag/answer"),any(),eq(false))).thenReturn(CompletableFuture.completedFuture(Map.of("text","answer","sources",List.of(),"evaluation","PASS","inputTokens",20,"outputTokens",5,"unknownReservedTokens",2200,"budgetTokens",2225)));
        var worker=new ExecutionWorker(store,client,new SimpleMeterRegistry());
        try{worker.run(store.claim("test").orElseThrow());}finally{worker.close();}
        assertThat(store.get(owner,run.view().executionId()).budget().reservedTokens()).isEqualTo(2200);
        assertThat(store.get(owner,run.view().executionId()).budget().tokensUsed()).isEqualTo(25);
    }
    @Test void decisionThatUsesRemainingBudgetCannotDispatchAnotherPaidTool() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__persistent_executions.sql")).execute(ds);
        var store=new ExecutionStore(new JdbcTemplate(ds),new DataSourceTransactionManager(ds),JsonMapper.builder().build());
        var client=mock(ExecutionClient.class);var owner=new ChatIdentity("a","u",true);
        var run=store.create(owner,new ExecutionStore.Create("key","AGENT","question",null,UUID.randomUUID().toString(),false));
        var lease=store.claim("test").orElseThrow();store.saveBudget(lease,ExecutionBudget.initial().charge(63900,0,0));
        when(client.post(eq(owner),eq("chat"),eq("/internal/chat/complete"),any(),eq(false))).thenReturn(CompletableFuture.completedFuture(Map.of("text","{\"type\":\"CALL_TOOL\",\"tool\":\"rag.answer\",\"arguments\":{\"query\":\"question\"}}","inputTokens",200,"outputTokens",100)));
        var worker=new ExecutionWorker(store,client,new SimpleMeterRegistry());
        try{worker.run(lease);}finally{worker.close();}
        assertThat(store.get(owner,run.view().executionId()).view().status()).isEqualTo("WAITING_FOR_BUDGET");
        verify(client,never()).post(eq(owner),eq("rag"),any(),any(),anyBoolean());
    }
    @Test void recoversAPersistedFinalReceiptAfterCrashWithoutCallingTheModelAgain() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__persistent_executions.sql")).execute(ds);
        var store=new ExecutionStore(new JdbcTemplate(ds),new DataSourceTransactionManager(ds),JsonMapper.builder().build());
        var client=mock(ExecutionClient.class);var owner=new ChatIdentity("a","u",true);
        var run=store.create(owner,new ExecutionStore.Create("key","CHAT","question",null,null,false));
        var lease=store.claim("before-crash").orElseThrow();String key="g1:r0:chat";
        var step=store.beginStep(lease,key,"saved-request",ExecutionWorker.callId(lease,key));
        store.finishStep(lease,step,Map.of("text","saved answer","inputTokens",12,"outputTokens",3),ExecutionBudget.initial().recordDecision(12,3,50,"answer",true,false));
        // Simulate process loss after durable model receipt, before final execution commit.
        new JdbcTemplate(ds).update("UPDATE agent_execution SET lease_until=DATEADD('SECOND',-60,CURRENT_TIMESTAMP) WHERE id=?",lease.executionId());
        var worker=new ExecutionWorker(store,client,new SimpleMeterRegistry());
        try{worker.run(store.claim("after-crash").orElseThrow());}finally{worker.close();}
        assertThat(store.get(owner,run.view().executionId()).view().status()).isEqualTo("COMPLETED");
        assertThat(store.get(owner,run.view().executionId()).view().answer()).isEqualTo("saved answer");
        assertThat(store.get(owner,run.view().executionId()).budget().tokensUsed()).isEqualTo(15);
        verifyNoInteractions(client);
    }
    @Test void oneShotChatCommitsAnswerAndActualUsageWithNoBudgetExtension() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__persistent_executions.sql")).execute(ds);
        var store=new ExecutionStore(new JdbcTemplate(ds),new DataSourceTransactionManager(ds),JsonMapper.builder().build());
        var client=mock(ExecutionClient.class);var owner=new ChatIdentity("a","u",true);
        var run=store.create(owner,new ExecutionStore.Create("key","CHAT","question",null,null,false));
        when(client.stream(eq(owner),any(),any())).thenAnswer(invocation->{
            java.util.function.Consumer<Map<String,Object>> sink=invocation.getArgument(2);
            sink.accept(Map.of("type","answer_delta","generationVersion",1,"payload",Map.of("text","partial")));
            sink.accept(Map.of("type","fallback_started","generationVersion",2,"payload",Map.of("message","备用服务接手")));
            sink.accept(Map.of("type","answer_replace","generationVersion",2,"payload",Map.of("text","answer")));
            assertThat(store.get(owner,run.view().executionId()).view().answer()).isEqualTo("answer");
            return CompletableFuture.completedFuture(Map.of("text","answer","inputTokens",12,"outputTokens",3,"fallback",true));
        });
        var observations=io.micrometer.observation.ObservationRegistry.create();
        var span=new java.util.concurrent.atomic.AtomicReference<io.micrometer.observation.Observation.Context>();
        observations.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>() {
            public boolean supportsContext(io.micrometer.observation.Observation.Context context){return true;}
            public void onStop(io.micrometer.observation.Observation.Context context){span.set(context);}
        });
        var worker=new ExecutionWorker(store,client,new SimpleMeterRegistry(),observations);
        try {worker.run(store.claim("test").orElseThrow());}finally{worker.close();}
        var done=store.get(owner,run.view().executionId());
        assertThat(done.view().status()).isEqualTo("COMPLETED");assertThat(done.view().answer()).isEqualTo("answer");
        assertThat(done.budget().tokensUsed()).isEqualTo(15);assertThat(done.budget().roundLimit()).isEqualTo(4);
        assertThat(span.get().getName()).isEqualTo("agent.execution");
        assertThat(store.events(owner,run.view().executionId(),0,200)).extracting(ExecutionStore.Event::type).contains("fallback_started","answer_committed");
    }
    @Test void dispatchedFailurePreservesCheckpointAndWaitsInsteadOfRetrying() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__persistent_executions.sql")).execute(ds);
        var store=new ExecutionStore(new JdbcTemplate(ds),new DataSourceTransactionManager(ds),JsonMapper.builder().build());
        var client=mock(ExecutionClient.class);var owner=new ChatIdentity("a","u",true);
        var run=store.create(owner,new ExecutionStore.Create("key","CHAT","question",null,null,false));
        when(client.stream(eq(owner),any(),any()))
                .thenReturn(CompletableFuture.failedFuture(new ExecutionClient.RemoteFailure(503)));
        var worker=new ExecutionWorker(store,client,new SimpleMeterRegistry());
        try{worker.run(store.claim("test").orElseThrow());}finally{worker.close();}
        assertThat(store.get(owner,run.view().executionId()).view().status()).isEqualTo("WAITING_FOR_RECONCILIATION");
        assertThat(store.claim("other")).isEmpty();
    }
}
