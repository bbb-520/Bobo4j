package com.bbb.exercise.agentdemo.mcp;

import com.bbb.exercise.agentdemo.mcp.schema.ToolSchema;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Remote data never supplies platform accounting; redact values without rewriting JSON syntax. */
final class McpResultNormalizer {
    private final JsonMapper json=JsonMapper.builder().build();
    private final List<String> secrets;
    McpResultNormalizer(Collection<String> secrets){this.secrets=secrets.stream().filter(s->!s.isBlank()).toList();}
    @SuppressWarnings("unchecked") Map<String,Object> normalize(McpCall call,McpSchema.CallToolResult result,ToolSchema output) {
        return normalize(call,result,output,McpDocumentPolicy.STANDARD,Map.of());
    }
    @SuppressWarnings("unchecked") Map<String,Object> normalize(McpCall call,McpSchema.CallToolResult result,ToolSchema output,McpDocumentPolicy policy,Map<String,Object> arguments) {
        var text=new StringBuilder();
        if(result.content()!=null)for(var content:result.content())if(content instanceof McpSchema.TextContent value)text.append(value.text()).append('\n');
        String rendered=text.toString().strip();Object structured=result.structuredContent();boolean error=Boolean.TRUE.equals(result.isError());
        if(!error&&output!=null) {
            try {if(!(structured instanceof Map<?,?>))throw new IllegalArgumentException();output.validateOutput((Map<String,Object>)structured);}
            catch(IllegalArgumentException invalid){rendered="MCP_OUTPUT_SCHEMA_INVALID";structured=null;error=true;}
        }
        if(!error&&rendered.isBlank()&&(structured==null||policy!=McpDocumentPolicy.STANDARD&&policy!=McpDocumentPolicy.LEARN_SEARCH)) {
            rendered="MCP_EMPTY_RESULT";structured=null;error=true;
        }
        boolean truncated=false;
        if(!error&&policy!=McpDocumentPolicy.STANDARD) {
            try {var evidence=policy.project(rendered,structured,arguments);rendered=evidence.text();structured=evidence.structured();truncated=evidence.truncated();}
            catch(IllegalArgumentException invalid){rendered="MCP_DOCUMENT_RESULT_INVALID";structured=null;error=true;}
        }
        try {
            if(rendered.length()+json.writeValueAsString(structured).length()>16_000)throw new IllegalArgumentException();
            rendered=redact(rendered);structured=scrub(structured,0);
            if(rendered.length()+json.writeValueAsString(structured).length()>16_000)throw new IllegalArgumentException();
        } catch(IllegalArgumentException tooLarge){rendered="MCP_RESULT_TOO_LARGE";structured=null;error=true;}
        if(rendered.isBlank()&&structured==null){rendered="MCP_EMPTY_RESULT";error=true;}
        var response=new LinkedHashMap<String,Object>();response.put("text",rendered);response.put("isError",error);
        if(truncated)response.put("truncated",true);
        if(structured!=null)response.put("structuredContent",structured);
        response.put("source",Map.of("serverId",call.serverId(),"tool",call.tool(),"schemaHash",call.schemaHash()));
        response.put("inputTokens",0L);response.put("outputTokens",0L);response.put("unknownTokens",0L);
        return response;
    }
    private String redact(String text){for(String secret:secrets)text=text.replace(secret,"[redacted]");return text;}
    private String redactKey(String key){for(String secret:secrets)if(secret.length()>=8||key.equals(secret))key=key.replace(secret,"[redacted]");return key;}
    private Object scrub(Object value,int depth) {
        if(depth>32)throw new IllegalArgumentException();
        if(value instanceof String text)return redact(text);
        if(value instanceof Map<?,?> map){var copy=new LinkedHashMap<String,Object>();map.forEach((k,v)->copy.put(redactKey(String.valueOf(k)),scrub(v,depth+1)));return copy;}
        if(value instanceof List<?> values)return values.stream().map(v->scrub(v,depth+1)).toList();
        return value;
    }
}
