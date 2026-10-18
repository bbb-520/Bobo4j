package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.mcp.schema.ToolSchema;
import com.bbb.exercise.agentdemo.orchestrator.mcp.McpToolSourceAdapter;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.mcp.*;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class McpFoundationTest {
    static final String SCHEMA="{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"query\"],\"additionalProperties\":false}";
    static final JsonMapper JSON=JsonMapper.builder().build();
    static ExecutionToolRegistry.Scope scope(String tenant) {return new ExecutionToolRegistry.Scope(new ChatIdentity(tenant,"user",true),null,null,false,"call","question",Set.of());}
    static McpProperties properties(int port, boolean enabled, String pin) {
        var approval=new McpProperties.Approval("mcp.test.echo","Read test evidence",pin,true,true);
        var connection=new McpProperties.Connection(URI.create("http://127.0.0.1:"+port),"/mcp","test-secret",true,Set.of("tenant"),Map.of("echo",approval));
        return new McpProperties(enabled,Duration.ofSeconds(2),Map.of("test",connection));
    }
    static class Fixture implements AutoCloseable {
        final HttpServer server; final AtomicInteger calls=new AtomicInteger();
        final AtomicInteger lists=new AtomicInteger();
        final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
        volatile boolean toolError; volatile boolean huge;volatile boolean endless;volatile boolean typedOutput;
        volatile Map<String,Object> responseOverride;
        Fixture() throws Exception {this(0);}
        Fixture(int port) throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
            server.setExecutor(workers);
            server.createContext("/mcp",exchange->{
                try {
                    if(!exchange.getRequestMethod().equals("POST")){exchange.sendResponseHeaders(405,-1);return;}
                    Map<?,?> request=JSON.readValue(exchange.getRequestBody().readAllBytes(),Map.class);
                    if(!request.containsKey("id")){exchange.sendResponseHeaders(202,-1);return;}
                    Object result=switch(String.valueOf(request.get("method"))) {
                        case "initialize" -> Map.of("protocolVersion",((Map<?,?>)request.get("params")).get("protocolVersion"),"capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","fixture","version","1"));
                        case "ping" -> Map.of();
                        case "tools/list" -> {
                            lists.incrementAndGet();var page=new LinkedHashMap<String,Object>();
                            var tool=new LinkedHashMap<String,Object>();tool.put("name","echo");tool.put("description","untrusted description");tool.put("inputSchema",JSON.readValue(SCHEMA,Map.class));
                            if(typedOutput)tool.put("outputSchema",Map.of("type","object","properties",Map.of("count",Map.of("type","integer")),"required",List.of("count")));
                            page.put("tools",List.of(tool));
                            if(endless)page.put("nextCursor","repeat");yield page;
                        }
                        case "tools/call" -> {
                            calls.incrementAndGet();
                            if(responseOverride!=null)yield responseOverride;
                            var params=(Map<?,?>)request.get("params");
                            var structured=new LinkedHashMap<String,Object>();structured.put("inputTokens",999999);structured.put("nullable",null);
                            structured.put("token",exchange.getRequestHeaders().getFirst("Authorization").substring(7));
                            yield Map.of("content",List.of(Map.of("type","text","text",huge?"x".repeat(24_000):"evidence: "+((Map<?,?>)params.get("arguments")).get("query"))),"isError",toolError,"structuredContent",structured);
                        }
                        default -> throw new IllegalStateException();
                    };
                    byte[] response=JSON.writeValueAsBytes(Map.of("jsonrpc","2.0","id",request.get("id"),"result",result));
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
                } finally {exchange.close();}
            });server.start();
        }
        int port(){return server.getAddress().getPort();}
        public void close(){server.stop(0);workers.close();}
    }
    static void ready(McpConnectionManager manager) throws Exception {
        manager.refresh();
        for(int i=0;i<100;i++){if(manager.status().get("test").equals("READY"))return;Thread.sleep(30);}
        fail("MCP fixture did not connect: "+manager.status());
    }
    @Test void actualProtocolCallIsScopedPinnedAndContainsNoRemoteAccounting() throws Exception {
        try(var fixture=new Fixture(); var manager=new McpConnectionManager(properties(fixture.port(),true,new ToolSchema(SCHEMA).fingerprint()),new SimpleMeterRegistry())) {
            ready(manager);
            var registry=new ExecutionToolRegistry(List.of(),List.of(new McpToolSourceAdapter(manager)));
            assertThat(registry.names(scope("other"))).doesNotContain("mcp.test.echo");
            assertThat(registry.names(scope("tenant"))).contains("mcp.test.echo").containsAll(AgentDecision.TOOLS);
            var decision=new AgentDecision("CALL_TOOL","mcp.test.echo",Map.of("query","hello"),null);
            var result=registry.dispatch(decision,scope("tenant"),plan->manager.execute(scope("tenant").identity(),plan.mcp(),plan.body()).get());
            assertThat(result).containsEntry("text","evidence: hello").containsEntry("inputTokens",0L);
            assertThat(JSON.writeValueAsString(result)).doesNotContain("test-secret");
            assertThat(fixture.calls).hasValue(1);
            assertThatThrownBy(()->registry.dispatch(decision,scope("other"),p->{throw new AssertionError("foreign dispatch");})).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void disabledAndOfflineConnectionsDoNotRemoveBuiltinsOrBlockConstruction() throws Exception {
        try(var fixture=new Fixture();var manager=new McpConnectionManager(properties(fixture.port(),false,new ToolSchema(SCHEMA).fingerprint()),new SimpleMeterRegistry())) {
            manager.refresh();assertThat(new McpToolSourceAdapter(manager).registrations(scope("tenant"))).isEmpty();assertThat(fixture.calls).hasValue(0);
        }
        try(var manager=new McpConnectionManager(properties(1,true,new ToolSchema(SCHEMA).fingerprint()),new SimpleMeterRegistry())) {
            var registry=new ExecutionToolRegistry(List.of(),List.of(new McpToolSourceAdapter(manager)));manager.refresh();
            assertThat(registry.names(scope("tenant"))).containsExactlyInAnyOrderElementsOf(AgentDecision.TOOLS);
        }
    }
    @Test void changedSchemasAreNotAutomaticallyAdmitted() throws Exception {
        try(var fixture=new Fixture();var manager=new McpConnectionManager(properties(fixture.port(),true,"0".repeat(64)),new SimpleMeterRegistry())) {
            ready(manager);assertThat(new McpToolSourceAdapter(manager).registrations(scope("tenant"))).isEmpty();assertThat(fixture.calls).hasValue(0);
        }
    }
    @Test void errorAndOversizedResultsStayBoundedAndCannotClaimSuccess() throws Exception {
        try(var fixture=new Fixture();var manager=new McpConnectionManager(properties(fixture.port(),true,new ToolSchema(SCHEMA).fingerprint()),new SimpleMeterRegistry())) {
            ready(manager);fixture.toolError=true;fixture.huge=true;
            var registry=new ExecutionToolRegistry(List.of(),List.of(new McpToolSourceAdapter(manager)));
            var result=registry.dispatch(new AgentDecision("CALL_TOOL","mcp.test.echo",Map.of("query","x"),null),scope("tenant"),p->manager.execute(scope("tenant").identity(),p.mcp(),p.body()).get());
            assertThat(result).containsEntry("isError",true);
            assertThat(JSON.writeValueAsString(result).length()).isLessThan(20_000);
            assertThat(result.get("text").toString()).contains("MCP_RESULT_TOO_LARGE");
        }
    }
    @Test void disconnectedConnectionRecoversWithoutAutomaticallyReplayingTools() throws Exception {
        try(var original=new Fixture();var manager=new McpConnectionManager(properties(original.port(),true,new ToolSchema(SCHEMA).fingerprint()),new SimpleMeterRegistry())) {
            ready(manager);int port=original.port();original.server.stop(0);
            var call=new McpCall("test","echo",new ToolSchema(SCHEMA).fingerprint());
            assertThatThrownBy(()->manager.execute(scope("tenant").identity(),call,Map.of("query","hello")).get()).isInstanceOf(ExecutionException.class);
            assertThat(new McpToolSourceAdapter(manager).registrations(scope("tenant"))).isEmpty();
            try(var replacement=new Fixture(port)) {
                for(int i=0;i<200&&!manager.status().get("test").equals("READY");i++){manager.refresh();Thread.sleep(50);}
                assertThat(manager.status().get("test")).isEqualTo("READY");assertThat(replacement.calls).hasValue(0);
                assertThat(manager.execute(scope("tenant").identity(),call,Map.of("query","hello")).get()).containsEntry("text","evidence: hello");
                assertThat(replacement.calls).hasValue(1);
            }
        }
    }
    @Test void repeatingDiscoveryCursorIsRejectedBeforeUnboundedAccumulation() throws Exception {
        try(var fixture=new Fixture();var manager=new McpConnectionManager(properties(fixture.port(),true,new ToolSchema(SCHEMA).fingerprint()),new SimpleMeterRegistry())) {
            fixture.endless=true;manager.refresh();
            for(int i=0;i<100&&!manager.status().get("test").equals("UNAVAILABLE");i++)Thread.sleep(30);
            assertThat(manager.status().get("test")).isEqualTo("UNAVAILABLE");
            assertThat(fixture.lists.get()).isLessThanOrEqualTo(2);assertThat(new McpToolSourceAdapter(manager).registrations(scope("tenant"))).isEmpty();
        }
    }
    @Test void declaredOutputSchemaIsCheckedWithoutSdkRawResultLogging() throws Exception {
        try(var fixture=new Fixture();var manager=new McpConnectionManager(properties(fixture.port(),true,new ToolSchema(SCHEMA).fingerprint()),new SimpleMeterRegistry())) {
            fixture.typedOutput=true;ready(manager);
            var result=manager.execute(scope("tenant").identity(),new McpCall("test","echo",new ToolSchema(SCHEMA).fingerprint()),Map.of("query","hello")).get();
            assertThat(result).containsEntry("isError",true).containsEntry("text","MCP_OUTPUT_SCHEMA_INVALID");
            assertThat(result).doesNotContainKey("structuredContent");
        }
    }
    @Test void credentialRedactionPreservesJsonNullAndNumericTypes() throws Exception {
        try(var fixture=new Fixture()) {
            var original=properties(fixture.port(),true,new ToolSchema(SCHEMA).fingerprint());var c=original.connections().get("test");
            var changed=new McpProperties(true,original.requestTimeout(),Map.of("test",new McpProperties.Connection(c.url(),c.endpoint(),"null",true,c.tenants(),c.tools())));
            try(var manager=new McpConnectionManager(changed,new SimpleMeterRegistry())) {
                ready(manager);var result=manager.execute(scope("tenant").identity(),new McpCall("test","echo",new ToolSchema(SCHEMA).fingerprint()),Map.of("query","hello")).get();
                assertThat(result).containsEntry("isError",false);
                assertThat(((Map<?,?>)result.get("structuredContent")).containsKey("nullable")).isTrue();
                assertThat(((Map<?,?>)result.get("structuredContent")).get("nullable")).isNull();
                assertThat(((Map<?,?>)result.get("structuredContent")).get("token")).isEqualTo("[redacted]");
            }
        }
    }
}
