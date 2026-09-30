package com.bbb.exercise.agentdemo.orchestrator.service;

import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun;
import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun.RunStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentWorkflowService {
    private final ConcurrentHashMap<UUID, AgentRun> runs = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;

    public AgentWorkflowService() {
        this.jdbc = null;
    }

    @Autowired
    public AgentWorkflowService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                RunStatus.DRAFT, Instant.now(), Instant.now());
        if (jdbc == null) {
            runs.put(run.id(), run);
        } else {
            jdbc.update("INSERT INTO agent_run(id,workflow,user_id,input_text,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                    run.id().toString(), run.workflow(), run.userId(), run.input(), run.status().name(),
                    run.createdAt(), run.updatedAt());
        }
        return run;
    }

    public AgentRun advance(UUID id, RunStatus next) {
        return advance(id, next, null);
    }

    /** Advances a run after checking that the caller owns it. */
    public AgentRun advance(UUID id, RunStatus next, String userId) {
        if (userId != null) requireOwner(userId);
        if (jdbc != null) {
            AgentRun current = userId == null ? get(id) : get(id, userId);
            if (current == null) throw new IllegalArgumentException("AgentRun 不存在: " + id);
            if (!allowed(current.status(), next)) {
                throw new IllegalStateException("非法状态转移: " + current.status() + " -> " + next);
            }
            int updated = jdbc.update("UPDATE agent_run SET status=?,updated_at=? WHERE id=? AND status=?",
                    next.name(), Instant.now(), id.toString(), current.status().name());
            if (updated != 1) throw new IllegalStateException("AgentRun 状态已被其它 Worker 修改");
            return get(id);
        }
        return runs.compute(id, (key, current) -> {
            if (current == null) throw new IllegalArgumentException("AgentRun 不存在: " + id);
            if (userId != null && !current.userId().equals(userId)) {
                throw new IllegalArgumentException("AgentRun 不存在: " + id);
            }
            if (!allowed(current.status(), next)) {
                throw new IllegalStateException("非法状态转移: " + current.status() + " -> " + next);
            }
            return current.advance(next);
        });
    }

    public AgentRun get(UUID id) {
        return get(id, null);
    }

    /** Returns a run only when it belongs to the supplied owner. */
    public AgentRun get(UUID id, String userId) {
        if (userId != null) requireOwner(userId);
        if (jdbc != null) {
            String sql = "SELECT id,workflow,user_id,input_text,status,created_at,updated_at FROM agent_run WHERE id=?"
                    + (userId == null ? "" : " AND user_id=?");
            Object[] args = userId == null ? new Object[]{id.toString()} : new Object[]{id.toString(), userId};
            var rows = jdbc.query(sql,
                    (rs, rowNum) -> new AgentRun(UUID.fromString(rs.getString("id")), rs.getString("workflow"),
                            rs.getString("user_id"), rs.getString("input_text"),
                            RunStatus.valueOf(rs.getString("status")),
                            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                    args);
            return rows.isEmpty() ? null : rows.get(0);
        }
        AgentRun run = runs.get(id);
        return run != null && (userId == null || run.userId().equals(userId)) ? run : null;
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
