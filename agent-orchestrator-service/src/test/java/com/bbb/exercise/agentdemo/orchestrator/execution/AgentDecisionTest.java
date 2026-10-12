package com.bbb.exercise.agentdemo.orchestrator.execution;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 验证模型决策仅接受声明的动作结构，拒绝混合动作及自报身份。 */
class AgentDecisionTest {
    @Test
    void acceptsOnlyDeclaredDecisionShapes() {
        var json = JsonMapper.builder().build();

        assertThat(
                        AgentDecision.parse(
                                        json,
                                        "{\"type\":\"CALL_TOOL\",\"tool\":\"rag.search\",\"arguments\":{\"query\":\"期限\"}}")
                                .tool())
                .isEqualTo("rag.search");
        assertThatThrownBy(
                        () ->
                                AgentDecision.parse(
                                        json,
                                        "{\"type\":\"CALL_TOOL\",\"tool\":\"shell.exec\",\"arguments\":{}}"))
                .hasMessageContaining("工具");
        assertThatThrownBy(() -> AgentDecision.parse(json, "{\"type\":\"FINAL\",\"answer\":\"\"}"))
                .hasMessageContaining("答案");
        assertThatThrownBy(() -> AgentDecision.parse(json, "```json\n{}\n```"))
                .hasMessageContaining("决策");
    }

    @Test
    void providerOutputCannotDeclareMultipleActionsOrIdentity() {
        var json = JsonMapper.builder().build();
        assertThatThrownBy(
                        () ->
                                AgentDecision.parse(
                                        json,
                                        "{\"type\":\"CALL_TOOL\",\"tool\":\"rag.search\",\"answer\":\"fake\",\"arguments\":{\"userId\":\"someone\"}}"))
                .hasMessageContaining("决策");
    }
}
