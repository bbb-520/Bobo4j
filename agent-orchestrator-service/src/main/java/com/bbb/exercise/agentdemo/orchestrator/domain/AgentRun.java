package com.bbb.exercise.agentdemo.orchestrator.domain;

import java.time.Instant;
import java.util.UUID;

/** 工作流运行记录，以所属用户和版本号约束流程状态的读取与推进。 */
public record AgentRun(
        UUID id,
        String workflow,
        String userId,
        String input,
        RunStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** 工作流状态集合，描述草稿、评审、修订、验证和最终审定阶段。 */
    public enum RunStatus {
        DRAFT,
        REVIEWING,
        REVISING,
        VALIDATING,
        APPROVED,
        REJECTED
    }
}
