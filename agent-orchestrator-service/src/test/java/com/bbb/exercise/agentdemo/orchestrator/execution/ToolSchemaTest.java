package com.bbb.exercise.agentdemo.orchestrator.execution;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ToolSchemaTest {
    private static final String SCHEMA = """
        {"type":"object","properties":{"filters":{"type":"object","properties":{
        "kind":{"type":"string","enum":["article","code"]},
        "ids":{"type":"array","minItems":1,"maxItems":3,"items":{"type":"integer","minimum":1}}},
        "required":["kind","ids"],"additionalProperties":false}},"required":["filters"],"additionalProperties":false}
        """;

    @Test void nestedArraysAndEnumsArePreservedAndValidatedBeforeDispatch() throws Exception {
        var registration = new ExecutionToolRegistry.Registration("test.nested", "Nested read", SCHEMA,
                (args, scope) -> new ExecutionToolRegistry.Plan("chat", "/internal/chat/test", args, false));
        var registry = new ExecutionToolRegistry(List.of(registration));
        var scope = new ExecutionToolRegistry.Scope(new com.bbb.exercise.agentdemo.api.identity.ChatIdentity("t", "u", true), null, null, false, "call", "question", Set.of());
        var valid = Map.<String,Object>of("filters", Map.of("kind", "code", "ids", List.of(1, 2)));
        assertThat(registry.dispatch(new AgentDecision("CALL_TOOL", "test.nested", valid, null), scope, p -> p.body())).isEqualTo(valid);
        for (var bad : List.of(Map.<String,Object>of(), Map.<String,Object>of("filters", Map.of("kind", "wrong", "ids", List.of(1))),
                Map.<String,Object>of("filters", Map.of("kind", "code", "ids", List.of(0))),
                Map.<String,Object>of("filters", Map.of("kind", "code", "ids", List.of(1), "extra", true)))) {
            assertThatThrownBy(() -> registry.dispatch(new AgentDecision("CALL_TOOL", "test.nested", bad, null), scope,
                    p -> { throw new AssertionError("invalid arguments reached IO"); })).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(registry.callbacks().stream().filter(c -> c.getToolDefinition().name().equals("test.nested")).findFirst().orElseThrow()
                .getToolDefinition().inputSchema()).contains("minItems", "required", "enum");
    }

    @Test void remoteSchemaReferencesCannotTriggerNetworkAccess() {
        assertThatThrownBy(() -> new ExecutionToolRegistry.Registration("test.remote", "Remote ref",
                "{\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":\"http://127.0.0.1:1/secret\"}}}",
                (a,s) -> null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void explicitlyNullableToolParametersSurviveDecisionParsingAndDispatch() throws Exception {
        var registration=new ExecutionToolRegistry.Registration("test.nullable","Optional input",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":[\"string\",\"null\"]}},\"required\":[\"query\"],\"additionalProperties\":false}",
                (a,s)->new ExecutionToolRegistry.Plan("chat","/internal/chat/test",a,false));
        var registry=new ExecutionToolRegistry(List.of(registration));
        var decision=AgentDecision.parse(tools.jackson.databind.json.JsonMapper.builder().build(),"{\"type\":\"CALL_TOOL\",\"tool\":\"test.nullable\",\"arguments\":{\"query\":null}}",registry.names());
        var scope=new ExecutionToolRegistry.Scope(new com.bbb.exercise.agentdemo.api.identity.ChatIdentity("t","u",true),null,null,false,"call","question",Set.of());
        var result=registry.dispatch(decision,scope,p->p.body());
        assertThat(result).containsKey("query");assertThat(result.get("query")).isNull();
    }
    @Test void schemaValidToolArgumentsAreNotRestrictedToEightTopLevelFields() throws Exception {
        var registry=new ExecutionToolRegistry(List.of(new ExecutionToolRegistry.Registration("test.fields","Read fields",
                "{\"type\":\"object\",\"additionalProperties\":{\"type\":\"integer\"}}",(a,s)->new ExecutionToolRegistry.Plan("chat","/internal/chat/test",a,false))));
        var decision=AgentDecision.parse(tools.jackson.databind.json.JsonMapper.builder().build(),
                "{\"type\":\"CALL_TOOL\",\"tool\":\"test.fields\",\"arguments\":{\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5,\"f\":6,\"g\":7,\"h\":8,\"i\":9,\"j\":10}}",registry.names());
        var scope=new ExecutionToolRegistry.Scope(new com.bbb.exercise.agentdemo.api.identity.ChatIdentity("t","u",true),null,null,false,"call","question",Set.of());
        assertThat(registry.dispatch(decision,scope,p->p.body())).hasSize(10).containsEntry("a",1).containsEntry("j",10);
    }
}
