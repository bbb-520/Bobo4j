package com.bbb.exercise.agentdemo.orchestrator;

import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun;
import com.bbb.exercise.agentdemo.orchestrator.service.AgentWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkflowPersistenceTest {
    @Test
    void startsWorkflowWithDurableRunInsert() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        AgentWorkflowService service = new AgentWorkflowService(jdbc);
        AgentRun run = service.start("REVISE", "draft", "user-1");

        assertThat(run.status()).isEqualTo(AgentRun.RunStatus.DRAFT);
        assertThat(run.version()).isZero();
        verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
