package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.GroupState;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owned reads and lock acquisition; callers retain the transaction and its lock order. */
final class ModelCallGroupRepository {
    private final JdbcTemplate jdbc;

    ModelCallGroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Map<String, Object> wallet(String user) {
        var rows =
                jdbc.queryForList("SELECT * FROM billing_wallet WHERE user_id=? FOR UPDATE", user);

        if (rows.isEmpty()) {
            throw new BillingException(404, "账户不存在");
        }

        return rows.getFirst();
    }

    Map<String, Object> group(String user, String tenant, String id) {
        var rows = jdbc.queryForList("SELECT * FROM model_call_group WHERE id=?", id);

        if (rows.isEmpty()) {
            throw new BillingException(404, "计费组不存在");
        }

        var g = rows.getFirst();

        if (!Objects.equals(user, g.get("user_id"))
                || !Objects.equals(tenant, g.get("tenant_id"))) {
            throw new BillingException(404, "计费组不存在");
        }

        return g;
    }

    Map<String, Object> lockBudget() {
        jdbc.queryForList("SELECT * FROM model_fault_budget WHERE id=1 FOR UPDATE");
        jdbc.update(
                "UPDATE model_fault_budget SET window_spent_micros=0,window_started=CURRENT_TIMESTAMP WHERE id=1 AND window_started<TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP)");

        return jdbc.queryForList("SELECT * FROM model_fault_budget WHERE id=1").getFirst();
    }

    List<Map<String, Object>> attemptRows(String id, String role) {
        return jdbc.queryForList(
                "SELECT * FROM model_call_attempt WHERE group_id=? AND role=?", id, role);
    }

    Map<String, Object> attemptRow(String id, String role) {
        var rows = attemptRows(id, role);

        if (rows.isEmpty()) {
            throw new BillingException(409, "模型尝试不存在");
        }

        return rows.getFirst();
    }

    GroupState state(Map<String, Object> g) {
        long input = 0, output = 0, unknown = 0;
        String id = (String) g.get("id");
        var attempts =
                jdbc.queryForList(
                        "SELECT status,planned_token_upper,input_tokens,output_tokens FROM model_call_attempt WHERE group_id=? UNION ALL SELECT status,planned_token_upper,input_tokens,output_tokens FROM model_call_attempt_history WHERE group_id=?",
                        id,
                        id);

        for (var a : attempts) {
            if (a.get("input_tokens") != null && a.get("output_tokens") != null) {
                input = Math.addExact(input, num(a, "input_tokens"));
                output = Math.addExact(output, num(a, "output_tokens"));
            } else if (List.of("DISPATCHED", "UNKNOWN", "FAILED", "RECONCILED")
                    .contains(a.get("status"))) {
                unknown = Math.addExact(unknown, num(a, "planned_token_upper"));
            }
        }

        return new GroupState(
                id,
                (String) g.get("status"),
                (String) g.get("receipt"),
                (String) g.get("winning_role"),
                (int) num(g, "generation"),
                input,
                output,
                unknown);
    }

    private static long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
