package com.bbb.exercise.agentdemo.common.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceModuleContractTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void businessServicesArePreparedForConfigurationPropertiesAndActuator() throws Exception {
        Map<String, String> services = Map.of(
                "agent-auth-service", "AuthServiceApplication.java",
                "agent-chat-service", "ChatServiceApplication.java",
                "agent-media-service", "MediaServiceApplication.java",
                "agent-content-service", "ContentServiceApplication.java",
                "agent-orchestrator-service", "OrchestratorApplication.java");

        for (var entry : services.entrySet()) {
            String pom = Files.readString(ROOT.resolve(entry.getKey()).resolve("pom.xml"));
            String source = Files.readString(ROOT.resolve(entry.getKey())
                    .resolve("src/main/java/com/bbb/exercise/agentdemo")
                    .resolve(entry.getValue().contains("Orchestrator") ? "orchestrator" : entry.getKey().replace("agent-", "").replace("-service", "") + "service")
                    .resolve(entry.getValue()));
            assertThat(pom).as(entry.getKey() + " actuator dependency")
                    .contains("spring-boot-starter-actuator");
            assertThat(source).as(entry.getKey() + " configuration properties scan")
                    .contains("@ConfigurationPropertiesScan");
        }
    }
}
