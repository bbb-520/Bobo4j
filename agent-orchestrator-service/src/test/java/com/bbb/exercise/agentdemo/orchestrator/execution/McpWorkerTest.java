package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.mcp.schema.ToolSchema;
import com.bbb.exercise.agentdemo.orchestrator.mcp.McpToolSourceAdapter;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.mcp.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpWorkerTest {
    ExecutionStore store;ExecutionClient models;SimpleMeterRegistry metrics;
    @BeforeEach void setup(){
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__persistent_executions.sql")).execute(source);
        store=new ExecutionStore(new JdbcTemplate(source),new DataSourceTransactionManager(source),JsonMapper.builder().build());
        models=mock(ExecutionClient.class);metrics=new SimpleMeterRegistry();
    }
    @Test void agentDecidesCallsRealMcpAndFinishesUsingTheSavedEvidence() throws Exception {
        try(var fixture=new McpFoundationTest.Fixture();var manager=new McpConnectionManager(McpFoundationTest.properties(fixture.port(),true,new ToolSchema(McpFoundationTest.SCHEMA).fingerprint()),metrics)){
            McpFoundationTest.ready(manager);var decisions=new AtomicInteger();
            when(models.post(any(),eq("chat"),eq("/internal/chat/complete"),any(),eq(false))).thenAnswer(invocation->{
                Map<?,?> body=invocation.getArgument(3);
                assertThat(body.get("system").toString()).contains("mcp.test.echo");
                String text;
                if(decisions.getAndIncrement()==0)text="{\"type\":\"CALL_TOOL\",\"tool\":\"mcp.test.echo\",\"arguments\":{\"query\":\"hello\"}}";
                else {assertThat(body.get("user").toString()).contains("evidence: hello");text="{\"type\":\"FINAL\",\"answer\":\"Evidence received\"}";}
                return CompletableFuture.completedFuture(Map.of("text",text,"inputTokens",10,"outputTokens",5));
            });
            var owner=new ChatIdentity("tenant","user",true);
            var row=store.create(owner,new ExecutionStore.Create("request","AGENT","Read evidence",null,null,false));
            var worker=new ExecutionWorker(store,models,metrics,ObservationRegistry.NOOP,new ExecutionToolRegistry(List.of(),List.of(new McpToolSourceAdapter(manager))),manager);
            try{
                worker.run(store.claim("test-worker").orElseThrow());
            } finally {worker.close();}
            var result=store.get(owner,row.view().executionId());
            assertThat(result.view().status()).isEqualTo("COMPLETED");assertThat(result.view().answer()).isEqualTo("Evidence received");
            assertThat(result.budget().toolCalls()).isEqualTo(1);assertThat(result.budget().tokensUsed()).isEqualTo(30);
            assertThat(fixture.calls).hasValue(1);
        }
    }
    @Test void normalChatCompletesEvenWhenConfiguredMcpServerIsUnreachable() throws Exception {
        try(var manager=new McpConnectionManager(McpFoundationTest.properties(1,true,new ToolSchema(McpFoundationTest.SCHEMA).fingerprint()),metrics)) {
            manager.refresh();
            when(models.stream(any(),any(),any())).thenReturn(CompletableFuture.completedFuture(Map.of("text","plain chat answer","inputTokens",10,"outputTokens",5)));
            var owner=new ChatIdentity("tenant","user",true);var row=store.create(owner,new ExecutionStore.Create("request","CHAT","hello",null,null,false));
            var worker=new ExecutionWorker(store,models,metrics,ObservationRegistry.NOOP,new ExecutionToolRegistry(List.of(),List.of(new McpToolSourceAdapter(manager))),manager);
            try{
                worker.run(store.claim("test-worker").orElseThrow());
            } finally {worker.close();}
            assertThat(store.get(owner,row.view().executionId()).view().status()).isEqualTo("COMPLETED");
            assertThat(store.get(owner,row.view().executionId()).view().answer()).isEqualTo("plain chat answer");
        }
    }
    @Test void savedDecisionReplaysWhenTheLiveToolCatalogChanges() throws Exception {
        try(var fixture=new McpFoundationTest.Fixture();var manager=new McpConnectionManager(McpFoundationTest.properties(fixture.port(),true,new ToolSchema(McpFoundationTest.SCHEMA).fingerprint()),metrics)) {
            McpFoundationTest.ready(manager);
            var owner=new ChatIdentity("tenant","user",true);var row=store.create(owner,new ExecutionStore.Create("request","AGENT","read",null,null,false));
            var lease=store.claim("worker").orElseThrow();String key="g1:r0:decision";
            var original=Map.of("system","previous MCP catalog","user","read","mode","DECISION");
            var step=store.beginStep(lease,key,ExecutionStore.hash(store.write(original)),ExecutionStepRunner.callId(lease,key),"chat","/internal/chat/complete",original,"DECISION",null);
            store.finishStep(lease,step,Map.of("text","{\"type\":\"CALL_TOOL\",\"tool\":\"mcp.test.echo\",\"arguments\":{\"query\":\"hello\"}}"),store.leased(lease).budget());
            when(models.post(any(),eq("chat"),eq("/internal/chat/complete"),any(),eq(false))).thenReturn(CompletableFuture.completedFuture(Map.of("text","{\"type\":\"FINAL\",\"answer\":\"done\"}")));
            var worker=new ExecutionWorker(store,models,metrics,ObservationRegistry.NOOP,new ExecutionToolRegistry(List.of(),List.of(new McpToolSourceAdapter(manager))),manager);
            try{worker.run(lease);}finally{worker.close();}
            assertThat(store.get(owner,row.view().executionId()).view().status()).isEqualTo("COMPLETED");assertThat(fixture.calls).hasValue(1);
            verify(models,times(1)).post(any(),eq("chat"),eq("/internal/chat/complete"),any(),eq(false));
        }
    }
}
