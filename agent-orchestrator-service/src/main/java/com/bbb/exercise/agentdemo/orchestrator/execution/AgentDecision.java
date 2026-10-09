package com.bbb.exercise.agentdemo.orchestrator.execution;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** A decision contains data only. Tool dispatch belongs to ExecutionWorker. */
public record AgentDecision(String type,String tool,Map<String,Object> arguments,String answer) {
    public static final Set<String> TOOLS=Set.of("rag.search","rag.answer","rag.read_sections","visual_memory.search","media.create");
    public static AgentDecision parse(ObjectMapper mapper,String text) {
        try {
            var node=mapper.readTree(text);
            if(!node.isObject()||!node.has("type"))throw new IllegalArgumentException();
            String type=node.path("type").asText();
            Set<String> fields=new HashSet<>();node.properties().forEach(entry->fields.add(entry.getKey()));
            if(type.equals("CALL_TOOL")) {
                if(!fields.equals(Set.of("type","tool","arguments"))||!node.path("arguments").isObject())throw new IllegalArgumentException();
                String tool=node.path("tool").asText();if(!TOOLS.contains(tool))throw new IllegalArgumentException("不允许的工具");
                Map<String,Object> args=mapper.convertValue(node.get("arguments"),mapper.getTypeFactory().constructMapType(LinkedHashMap.class,String.class,Object.class));
                if(args.size()>8||mapper.writeValueAsString(args).length()>8000||args.keySet().stream().anyMatch(k->Set.of("userId","tenantId","owner","filter","system").contains(k)))throw new IllegalArgumentException();
                return new AgentDecision(type,tool,Map.copyOf(args),null);
            }
            if(type.equals("FINAL")) {
                if(!fields.equals(Set.of("type","answer"))||!node.path("answer").isString()||node.path("answer").asText().isBlank())throw new IllegalArgumentException("最终答案不能为空");
                return new AgentDecision(type,null,Map.of(),node.path("answer").asText());
            }
            if(type.equals("PAUSE")&&fields.equals(Set.of("type","answer")))return new AgentDecision(type,null,Map.of(),node.path("answer").asText());
            throw new IllegalArgumentException();
        } catch(Exception e) {throw new IllegalArgumentException("模型决策无效："+(e.getMessage()!=null&&e.getMessage().contains("工具")?"工具不在白名单":e.getMessage()!=null&&e.getMessage().contains("答案")?"答案不能为空":"只允许一个符合结构的决策"));}
    }
}
