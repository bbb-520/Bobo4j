package com.bbb.exercise.agentdemo.orchestrator.execution;

import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import com.networknt.schema.*;
import java.util.*;

/** Immutable draft-2020-12 schemas; no remote schema resolution or caller-owned identity. */
public final class ToolSchema {
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final Set<String> IDENTITY = Set.of("userId", "tenantId", "owner", "filter", "system");
    private final String json;
    private final Schema validator;
    private final boolean protectIdentity;

    public ToolSchema(String source) {this(source,true);}
    public ToolSchema(String source,boolean protectIdentity) {
        this.protectIdentity=protectIdentity;
        try {
            if (source == null || source.length() > 64_000) throw new IllegalArgumentException();
            JsonNode node = JSON.readTree(source);
            if (!node.isObject() || !"object".equals(node.path("type").asText())) throw new IllegalArgumentException();
            inspect(node, 0);
            if (node.has("$schema") && !"https://json-schema.org/draft/2020-12/schema".equals(node.path("$schema").asText())) throw new IllegalArgumentException();
            if(protectIdentity)for (String field : IDENTITY) if (node.path("properties").has(field)) throw new IllegalArgumentException();
            json = JSON.writeValueAsString(JSON.convertValue(node, Object.class));
            validator = SchemaRegistry.withDefaultDialect(com.networknt.schema.dialect.Dialects.getDraft202012()).getSchema(JSON.readTree(json));
        } catch (Exception error) { throw new IllegalArgumentException("工具参数定义无效或包含外部引用"); }
    }

    private static void inspect(JsonNode node, int depth) {
        if (depth > 32) throw new IllegalArgumentException();
        if (node.isObject()) {
            if (node.has("$id")) throw new IllegalArgumentException();
            for (String ref : List.of("$ref", "$dynamicRef"))
                if (node.has(ref) && !node.get(ref).asText().startsWith("#")) throw new IllegalArgumentException();
        }
        for (JsonNode child : node) inspect(child, depth + 1);
    }

    public void validate(Map<String,Object> args) {
        validate(args,8000);
    }
    /** Outputs are already wire-bounded; validate them before bounded evidence projection. */
    public void validateOutput(Map<String,Object> args) {validate(args,64_000);}
    private void validate(Map<String,Object> args,int maxCharacters) {
        try {
            if (args == null || protectIdentity&&args.keySet().stream().anyMatch(IDENTITY::contains)) throw new IllegalArgumentException();
            String encoded = JSON.writeValueAsString(args);
            if (encoded.length() > maxCharacters || !validator.validate(JSON.readTree(encoded)).isEmpty()) throw new IllegalArgumentException();
        } catch (Exception error) { throw new IllegalArgumentException("工具参数不符合定义"); }
    }
    public String json() { return json; }
    public String fingerprint() { return ExecutionStore.hash(json); }
    public static String canonical(Object value) {return JSON.writeValueAsString(value);}
    public static String simple(Set<String> arguments) {
        var properties = new TreeMap<String,Object>();
        arguments.forEach(a -> properties.put(a, Map.of("type", Set.of("limit","afterOrdinal").contains(a) ? "integer" : "string")));
        try { return JSON.writeValueAsString(Map.of("type","object","properties",properties,"additionalProperties",false)); }
        catch (Exception error) { throw new IllegalArgumentException("工具参数定义无效"); }
    }
}
