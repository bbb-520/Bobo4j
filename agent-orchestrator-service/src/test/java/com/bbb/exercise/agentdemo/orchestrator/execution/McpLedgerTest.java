package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.mcp.schema.ToolSchema;
import com.bbb.exercise.agentdemo.orchestrator.mcp.McpToolSourceAdapter;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.mcp.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpLedgerTest {
    @Test void publicDocumentRestrictionsRunBeforeLedgerAndProjectedReceiptReplays() throws Exception {
        try(var fixture=new McpFoundationTest.Fixture()) {
            String url="https://learn.microsoft.com/azure/developer/java/sdk/authentication/overview";
            var rows=List.of(Map.of("title","Java identity","content","evidence "+"x".repeat(10000),"contentUrl",url));
            fixture.responseOverride=Map.of("content",List.of(Map.of("type","text","text","duplicated "+"y".repeat(10000))),"structuredContent",Map.of("results",rows),"isError",false);
            var original=McpFoundationTest.properties(fixture.port(),true,new ToolSchema(McpFoundationTest.SCHEMA).fingerprint());var c=original.connections().get("test");
            var approval=new McpProperties.Approval("mcp.learn.search","Search public docs",new ToolSchema(McpFoundationTest.SCHEMA).fingerprint(),true,true,McpDocumentPolicy.LEARN_SEARCH);
            var config=new McpProperties(true,original.requestTimeout(),Map.of("test",new McpProperties.Connection(c.url(),c.endpoint(),c.bearerToken(),true,c.tenants(),Map.of("echo",approval))));
            try(var manager=new McpConnectionManager(config,new SimpleMeterRegistry())) {
                McpFoundationTest.ready(manager);var registry=new ExecutionToolRegistry(List.of(),List.of(new McpToolSourceAdapter(manager)));var scope=McpFoundationTest.scope("tenant");
                assertThatThrownBy(()->registry.dispatch(new AgentDecision("CALL_TOOL","mcp.learn.search",Map.of("query"," "),null),scope,p->runner.invokeMcp(lease,"docs",p,"mcp.learn.search",manager))).isInstanceOf(IllegalArgumentException.class);
                assertThat(store.completedSteps(lease)).isEmpty();assertThat(fixture.calls).hasValue(0);
                var decision=new AgentDecision("CALL_TOOL","mcp.learn.search",Map.of("query","Java identity"),null);
                var first=registry.dispatch(decision,scope,p->runner.invokeMcp(lease,"docs",p,"mcp.learn.search",manager));
                var replay=registry.dispatch(decision,scope,p->runner.invokeMcp(lease,"docs",p,"mcp.learn.search",manager));
                assertThat(ToolSchema.canonical(first)).isEqualTo(ToolSchema.canonical(replay));
                assertThat(first).containsEntry("isError",false).containsEntry("truncated",true);assertThat(first.get("text").toString()).contains(url);
                assertThat(fixture.calls).hasValue(1);assertThat(store.leased(lease).budget().toolCalls()).isEqualTo(1);
            }
        }
    }
    ExecutionStore store;ExecutionStore.Lease lease;ExecutionStepRunner runner;JdbcTemplate db;
    @BeforeEach void setup(){
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__persistent_executions.sql")).execute(source);
        db=new JdbcTemplate(source);store=new ExecutionStore(db,new DataSourceTransactionManager(source),JsonMapper.builder().build());
        store.create(new ChatIdentity("tenant","user",true),new ExecutionStore.Create("request","AGENT","read evidence",null,null,false));
        lease=store.claim("worker").orElseThrow();runner=new ExecutionStepRunner(store,mock(ExecutionClient.class),new SimpleMeterRegistry());
    }
    @Test void actualMcpReceiptReplaysWithoutSecondCallOrRemoteTokenCharge() throws Exception {
        try(var fixture=new McpFoundationTest.Fixture();var manager=new McpConnectionManager(McpFoundationTest.properties(fixture.port(),true,new ToolSchema(McpFoundationTest.SCHEMA).fingerprint()),new SimpleMeterRegistry())){
            McpFoundationTest.ready(manager);
            var plan=ExecutionToolRegistry.Plan.mcp(new McpCall("test","echo",new ToolSchema(McpFoundationTest.SCHEMA).fingerprint()),Map.of("query","hello"));
            var first=runner.invokeMcp(lease,"g1:r0:tool",plan,"mcp.test.echo",manager);
            var replay=runner.invokeMcp(lease,"g1:r0:tool",plan,"mcp.test.echo",manager);
            assertThat(JsonMapper.builder().build().writeValueAsString(replay)).isEqualTo(JsonMapper.builder().build().writeValueAsString(first));assertThat(fixture.calls).hasValue(1);
            assertThat(store.completedSteps(lease)).hasSize(1);
            assertThat(store.leased(lease).budget().tokensUsed()).isZero();
            assertThat(store.leased(lease).budget().toolCalls()).isEqualTo(1);
            assertThat(db.queryForObject("SELECT request_json FROM agent_execution_step WHERE target='mcp'",String.class)).contains("schemaHash","test").doesNotContain("test-secret");
        }
    }
    @Test void unresolvedDispatchCannotRedispatchOrBeReconciledAgainstModelBilling() throws Exception {
        var plan=ExecutionToolRegistry.Plan.mcp(new McpCall("test","echo","0".repeat(64)),Map.of("query","hello"));
        McpToolExecutor timeout=(who,call,args)->CompletableFuture.failedFuture(new TimeoutException());
        assertThatThrownBy(()->runner.invokeMcp(lease,"g1:r0:tool",plan,"mcp.test.echo",timeout)).isInstanceOf(McpDispatchUncertain.class);
        McpToolExecutor forbidden=(who,call,args)->{throw new AssertionError("Unknown calls must not be redispatched");};
        assertThatThrownBy(()->runner.invokeMcp(lease,"g1:r0:tool",plan,"mcp.test.echo",forbidden)).isInstanceOf(McpDispatchUncertain.class);
        assertThatThrownBy(()->runner.recoverPending(lease,store.leased(lease))).isInstanceOf(McpDispatchUncertain.class);
        assertThat(store.unfinishedSteps(lease.identity(),lease.executionId())).hasSize(1);
        assertThat(store.leased(lease).budget().reservedTokens()).isZero();
    }
    @Test void remoteToolErrorIsAReplayableReceiptButDoesNotCountAsProgress() throws Exception {
        var plan=ExecutionToolRegistry.Plan.mcp(new McpCall("test","echo","0".repeat(64)),Map.of("query","hello"));
        McpToolExecutor error=(who,call,args)->CompletableFuture.completedFuture(Map.of("text","tool failed","isError",true));
        runner.invokeMcp(lease,"g1:r0:tool",plan,"mcp.test.echo",error);
        assertThat(store.completedSteps(lease)).hasSize(1);assertThat(store.leased(lease).budget().lastProgress()).isFalse();
    }
    @Test void stopCancelsLocalWaitAndRetainsUncertainDispatch() throws Exception {
        var plan=ExecutionToolRegistry.Plan.mcp(new McpCall("test","echo","0".repeat(64)),Map.of("query","hello"));
        var future=new CompletableFuture<Map<String,Object>>();
        McpToolExecutor waiting=(who,call,args)->{store.stop(lease.identity(),lease.executionId());return future;};
        assertThatThrownBy(()->runner.invokeMcp(lease,"g1:r0:tool",plan,"mcp.test.echo",waiting)).isInstanceOf(McpDispatchUncertain.class);
        assertThat(future.isCancelled()).isTrue();assertThat(store.unfinishedSteps(lease.identity(),lease.executionId())).hasSize(1);
    }
    @Test void exhaustedBudgetCannotCreateAFalseDispatchedStep() {
        store.saveBudget(lease,new ExecutionBudget(64,64,0,0,0,"",0,true));
        var plan=ExecutionToolRegistry.Plan.mcp(new McpCall("test","echo","0".repeat(64)),Map.of("query","hello"));
        assertThatThrownBy(()->runner.invokeMcp(lease,"g1:r0:tool",plan,"mcp.test.echo",(a,b,c)->{throw new AssertionError("Budget bypass");}))
                .isInstanceOf(ExecutionStepRunner.BudgetExhausted.class);
        assertThat(store.unfinishedSteps(lease.identity(),lease.executionId())).isEmpty();
    }
    @Test void reorderedJsonKeysReplayTheSameRequestAfterSerialization() throws Exception {
        var one=new LinkedHashMap<String,Object>();one.put("query","hello");one.put("limit",2);
        var two=new LinkedHashMap<String,Object>();two.put("limit",2);two.put("query","hello");
        var call=new McpCall("test","echo","0".repeat(64));
        runner.invokeMcp(lease,"g1:r0:tool",ExecutionToolRegistry.Plan.mcp(call,one),"mcp.test.echo",(a,b,c)->CompletableFuture.completedFuture(Map.of("text","saved")));
        var replay=runner.invokeMcp(lease,"g1:r0:tool",ExecutionToolRegistry.Plan.mcp(call,two),"mcp.test.echo",(a,b,c)->{throw new AssertionError("Repeated IO");});
        assertThat(replay).containsEntry("text","saved");
    }
}
