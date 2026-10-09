package com.bbb.exercise.agentdemo.orchestrator;

import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun.RunStatus;
import com.bbb.exercise.agentdemo.orchestrator.service.AgentWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 固化 A0 对 Orchestrator 状态机的要求：
 * 走持久化、必须带 owner、必须带 version 乐观锁、不得存在无 owner 的推进入口。
 */
class AgentRunOwnershipAndVersionContractTest {

    @Test
    void serviceOnlyAcceptsJdbcTemplateAndKeepsNoInMemoryFallback() {
        var constructors = AgentWorkflowService.class.getDeclaredConstructors();

        assertThat(constructors)
                .as("只允许一个构造器，且必须注入 JdbcTemplate（禁止无参构造 + 内存回退）")
                .hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(JdbcTemplate.class);
        assertThat(AgentWorkflowService.class.getDeclaredFields())
                .as("生产实现不得再持有 ConcurrentHashMap 之类的内存回退")
                .noneMatch(field -> Map.class.isAssignableFrom(field.getType()));
    }

    @Test
    void advanceAlwaysRequiresOwnerArgument() {
        assertThat(Arrays.stream(AgentWorkflowService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("advance")))
                .as("必须删除 advance(id, next) 这种 userId=null 的无 owner 重载")
                .isNotEmpty()
                .allMatch(method -> method.getParameterCount() == 3);
    }

    @Test
    void stateUpdateCarriesOwnerAndVersionConditions() {
        FakeAgentRunJdbc fake = new FakeAgentRunJdbc();
        AgentWorkflowService service = new AgentWorkflowService(fake.jdbc());

        var run = service.start("REVISE", "draft", "u1");
        assertThat(run.version()).isZero();

        var advanced = service.advance(run.id(), RunStatus.REVIEWING, "u1");
        assertThat(advanced.status()).isEqualTo(RunStatus.REVIEWING);
        assertThat(advanced.version()).isEqualTo(1L);

        assertThat(fake.sqlLog())
                .as("状态更新必须同时带 user_id 与 version 条件，并自增 version")
                .anyMatch(sql -> sql.contains("user_id=?") && sql.contains("version=?")
                        && sql.contains("version=version+1"));
    }

    @Test
    void foreignOwnerCannotReadOrAdvance() {
        FakeAgentRunJdbc fake = new FakeAgentRunJdbc();
        AgentWorkflowService service = new AgentWorkflowService(fake.jdbc());
        var run = service.start("REVISE", "draft", "u1");

        assertThat(service.get(run.id(), "u2")).isNull();
        assertThatThrownBy(() -> service.advance(run.id(), RunStatus.REVIEWING, "u2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        assertThat(service.get(run.id(), "u1")).isNotNull();
    }

    @Test
    void staleVersionIsReportedAsConcurrencyConflict() {
        FakeAgentRunJdbc fake = new FakeAgentRunJdbc();
        AgentWorkflowService service = new AgentWorkflowService(fake.jdbc());
        var run = service.start("REVISE", "draft", "u1");

        fake.simulateConcurrentModification();
        assertThatThrownBy(() -> service.advance(run.id(), RunStatus.REVIEWING, "u1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Worker");
    }

    @Test
    void illegalTransitionIsRejected() {
        FakeAgentRunJdbc fake = new FakeAgentRunJdbc();
        AgentWorkflowService service = new AgentWorkflowService(fake.jdbc());
        var run = service.start("REVISE", "draft", "u1");

        assertThatThrownBy(() -> service.advance(run.id(), RunStatus.APPROVED, "u1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法状态转移");
    }

    @Test
    void anonymousOwnerIsRejected() {
        FakeAgentRunJdbc fake = new FakeAgentRunJdbc();
        AgentWorkflowService service = new AgentWorkflowService(fake.jdbc());

        assertThatThrownBy(() -> service.start("REVISE", "draft", "anonymous"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户身份");
        assertThatThrownBy(() -> service.start("REVISE", "draft", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户身份");
    }
}
