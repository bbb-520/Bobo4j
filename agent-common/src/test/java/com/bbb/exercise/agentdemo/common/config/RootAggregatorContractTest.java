package com.bbb.exercise.agentdemo.common.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证仓库根模块只承担聚合职责，不持有应用源码。 */
class RootAggregatorContractTest {
    @Test
    void rootDoesNotOwnApplicationSources() throws Exception {
        Path repositoryRoot = locateRepositoryRoot();

        assertThat(Files.exists(repositoryRoot.resolve("src"))).isFalse();
        assertThat(Files.exists(repositoryRoot.resolve("agent-chat-legacy"))).isFalse();
    }

    private static Path locateRepositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("agent-api"))) {
                return candidate;
            }

            candidate = candidate.getParent();
        }

        throw new IllegalStateException("Unable to locate the BoboWorld4J repository root");
    }
}
