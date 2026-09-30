package com.bbb.exercise.agentdemo.orchestrator;

import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun.RunStatus;
import com.bbb.exercise.agentdemo.orchestrator.service.AgentWorkflowService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentWorkflowServiceTest {
    @Test
    void reviseWorkflowAdvancesInOrder() {
        AgentWorkflowService service = new AgentWorkflowService();
        var run = service.start("REVISE", "draft", "u1");
        run = service.advance(run.id(), RunStatus.REVIEWING);
        run = service.advance(run.id(), RunStatus.REVISING);
        run = service.advance(run.id(), RunStatus.VALIDATING);
        run = service.advance(run.id(), RunStatus.APPROVED);
        assertThat(run.status()).isEqualTo(RunStatus.APPROVED);
    }

    @Test
    void rejectsIllegalTransition() {
        AgentWorkflowService service = new AgentWorkflowService();
        var run = service.start("REVISE", "draft", "u1");
        assertThatThrownBy(() -> service.advance(run.id(), RunStatus.APPROVED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAnonymousOrMissingOwner() {
        AgentWorkflowService service = new AgentWorkflowService();
        assertThatThrownBy(() -> service.start("REVISE", "draft", "anonymous"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户身份");
        assertThatThrownBy(() -> service.start("REVISE", "draft", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户身份");
    }

    @Test
    void ownerCannotReadOrAdvanceAnotherOwnersRun() {
        AgentWorkflowService service = new AgentWorkflowService();
        var run = service.start("REVISE", "draft", "u1");

        assertThat(service.get(run.id(), "u2")).isNull();
        assertThatThrownBy(() -> service.advance(run.id(), RunStatus.REVIEWING, "u2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        assertThat(service.get(run.id(), "u1")).isNotNull();
    }
}
