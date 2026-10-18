package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic source inventories exercise namespace-independent duplicate ownership. */
class DuplicateSourceDiscoveryTest {
    @Test
    void detectsCurrentNamespaceDuplicatesAndEveryAdditionalOwner() {
        var duplicates = ArchitectureState.duplicateMainSourceOwners(List.of(
                "agent-rag-service/src/main/java/com/bbb/exercise/agentdemo/shared/Copied.java",
                "agent-media-service/src/main/java/com/bbb/exercise/agentdemo/shared/Copied.java",
                "agent-content-service/src/main/java/com/bbb/exercise/agentdemo/shared/Copied.java",
                "agent-rag-service/src/main/java/com/bbb/exercise/agentdemo/ragservice/Unique.java",
                "agent-media-service/src/main/java/com/bbb/exercise/agentdemo/mediaservice/Unique.java",
                "agent-chat-service/src/test/java/com/bbb/exercise/agentdemo/shared/Copied.java",
                "agent-media-service/src/test/java/com/bbb/exercise/agentdemo/ragservice/Unique.java"));

        assertThat(duplicates).containsOnlyKeys("com/bbb/exercise/agentdemo/shared/Copied.java");
        assertThat(duplicates.get("com/bbb/exercise/agentdemo/shared/Copied.java"))
                .isEqualTo(Set.of("agent-rag-service", "agent-media-service", "agent-content-service"));
    }

    @Test
    void retainsLegacyCoverageAndCoversOtherProductionNamespaces() {
        var duplicates = ArchitectureState.duplicateMainSourceOwners(List.of(
                "agent-rag-service/src/main/java/com/bbb/exercise/agentdemo1_0/Legacy.java",
                "agent-media-service/src/main/java/com/bbb/exercise/agentdemo1_0/Legacy.java",
                "agent-rag-service/src/main/java/org/example/Copied.java",
                "agent-media-service/src/main/java/org/example/Copied.java",
                "agent-content-service/src/main/java/org/example/Unique.java"));

        assertThat(duplicates).containsOnlyKeys(
                "com/bbb/exercise/agentdemo1_0/Legacy.java", "org/example/Copied.java");
    }
}
