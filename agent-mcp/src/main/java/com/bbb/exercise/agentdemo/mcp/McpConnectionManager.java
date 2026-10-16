package com.bbb.exercise.agentdemo.mcp;

import com.bbb.exercise.agentdemo.mcp.schema.ToolSchema;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.*;

/** Optional, lazy MCP connections. Only operator-pinned, tenant-scoped read tools reach the ledger. */
public final class McpConnectionManager implements McpToolExecutor,AutoCloseable {
    private final McpProperties config;
    private final MeterRegistry metrics;
    private final Map<String,State> states=new ConcurrentHashMap<>();
    private final JsonMapper json=JsonMapper.builder().build();
    private final McpResultNormalizer normalizer;
    private volatile boolean closed;
    private static final class State {
        volatile McpAsyncClient client;
        volatile Map<String,ApprovedTool> tools=Map.of();
        volatile String status="DISCONNECTED";
        volatile CompletableFuture<?> pending;
        long nextAttempt;int failures;
    }
    private record ApprovedTool(ToolSchema input,ToolSchema effectiveInput,ToolSchema output) {}
    public McpConnectionManager(McpProperties config,MeterRegistry metrics) {
        this.config=config;this.metrics=metrics;
        this.normalizer=new McpResultNormalizer(config.connections().values().stream().map(McpProperties.Connection::bearerToken).toList());
        config.connections().keySet().forEach(id->{var state=new State();state.status=config.enabled()?"DISCONNECTED":"DISABLED";states.put(id,state);
            io.micrometer.core.instrument.Gauge.builder("agent.mcp.approved.tools",state,s->s.tools.size()).tag("server",id).register(metrics);});
    }
    public Map<String,String> status(){var result=new TreeMap<String,String>();states.forEach((id,s)->result.put(id,s.status));return Collections.unmodifiableMap(result);}

