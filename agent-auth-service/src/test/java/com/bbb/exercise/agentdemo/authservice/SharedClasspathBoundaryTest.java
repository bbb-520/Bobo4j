package com.bbb.exercise.agentdemo.authservice;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;

/** A shadowed class makes behavior depend on jar ordering instead of the shared contract. */
class SharedClasspathBoundaryTest {
    @Test
    void modelAndHealthContractsHaveExactlyOneDefinitionOnRuntimeClasspath() throws Exception {
        for (String resource : new String[] {
                "com/bbb/exercise/agentdemo1_0/model/ModelProfile.class",
                "com/bbb/exercise/agentdemo1_0/model/ModelProviderRegistry.class",
                "com/bbb/exercise/agentdemo1_0/health/HealthController.class",
                "com/bbb/exercise/agentdemo1_0/identity/ChatIdentityResolver.class"}) {
            assertThat(Collections.list(getClass().getClassLoader().getResources(resource)))
                    .as("Non-shadowed runtime contract: %s", resource).hasSize(1);
        }
    }
}
