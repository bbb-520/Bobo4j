package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Persistent execution access is scoped by a browser identity or a fenced worker lease. */
class ExecutionOwnershipGateTest {
    private static final String PACKAGE = "com.bbb.exercise.agentdemo.orchestrator.execution.";

    @Test
    void browserOperationsRequireTheFullUserAndTenantIdentity() {
        assertFirstParameter(Set.of("create", "get", "byRequest", "stop", "resume", "events",
                        "unfinishedSteps", "reconcilableSteps"),
                "com.bbb.exercise.agentdemo.api.identity.ChatIdentity");
    }

    @Test
    void workerMutationsRequireAFencedLease() {
        assertFirstParameter(Set.of("renew", "beginStep", "finishStep", "complete", "terminal",
                        "terminalWithBudget", "saveBudget", "streamEvent", "reserveStepTokens",
                        "settleStepTokens", "accountStepTime"), PACKAGE + "ExecutionStore$Lease");
    }

    @Test
    void theWorkerLeaseBindsItsTokenToTheExecutionAndOwner() {
        Class<?> lease = ModuleClasses.load("agent-orchestrator-service", PACKAGE + "ExecutionStore$Lease");
        assertThat(lease.isRecord()).isTrue();
        assertThat(lease.getRecordComponents()).extracting(RecordComponent::getName)
                .containsExactly("executionId", "token", "identity");
        assertThat(lease.getRecordComponents()[2].getType().getName())
                .isEqualTo("com.bbb.exercise.agentdemo.api.identity.ChatIdentity");
    }

    private static void assertFirstParameter(Set<String> names, String requiredType) {
        Class<?> store = ModuleClasses.load("agent-orchestrator-service", PACKAGE + "ExecutionStore");
        for (String name : names) {
            var methods = Arrays.stream(store.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> method.getName().equals(name)).toList();
            assertThat(methods).as("persistent execution operation %s", name).isNotEmpty()
                    .allSatisfy(method -> {
                        assertThat(method.getParameterCount()).as(method.toString()).isPositive();
                        assertThat(method.getParameterTypes()[0].getName())
                                .as("owner boundary of %s", method).isEqualTo(requiredType);
                    });
        }
    }
}
