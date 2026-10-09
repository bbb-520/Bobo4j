package com.bbb.exercise.agentdemo.orchestrator.domain;

import java.time.Instant;
import java.util.UUID;

public record AgentRun(UUID id, String workflow, String userId, String input,
                       RunStatus status, long version, Instant createdAt, Instant updatedAt) {

    public enum RunStatus {
        DRAFT, REVIEWING, REVISING, VALIDATING, APPROVED, REJECTED
    }
}
