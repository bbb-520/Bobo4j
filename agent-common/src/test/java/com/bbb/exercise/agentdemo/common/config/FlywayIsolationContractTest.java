package com.bbb.exercise.agentdemo.common.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayIsolationContractTest {
    private static final Map<String, String> SERVICES = Map.of(
            "agent-auth-service", "flyway_schema_history_auth",
            "agent-chat-service", "flyway_schema_history_chat",
            "agent-media-service", "flyway_schema_history_media",
            "agent-content-service", "flyway_schema_history_content",
            "agent-orchestrator-service", "flyway_schema_history_orchestrator"
    );

    @Test
    void sharedDatabaseServicesUseIndependentFlywayHistoryTables() throws Exception {
        for (var entry : SERVICES.entrySet()) {
            String yaml = Files.readString(Path.of("..", entry.getKey(), "src/main/resources/application.yml"));
            assertThat(yaml).as(entry.getKey()).contains("table: " + entry.getValue());
        }
    }
}
