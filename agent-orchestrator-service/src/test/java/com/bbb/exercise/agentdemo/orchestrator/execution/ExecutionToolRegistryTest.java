package com.bbb.exercise.agentdemo.orchestrator.execution;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ExecutionToolRegistryTest {
    ExecutionToolRegistry.Scope scope(boolean image,String document) {return new ExecutionToolRegistry.Scope(new ChatIdentity("t","u",true),"conv",document,image,"stable-call","question",Set.of());}
    @Test void springAiCallbacksCannotBeInvokedWithoutTheTrustedExecutionLedger() {
        var tools=new ExecutionToolRegistry(List.of());
        assertThat(tools.callbacks()).hasSize(5);
        for(var callback:tools.callbacks())assertThatThrownBy(()->callback.call("{}" )).isInstanceOf(IllegalStateException.class);
    }
    @Test void authorizationAndArgumentWhitelistRunBeforeDispatch() {
        var tools=new ExecutionToolRegistry(List.of());
        assertThatThrownBy(()->tools.dispatch(new AgentDecision("CALL_TOOL","media.create",Map.of("prompt","cat"),null),scope(false,null),p->{throw new AssertionError("unauthorized dispatch");})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tools.dispatch(new AgentDecision("CALL_TOOL","rag.search",Map.of(),null),scope(false,null),p->{throw new AssertionError("unscoped dispatch");})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tools.dispatch(new AgentDecision("CALL_TOOL","visual_memory.search",Map.of("userId","victim"),null),scope(false,null),p->{throw new AssertionError("identity override");})).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void newSpringRegisteredToolUsesTheSameLedgerDispatchPort() throws Exception {
        var extension=new ExecutionToolRegistry.Registration("test.echo","Test extension",Set.of("query"),(args,scope)->new ExecutionToolRegistry.Plan("chat","/internal/chat/echo",Map.of("question",args.get("query")),false));
        var registry=new ExecutionToolRegistry(List.of(extension));
        var decision=AgentDecision.parse(tools.jackson.databind.json.JsonMapper.builder().build(),"{\"type\":\"CALL_TOOL\",\"tool\":\"test.echo\",\"arguments\":{\"query\":\"hello\"}}",registry.names());
        var result=registry.dispatch(decision,scope(false,null),plan->{assertThat(plan.target()).isEqualTo("chat");return Map.of("text",plan.body().get("question"));});
        assertThat(result).containsEntry("text","hello");
    }
}
