package com.bbb.exercise.agentdemo.orchestrator;

import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun.RunStatus;
import com.bbb.exercise.agentdemo.orchestrator.service.AgentWorkflowService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentWorkflowServiceTest {

    private static AgentWorkflowService service() {
        return new AgentWorkflowService(new FakeAgentRunJdbc().jdbc());
    }

    @Test
    void reviseWorkflowAdvancesInOrder() {
        AgentWorkflowService service = service();
        var run = service.start("REVISE", "draft", "u1");
        run = service.advance(run.id(), RunStatus.REVIEWING, "u1");
        run = service.advance(run.id(), RunStatus.REVISING, "u1");
        run = service.advance(run.id(), RunStatus.VALIDATING, "u1");
        run = service.advance(run.id(), RunStatus.APPROVED, "u1");
        assertThat(run.status()).isEqualTo(RunStatus.APPROVED);
    }

    @Test
    void rejectsIllegalTransition() {
        AgentWorkflowService service = service();
        var run = service.start("REVISE", "draft", "u1");
        assertThatThrownBy(() -> service.advance(run.id(), RunStatus.APPROVED, "u1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAnonymousOrMissingOwner() {
        AgentWorkflowService service = service();
        assertThatThrownBy(() -> service.start("REVISE", "draft", "anonymous"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户身份");
        assertThatThrownBy(() -> service.start("REVISE", "draft", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户身份");
    }

    @Test
    void ownerCannotReadOrAdvanceAnotherOwnersRun() {
        AgentWorkflowService service = service();
        var run = service.start("REVISE", "draft", "u1");

        assertThat(service.get(run.id(), "u2")).isNull();
        assertThatThrownBy(() -> service.advance(run.id(), RunStatus.REVIEWING, "u2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        assertThat(service.get(run.id(), "u1")).isNotNull();
    }
}
