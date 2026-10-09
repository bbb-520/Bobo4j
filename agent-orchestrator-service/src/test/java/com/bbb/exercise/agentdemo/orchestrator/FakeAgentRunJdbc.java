package com.bbb.exercise.agentdemo.orchestrator;

import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun;
import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun.RunStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试替身：用内存 Map 近似 {@code agent_run} 表。
 *
 * <p>它把生产 SQL 的"形状"固化为测试契约：
 * <ul>
 *   <li>INSERT 带 8 个占位符，含 {@code version} 列；</li>
 *   <li>状态推进的 UPDATE 带 5 个占位符，</li>
 * </ul>
 * 一旦生产实现丢失 {@code user_id} 或 {@code version} 条件，占位符数量/位置不再匹配，
 * 替身会返回 0 行受影响，乐观锁冲突分支随之被触发。
 *
 * <p>本类只服务测试，不代表任何生产路径。
 */
final class FakeAgentRunJdbc {
    private final Map<String, AgentRun> store = new ConcurrentHashMap<>();
    private final List<String> sqlLog = new ArrayList<>();
    private final AtomicBoolean versionedUpdateFails = new AtomicBoolean(false);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    FakeAgentRunJdbc() {
        // INSERT INTO agent_run(id,workflow,user_id,input_text,status,version,created_at,updated_at)
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    sqlLog.add((String) invocation.getArgument(0));
                    String id = (String) invocation.getArgument(1);
                    store.put(id, new AgentRun(UUID.fromString(id),
                            (String) invocation.getArgument(2),
                            (String) invocation.getArgument(3),
                            (String) invocation.getArgument(4),
                            RunStatus.valueOf((String) invocation.getArgument(5)),
                            ((Number) invocation.getArgument(6)).longValue(),
                            (Instant) invocation.getArgument(7),
                            (Instant) invocation.getArgument(8)));
                    return 1;
                });

        // UPDATE agent_run SET status=?,version=version+1,updated_at=?
        //   WHERE id=? AND user_id=? AND version=?
        when(jdbc.update(anyString(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    sqlLog.add((String) invocation.getArgument(0));
                    if (versionedUpdateFails.getAndSet(false)) {
                        return 0;
                    }
                    String id = (String) invocation.getArgument(3);
                    String userId = (String) invocation.getArgument(4);
                    long expectedVersion = ((Number) invocation.getArgument(5)).longValue();
                    AgentRun current = store.get(id);
                    if (current == null || !current.userId().equals(userId)
                            || current.version() != expectedVersion) {
                        return 0;
                    }
                    store.put(id, new AgentRun(current.id(), current.workflow(), current.userId(), current.input(),
                            RunStatus.valueOf((String) invocation.getArgument(1)),
                            current.version() + 1,
                            current.createdAt(),
                            (Instant) invocation.getArgument(2)));
                    return 1;
                });

        // SELECT ... FROM agent_run WHERE id=? AND user_id=?
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
                .thenAnswer(invocation -> {
                    String id = (String) invocation.getArgument(2);
                    String userId = (String) invocation.getArgument(3);
                    AgentRun run = store.get(id);
                    List<AgentRun> rows = new ArrayList<>();
                    if (run != null && run.userId().equals(userId)) {
                        rows.add(run);
                    }
                    return rows;
                });
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    /** 返回已执行过的 UPDATE/INSERT SQL，供测试断言"条件是否真的在 SQL 里"。 */
    List<String> sqlLog() {
        return List.copyOf(sqlLog);
    }

    /** 模拟另一个 Worker 抢先推进版本：下一次带 version 条件的 UPDATE 将影响 0 行。 */
    void simulateConcurrentModification() {
        versionedUpdateFails.set(true);
    }
}