    @Scheduled(fixedDelayString="${app.mcp.poll-ms:5000}")
    public void refresh() {
        if(closed||!config.enabled())return;
        config.connections().forEach((id,connection)->{
            var state=states.get(id);
            synchronized(state) {
                if(closed||state.pending!=null&&!state.pending.isDone()||System.nanoTime()<state.nextAttempt)return;
                try {
                    if(state.client==null)state.client=create(connection);
                    var client=state.client;
                    Mono<McpSchema.ListToolsResult> discovery=(client.isInitialized()?client.ping():client.initialize()).then(discover(client));
                    state.pending=discovery.timeout(config.requestTimeout()).doOnNext(result->{
                        var approved=new LinkedHashMap<String,ApprovedTool>();
                        if(result.tools().size()>128)throw new IllegalArgumentException("Too many MCP tools");
                        for(var tool:result.tools()) {
                            var approval=connection.tools().get(tool.name());if(approval==null)continue;
                            try {
                                var schema=new ToolSchema(json.writeValueAsString(tool.inputSchema()));
                                var output=tool.outputSchema()==null?null:new ToolSchema(json.writeValueAsString(tool.outputSchema()),false);
                                if(schema.fingerprint().equals(approval.schemaSha256()))approved.put(tool.name(),new ApprovedTool(schema,approval.policy().input(schema),output));
                                else metrics.counter("agent.mcp.schema.rejections","server",id).increment();
                            } catch(IllegalArgumentException invalid){metrics.counter("agent.mcp.schema.rejections","server",id).increment();}
                        }
                        synchronized(state){if(!closed&&state.client==client){state.tools=Map.copyOf(approved);state.status="READY";state.failures=0;state.nextAttempt=System.nanoTime()+30_000_000_000L;}}
                    }).doOnError(error->disconnect(id,state,client)).onErrorResume(error->Mono.empty()).toFuture();
                } catch(Exception error){disconnect(id,state,state.client);}
            }
        });
    }
    private Mono<McpSchema.ListToolsResult> discover(McpAsyncClient client) {
        return Mono.defer(()->{
            var tools=new ArrayList<McpSchema.Tool>();var cursors=new HashSet<String>();var names=new HashSet<String>();long[] bytes={0};
            return client.listTools((String)null).expand(page->{
                for(var tool:page.tools()) {
                    bytes[0]+=json.writeValueAsString(tool).length();
                    if(tools.size()>=128||bytes[0]>64_000||!names.add(tool.name()))return Mono.error(new IllegalArgumentException("MCP discovery limit exceeded"));
                    tools.add(tool);
                }
                String cursor=page.nextCursor();
                if(cursor==null||cursor.isBlank())return Mono.empty();
                if(cursors.size()>=7||cursor.length()>2048||!cursors.add(cursor))return Mono.error(new IllegalArgumentException("MCP pagination limit exceeded"));
                return client.listTools(cursor);
            }).then(Mono.fromSupplier(()->new McpSchema.ListToolsResult(List.copyOf(tools),null)));
        });
    }
    private McpAsyncClient create(McpProperties.Connection connection) {
        var http=HttpClient.create(ConnectionProvider.newConnection()).disableRetry(true).followRedirect(false).responseTimeout(config.requestTimeout());
        var web=WebClient.builder().baseUrl(connection.url().toString()).clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c->c.defaultCodecs().maxInMemorySize(64_000));
        if(!connection.bearerToken().isBlank())web.defaultHeaders(headers->headers.setBearerAuth(connection.bearerToken()));
        var transport=WebClientStreamableHttpTransport.builder(web).endpoint(connection.endpoint()).resumableStreams(false).openConnectionOnStartup(false).build();
        return McpClient.async(transport).requestTimeout(config.requestTimeout()).initializationTimeout(config.requestTimeout())
                .jsonSchemaValidator(new McpSchemaValidator()).capabilities(McpSchema.ClientCapabilities.builder().build()).build();
    }
    private void disconnect(String id,State state,McpAsyncClient client) {
        synchronized(state){
            if(state.client!=client)return;
            state.tools=Map.of();state.client=null;state.status="UNAVAILABLE";
            state.failures=Math.min(4,state.failures+1);state.nextAttempt=System.nanoTime()+Math.min(60,5L<<(state.failures-1))*1_000_000_000L;
            metrics.counter("agent.mcp.connection.failures","server",id).increment();
        }
        if(client!=null)client.closeGracefully().timeout(java.time.Duration.ofSeconds(1)).onErrorResume(e->Mono.empty()).subscribe();
    }
    public List<McpToolDescriptor> tools(ChatIdentity identity) {
        if(closed||!config.enabled()||identity==null||!identity.authenticated())return List.of();
        var registrations=new ArrayList<McpToolDescriptor>();
        config.connections().forEach((id,connection)->{
            if(!connection.tenants().contains(identity.tenantId()))return;
            states.get(id).tools.forEach((name,approved)->{
                var schema=approved.input();
                var approval=connection.tools().get(name);var call=new McpCall(id,name,schema.fingerprint());
                registrations.add(new McpToolDescriptor(approval.alias(),approval.description(),approved.effectiveInput(),call));
            });
        });return List.copyOf(registrations);
    }
    @Override public CompletableFuture<Map<String,Object>> execute(ChatIdentity identity,McpCall call,Map<String,Object> args) {
        var connection=config.connections().get(call.serverId());var state=states.get(call.serverId());
        if(closed||!config.enabled()||identity==null||!identity.authenticated()||connection==null||!connection.tenants().contains(identity.tenantId()))
            throw new IllegalArgumentException("MCP 工具未授权");
        McpAsyncClient client;ApprovedTool approved;
        synchronized(state){client=state.client;approved=state.tools.get(call.tool());}
        ToolSchema schema=approved==null?null:approved.input();
        if(client==null||schema==null||!schema.fingerprint().equals(call.schemaHash()))throw new IllegalArgumentException("MCP 工具不可用或定义已变化");
        approved.effectiveInput().validate(args);
        return client.callTool(new McpSchema.CallToolRequest(call.tool(),args)).timeout(config.requestTimeout())
                .map(result->normalizer.normalize(call,result,approved.output(),connection.tools().get(call.tool()).policy(),args)).doOnError(error->disconnect(call.serverId(),state,client)).toFuture();
    }
    @Override @PreDestroy public void close() {
        closed=true;
        var clients=new ArrayList<McpAsyncClient>();
        states.values().forEach(state->{synchronized(state){
            if(state.pending!=null)state.pending.cancel(true);state.tools=Map.of();state.status="CLOSED";
            if(state.client!=null){clients.add(state.client);state.client=null;}
        }});
        clients.forEach(McpAsyncClient::close);
    }
}
