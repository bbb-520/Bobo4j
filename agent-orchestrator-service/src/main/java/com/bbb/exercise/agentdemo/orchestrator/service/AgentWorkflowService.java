package com.bbb.exercise.agentdemo.orchestrator.service;

import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun;
import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun.RunStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Orchestrator 的持久化工作流状态机。
 *
 * <p>生产实现只走 JDBC：没有内存回退，没有无 owner 的入口。
 * 状态推进使用 {@code user_id} + {@code version} 双重条件做乐观锁，
 * 影响行数不为 1 时视为并发冲突（设计文档 §13）。
 */
@Service
public class AgentWorkflowService {
    private final JdbcTemplate jdbc;

    public AgentWorkflowService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate 不能为空");
    }

    public AgentRun start(String workflow, String input, String userId) {
        if (!"REVISE".equalsIgnoreCase(workflow)) {
            throw new IllegalArgumentException("不支持的工作流: " + workflow);
        }
        requireOwner(userId);
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("工作流输入不能为空");
        }
        AgentRun run = new AgentRun(UUID.randomUUID(), "REVISE", userId, input,
                RunStatus.DRAFT, 0L, Instant.now(), Instant.now());
        jdbc.update("INSERT INTO agent_run(id,workflow,user_id,input_text,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
                run.id().toString(), run.workflow(), run.userId(), run.input(), run.status().name(),
                run.version(), run.createdAt(), run.updatedAt());
        return run;
    }

    /**
     * 只推进调用者拥有的 run。owner 与 version 都是必须满足的条件，
     * 因此越权推进和陈旧版本推进都会失败。
     */
    public AgentRun advance(UUID id, RunStatus next, String userId) {
        requireOwner(userId);
        AgentRun current = get(id, userId);
        if (current == null) {
            throw new IllegalArgumentException("AgentRun 不存在: " + id);
        }
        if (!allowed(current.status(), next)) {
            throw new IllegalStateException("非法状态转移: " + current.status() + " -> " + next);
        }
        int updated = jdbc.update("UPDATE agent_run SET status=?,version=version+1,updated_at=? WHERE id=? AND user_id=? AND version=?",
                next.name(), Instant.now(), id.toString(), userId, current.version());
        if (updated != 1) {
            throw new IllegalStateException("AgentRun 状态已被其它 Worker 修改");
        }
        AgentRun advanced = get(id, userId);
        if (advanced == null) {
            throw new IllegalStateException("AgentRun 推进后不可读: " + id);
        }
        return advanced;
    }

    /** 只返回属于指定 owner 的 run；不存在或不属于该 owner 时返回 null。 */
    public AgentRun get(UUID id, String userId) {
        requireOwner(userId);
        var rows = jdbc.query("""
                        SELECT id,workflow,user_id,input_text,status,version,created_at,updated_at
                        FROM agent_run WHERE id=? AND user_id=?
                        """,
                (rs, rowNum) -> new AgentRun(UUID.fromString(rs.getString("id")), rs.getString("workflow"),
                        rs.getString("user_id"), rs.getString("input_text"),
                        RunStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                id.toString(), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void requireOwner(String userId) {
        if (userId == null || userId.isBlank() || "anonymous".equalsIgnoreCase(userId.trim())) {
            throw new IllegalArgumentException("用户身份不能为空");
        }
    }

    private boolean allowed(RunStatus current, RunStatus next) {
        return switch (current) {
            case DRAFT -> next == RunStatus.REVIEWING;
            case REVIEWING -> next == RunStatus.REVISING || next == RunStatus.APPROVED;
            case REVISING -> next == RunStatus.VALIDATING;
            case VALIDATING -> next == RunStatus.APPROVED || next == RunStatus.REJECTED;
            case APPROVED, REJECTED -> false;
        };
    }
}
