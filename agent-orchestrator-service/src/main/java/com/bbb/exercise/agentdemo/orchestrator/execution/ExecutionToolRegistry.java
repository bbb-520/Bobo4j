package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.function.BiFunction;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Spring AI callbacks accept only an execution-bound dispatcher, never model-supplied identity or IO. */
@Component
public class ExecutionToolRegistry {
    private final JsonMapper json=JsonMapper.builder().build();
    private final Map<String,ToolCallback> callbacks=new LinkedHashMap<>();
    private final List<ToolSource> sources;
    public ExecutionToolRegistry(List<Registration> extensions) {this(extensions,List.of());}
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionToolRegistry(List<Registration> extensions,List<ToolSource> sources) {
        this.sources=List.copyOf(sources);
        var registrations=new ArrayList<>(builtins());registrations.addAll(extensions);
        for(var registration:registrations) {
            if(callbacks.putIfAbsent(registration.name(),callback(registration))!=null)throw new IllegalArgumentException("重复的工具注册");
        }
    }
    public Set<String> names(){return Set.copyOf(callbacks.keySet());}
    public List<ToolCallback> callbacks(){return List.copyOf(callbacks.values());}
    public String descriptions(){return callbacks.values().stream().map(c->c.getToolDefinition().name()+": "+c.getToolDefinition().description()+" "+c.getToolDefinition().inputSchema()).reduce((a,b)->a+"\n"+b).orElse("");}
    private Map<String,ToolCallback> available(Scope scope) {
        var available=new LinkedHashMap<>(callbacks);
        for(var source:sources) for(var registration:source.registrations(scope))
            if(available.putIfAbsent(registration.name(),callback(registration))!=null)throw new IllegalArgumentException("重复的工具注册");
        return available;
    }
    public Set<String> names(Scope scope){return Set.copyOf(available(scope).keySet());}
    public String descriptions(Scope scope){return available(scope).values().stream().map(c->c.getToolDefinition().name()+": "+c.getToolDefinition().description()+" "+c.getToolDefinition().inputSchema()).reduce((a,b)->a+"\n"+b).orElse("");}
    public Map<String,Object> dispatch(AgentDecision decision,Scope scope,Dispatcher ledger) throws Exception {
        if(scope.identity()==null||!scope.identity().authenticated())throw new IllegalArgumentException("工具需要认证身份");
        var callback=available(scope).get(decision.tool());if(callback==null)throw new IllegalArgumentException("不允许的工具");
        var invocation=new Invocation(scope,ledger);
        try {callback.call(json.writeValueAsString(decision.arguments()),new ToolContext(Map.of("execution",invocation)));}
        catch(DispatchFailure failure){throw (Exception)failure.getCause();}
        return invocation.result;
    }
    private ToolCallback callback(Registration registration) {
        var definition=ToolDefinition.builder().name(registration.name()).description(registration.description()).inputSchema(registration.schema().json()).build();
        return new ToolCallback() {
            public ToolDefinition getToolDefinition(){return definition;}
            public String call(String input){throw new IllegalStateException("工具只能通过受控执行账本调用");}
            @SuppressWarnings("unchecked") public String call(String input,ToolContext context) {
                if(context==null||!(context.getContext().get("execution") instanceof Invocation invocation))return call(input);
                Map<String,Object> args=json.readValue(input,LinkedHashMap.class);
                registration.schema().validate(args);
                Plan plan=registration.planner().apply(Collections.unmodifiableMap(new LinkedHashMap<>(args)),invocation.scope);
                try {invocation.result=invocation.ledger.dispatch(plan);return json.writeValueAsString(invocation.result);}
                catch(RuntimeException e){throw e;}catch(Exception e){throw new DispatchFailure(e);}
            }
        };
    }
    private List<Registration> builtins() {
        return List.of(
            new Registration("rag.search","Retrieve evidence from the selected document conversation",Set.of("query"),(a,s)->{document(s);return new Plan("rag","/internal/rag/retrieve",Map.of("callId",s.callId(),"conversationId",s.documentConversationId(),"question",query(a,s)),false);}),
            new Registration("rag.answer","Answer and verify against selected documents",Set.of("query"),(a,s)->{document(s);return new Plan("rag","/internal/rag/answer",Map.of("callId",s.callId(),"conversationId",s.documentConversationId(),"question",query(a,s)),false);}),
            new Registration("rag.read_sections","Read a previously retrieved document source",Set.of("documentId","afterOrdinal","limit"),(a,s)->{
                document(s);String id=String.valueOf(a.get("documentId"));UUID.fromString(id);
                if(!s.retrievedDocuments().contains(id))throw new IllegalArgumentException("请先检索到该文档来源");
                long after=number(a.getOrDefault("afterOrdinal",-1));long limit=number(a.getOrDefault("limit",8));
                if(after< -1||limit<1||limit>8)throw new IllegalArgumentException("文档读取范围无效");
                return new Plan("rag","/internal/rag/documents/"+id+"/sections?conversationId="+s.documentConversationId()+"&afterOrdinal="+after+"&limit="+limit,null,true);
            }),
            new Registration("visual_memory.search","Search semantic visual memory owned by this user and tenant",Set.of("query"),(a,s)->new Plan("chat","/internal/chat/visual-memory",Map.of("query",query(a,s),"callId",s.callId()),false)),
            new Registration("media.create","Create an image only with explicit user authorization",Set.of("prompt","query"),(a,s)->{
                if(!s.allowImageGeneration()||s.conversationId()==null)throw new IllegalArgumentException("用户未授权图片生成工具");
                var body=new LinkedHashMap<String,Object>();body.put("conversationId",UUID.nameUUIDFromBytes(s.callId().getBytes(StandardCharsets.UTF_8)).toString());body.put("prompt",query(a,s));body.put("assetId",null);
                return new Plan("media","/internal/media/image-jobs",body,false);
            })
        );
    }
    private static void document(Scope s){if(s.documentConversationId()==null)throw new IllegalArgumentException("该工具需要已选择的文档会话");}
    private static String query(Map<String,Object> args,Scope scope){Object raw=args.getOrDefault("query",args.getOrDefault("prompt",scope.question()));if(!(raw instanceof String q)||q.isBlank()||q.length()>16000)throw new IllegalArgumentException("工具查询无效");return q;}
    private static long number(Object value){if(!(value instanceof Number n)||n.doubleValue()!=n.longValue())throw new IllegalArgumentException("工具数值参数无效");return n.longValue();}
    public record Registration(String name,String description,ToolSchema schema,BiFunction<Map<String,Object>,Scope,Plan> planner) {
        public Registration(String name,String description,Set<String> arguments,BiFunction<Map<String,Object>,Scope,Plan> planner){this(name,description,new ToolSchema(ToolSchema.simple(arguments)),planner);}
        public Registration(String name,String description,String schema,BiFunction<Map<String,Object>,Scope,Plan> planner){this(name,description,new ToolSchema(schema),planner);}
        public Registration {if(name==null||!name.matches("[a-z][a-z0-9_.]{1,63}")||description==null||description.isBlank()||description.length()>1000||planner==null||schema==null)throw new IllegalArgumentException("工具注册定义无效");}
    }
    public interface ToolSource {List<Registration> registrations(Scope scope);}
    public record Scope(ChatIdentity identity,String conversationId,String documentConversationId,boolean allowImageGeneration,String callId,String question,Set<String> retrievedDocuments) {public Scope {retrievedDocuments=Set.copyOf(retrievedDocuments);}}
    public record Plan(String target,String path,Map<String,Object> body,boolean get,com.bbb.exercise.agentdemo.orchestrator.mcp.McpCall mcp) {
        public Plan(String target,String path,Map<String,Object> body,boolean get){this(target,path,body,get,null);}
        public static Plan mcp(com.bbb.exercise.agentdemo.orchestrator.mcp.McpCall call,Map<String,Object> args){return new Plan("mcp","/mcp/"+call.serverId()+"/"+call.tool(),args,false,call);}
        public Plan {
            boolean valid=mcp==null?Set.of("rag","chat","media").contains(target)&&path.startsWith("/internal/"+target+"/"):
                    target.equals("mcp")&&!get&&path.equals("/mcp/"+mcp.serverId()+"/"+mcp.tool());
            if(!valid||path.contains("..")||path.contains("#"))throw new IllegalArgumentException("工具目标不在白名单");body=body==null?null:Collections.unmodifiableMap(new LinkedHashMap<>(body));
        }
    }
    @FunctionalInterface public interface Dispatcher {Map<String,Object> dispatch(Plan plan) throws Exception;}
    private static final class Invocation {final Scope scope;final Dispatcher ledger;Map<String,Object> result;Invocation(Scope scope,Dispatcher ledger){this.scope=scope;this.ledger=ledger;}}
    public static final class DispatchFailure extends RuntimeException {DispatchFailure(Exception cause){super(cause);}}
}
