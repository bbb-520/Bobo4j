package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.mcp.schema.ToolSchema;
import com.bbb.exercise.agentdemo.orchestrator.mcp.McpToolSourceAdapter;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.mcp.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit public-query-only live smoke, runnable with the deployed JAR runtime and isolated H2. */
@EnabledIfSystemProperty(named="mcp.live",matches="true")
public class McpPublicDocumentationLiveTest {
    @Test void fourRealToolsReturnBoundedReceiptsAndReplayWithoutAnotherDispatch() throws Exception {
        String module=McpConnectionManager.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertThat(module).contains("agent-mcp");
        System.out.println("MCP_MODULE_SOURCE "+module);
        var owner=new ChatIdentity("mcp-live","smoke",true);
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__persistent_executions.sql")).execute(source);
        var store=new ExecutionStore(new JdbcTemplate(source),new DataSourceTransactionManager(source),JsonMapper.builder().build());
        store.create(owner,new ExecutionStore.Create("mcp-live","AGENT","Public documentation verification",null,null,false));
        var lease=store.claim("mcp-live").orElseThrow();var metrics=new SimpleMeterRegistry();
        var config=new McpPublicDocumentation(true,Set.of("mcp-live")).apply(new McpProperties(true,Duration.ofSeconds(30),Map.of()));
        try(var manager=new McpConnectionManager(config,metrics)) {
            manager.refresh();long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();
            while(System.nanoTime()<deadline&&!manager.status().values().stream().allMatch("READY"::equals))Thread.sleep(100);
            assertThat(manager.status().values()).containsOnly("READY");
            for(String server:config.connections().keySet())assertThat(metrics.get("agent.mcp.approved.tools").tag("server",server).gauge().value()).isEqualTo(2);
            var registry=new ExecutionToolRegistry(List.of(),List.of(new McpToolSourceAdapter(manager)));
            var scope=new ExecutionToolRegistry.Scope(owner,null,null,false,"mcp-live","Public technical query",Set.of());
            assertThat(registry.names(scope)).contains("mcp.learn.search","mcp.learn.fetch","mcp.deepwiki.structure","mcp.deepwiki.ask").hasSize(9);
            var requests=new LinkedHashMap<String,Map<String,Object>>();
            requests.put("mcp.learn.search",Map.of("query","Azure Java DefaultAzureCredential managed identity overview"));
            requests.put("mcp.learn.fetch",Map.of("url","https://learn.microsoft.com/en-us/azure/developer/java/sdk/authentication/overview"));
            requests.put("mcp.deepwiki.structure",Map.of("repoName","spring-projects/spring-ai"));
            requests.put("mcp.deepwiki.ask",Map.of("repoName","spring-projects/spring-ai","question","What is the purpose of ToolCallback? Answer briefly and cite the source."));
            var runner=new ExecutionStepRunner(store,null,metrics);int number=0;
            for(var request:requests.entrySet()) {
                String key="g1:r0:public-"+number++;String alias=request.getKey();
                var decision=new AgentDecision("CALL_TOOL",alias,request.getValue(),null);
                var result=registry.dispatch(decision,scope,p->runner.invokeMcp(lease,key,p,alias,manager));
                assertThat(result).containsEntry("isError",false).containsEntry("inputTokens",0L).containsEntry("outputTokens",0L);
                assertThat(result.get("text").toString()).isNotBlank().contains("https://");
                assertThat(ToolSchema.canonical(result)).hasSizeLessThan(16000);
                var replay=registry.dispatch(decision,scope,p->runner.invokeMcp(lease,key,p,alias,(i,c,a)->{throw new AssertionError("Remote redispatch on replay");}));
                assertThat(ToolSchema.canonical(replay)).isEqualTo(ToolSchema.canonical(result));
                System.out.println("LIVE_RECEIPT "+alias+" chars="+ToolSchema.canonical(result).length()+" replay=PASS");
            }
            assertThat(store.completedSteps(lease)).hasSize(4);assertThat(store.leased(lease).budget().tokensUsed()).isZero();
            assertThat(store.leased(lease).budget().toolCalls()).isEqualTo(4);
            System.out.println("LIVE_COMPLETE four tools; isolated ledger replay; no model billing; no production rows modified");
        }
    }
    public static void main(String[] args) throws Exception {new McpPublicDocumentationLiveTest().fourRealToolsReturnBoundedReceiptsAndReplayWithoutAnotherDispatch();}
}
