package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** REVISE retirement must remove its runtime surface without removing execution or schema history. */
class RetiredWorkflowContractTest {
    @Test
    void reviseRuntimeImplementationIsRetired() {
        String source = "agent-orchestrator-service/src/main/java/com/bbb/exercise/agentdemo/orchestrator/";
        for (String file : new String[] {"web/AgentWorkflowController.java",
                "service/AgentWorkflowService.java", "domain/AgentRun.java", "auth/AuthSessionClient.java"}) {
            assertThat(Repository.exists(source + file)).as("retired REVISE implementation %s", file)
                    .isFalse();
        }
    }

    @Test
    void gatewayAndPermissionPolicyExposeOnlyPersistentExecutions() {
        String routes = Repository.text(Repository.resolve(
                "agent-gateway/src/main/java/com/bbb/exercise/agentdemo/gateway/config/GatewayRoutes.java"));
        String permissions = Repository.text(Repository.resolve(
                "agent-runtime/src/main/java/com/bbb/exercise/agentdemo/runtime/security/PermissionPolicy.java"));
        assertThat(routes).contains("/api/agent-executions/**").doesNotContain("/api/agent-runs");
        assertThat(permissions).contains("/api/agent-executions").doesNotContain("/api/agent-runs");
    }

    @Test
    void durableExecutionAndHistoricalMigrationsRemainAvailable() {
        String module = "agent-orchestrator-service/src/main/";
        for (String file : new String[] {
                "java/com/bbb/exercise/agentdemo/orchestrator/execution/ExecutionController.java",
                "java/com/bbb/exercise/agentdemo/orchestrator/execution/ExecutionWorker.java",
                "java/com/bbb/exercise/agentdemo/orchestrator/execution/ExecutionStore.java",
                "resources/db/migration/V1__orchestrator_baseline.sql",
                "resources/db/migration/V2__agent_run_version.sql",
                "resources/db/migration/V3__persistent_executions.sql"}) {
            assertThat(Repository.exists(module + file)).as("preserved execution asset %s", file)
                    .isTrue();
        }
    }
}
