package com.bbb.exercise.agentdemo.orchestrator.execution;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.time.Instant;
import static com.bbb.exercise.agentdemo.orchestrator.execution.ExecutionStore.*;

/** Step checkpoints and consumption; every mutation retains the owner's row lock and transaction. */
final class ExecutionSteps {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ExecutionStore owner;
    ExecutionSteps(JdbcTemplate jdbc, TransactionTemplate tx, ExecutionStore owner) {
        this.jdbc=jdbc; this.tx=tx; this.owner=owner;
    }
    private Row lock(Lease lease) {return owner.lock(lease);}
    private void saveBudgetLocked(String id, ExecutionBudget budget) {owner.saveBudgetLocked(id,budget);}
    private Instant now() {return owner.now();}
    private String write(Object value) {return owner.write(value);}
    private Map<String,Object> map(String value) {return owner.map(value);}

    public Step beginStep(
            Lease lease,
            String key,
            String parameterHash,
            String callId,
            String target,
            String path,
            Object request,
            String consumption,
            String toolName) {
        return tx.execute(
                s -> {
                    lock(lease);
                    var old = steps(lease.executionId(), key);

                    if (!old.isEmpty()) {
                        var step = old.getFirst();

                        if (!step.hash().equals(parameterHash) || !step.callId().equals(callId)) {
                            throw conflict("步骤参数不一致");
                        }

                        return step;
                    }

                    String id = UUID.randomUUID().toString();
                    var at = now();
                    String body = request == null ? null : write(request);

                    jdbc.update(
                            "INSERT INTO agent_execution_step(id,execution_id,step_key,parameter_hash,call_id,status,target,request_path,request_json,consumption,tool_name,created_at,updated_at) VALUES(?,?,?,?,?,'DISPATCHED',?,?,?,?,?,?,?)",
                            id,
                            lease.executionId(),
                            key,
                            parameterHash,
                            callId,
                            target,
                            path,
                            body,
                            consumption,
                            toolName,
                            stamp(at),
                            stamp(at));

                    return new Step(
                            id,
                            key,
                            parameterHash,
                            callId,
                            "DISPATCHED",
                            Map.of(),
                            0,
                            target,
                            path,
                            body,
                            consumption,
                            toolName);
                });
    }

    public void finishStep(
            Lease lease, Step step, Map<String, Object> result, ExecutionBudget budget) {
        tx.executeWithoutResult(
                s -> {
                    lock(lease);
                    jdbc.update(
                            "UPDATE agent_execution_step SET status='SUCCEEDED',result_json=?,updated_at=? WHERE id=? AND execution_id=? AND status='DISPATCHED'",
                            write(result),
                            stamp(now()),
                            step.id(),
                            lease.executionId());
                    saveBudgetLocked(lease.executionId(), budget);
                });
    }

    public void accountStepTime(Lease lease, Step step, long attemptMillis) {
        if (attemptMillis < 0) {
            throw bad("执行时长无效");
        }

        tx.executeWithoutResult(
                s -> {
                    var row = lock(lease);
                    long stored =
                            jdbc.queryForObject(
                                    "SELECT active_millis FROM agent_execution_step WHERE id=? AND execution_id=?",
                                    Long.class,
                                    step.id(),
                                    lease.executionId());
                    long total = Math.addExact(step.activeMillis(), attemptMillis),
                            delta = Math.max(0, total - stored);

                    if (delta > 0) {
                        jdbc.update(
                                "UPDATE agent_execution_step SET active_millis=?,updated_at=? WHERE id=?",
                                total,
                                stamp(now()),
                                step.id());
                        saveBudgetLocked(lease.executionId(), row.budget().charge(0, 0, delta));
                    }
                });
    }

    public long reserveStepTokens(Lease lease, Step step) {
        return tx.execute(
                s -> {
                    var row = lock(lease);
                    long held =
                            jdbc.queryForObject(
                                    "SELECT reserved_tokens FROM agent_execution_step WHERE id=? AND execution_id=?",
                                    Long.class,
                                    step.id(),
                                    lease.executionId());

                    if (held > 0) {
                        return held;
                    }

                    long remaining = row.budget().availableTokens();

                    if (remaining <= 0) {
                        throw conflict("剩余token预算不足");
                    }

                    jdbc.update(
                            "UPDATE agent_execution_step SET reserved_tokens=? WHERE id=?",
                            remaining,
                            step.id());
                    saveBudgetLocked(lease.executionId(), row.budget().reserve(remaining));

                    return remaining;
                });
    }

    public void settleStepTokens(Lease lease, Step step, long input, long output, long unknown) {
        if (input < 0 || output < 0 || unknown < 0) {
            throw bad("模型用量无效");
        }

        tx.executeWithoutResult(
                s -> {
                    var row = lock(lease);
                    var saved =
                            jdbc.queryForMap(
                                    "SELECT accounted_input_tokens,accounted_output_tokens,reserved_tokens,unknown_tokens FROM agent_execution_step WHERE id=? AND execution_id=?",
                                    step.id(),
                                    lease.executionId());
                    long oldInput = ((Number) saved.get("accounted_input_tokens")).longValue(),
                            oldOutput = ((Number) saved.get("accounted_output_tokens")).longValue();
                    long held = ((Number) saved.get("reserved_tokens")).longValue(),
                            oldUnknown = ((Number) saved.get("unknown_tokens")).longValue();
                    long settledInput = Math.max(input, oldInput),
                            settledOutput = Math.max(output, oldOutput);
                    var budget =
                            row.budget()
                                    .charge(settledInput - oldInput, settledOutput - oldOutput, 0)
                                    .withReserved(
                                            row.budget().reservedTokens()
                                                    - held
                                                    - oldUnknown
                                                    + unknown);

                    jdbc.update(
                            "UPDATE agent_execution_step SET accounted_input_tokens=?,accounted_output_tokens=?,unknown_tokens=?,reserved_tokens=0 WHERE id=?",
                            settledInput,
                            settledOutput,
                            unknown,
                            step.id());
                    saveBudgetLocked(lease.executionId(), budget);
                });
    }

    public List<Step> completedSteps(Lease lease) {
        owner.leased(lease);
        return jdbc.query(
                "SELECT * FROM agent_execution_step WHERE execution_id=? AND status='SUCCEEDED' ORDER BY created_at,id",
                stepMapper(),
                lease.executionId());
    }

    private List<Step> steps(String id, String key) {
        return jdbc.query(
                "SELECT * FROM agent_execution_step WHERE execution_id=? AND step_key=?",
                stepMapper(),
                id,
                key);
    }

    RowMapper<Step> stepMapper() {
        return (rs, n) ->
                new Step(
                        rs.getString("id"),
                        rs.getString("step_key"),
                        rs.getString("parameter_hash"),
                        rs.getString("call_id"),
                        rs.getString("status"),
                        rs.getString("result_json") == null
                                ? Map.of()
                                : map(rs.getString("result_json")),
                        rs.getLong("active_millis"),
                        rs.getString("target"),
                        rs.getString("request_path"),
                        rs.getString("request_json"),
                        rs.getString("consumption"),
                        rs.getString("tool_name"));
    }
}
