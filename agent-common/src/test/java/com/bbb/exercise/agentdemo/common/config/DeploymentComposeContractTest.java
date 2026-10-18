package com.bbb.exercise.agentdemo.common.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises Compose's path resolution and exposure contract without a Docker daemon. */
class DeploymentComposeContractTest {
    private final Path root = Path.of("..").toAbsolutePath().normalize();

    @Test
    void publicEntryResolvesEveryApplicationBuildFromRepositoryRoot() throws Exception {
        JsonNode services = compose(Map.of()).path("services");
        int applications = 0;
        for (JsonNode service : services) {
            if (service.has("build")) {
                applications++;
                Path context = Path.of(service.path("build").path("context").asText());
                assertThat(context).isEqualTo(root);
                assertThat(Files.isRegularFile(context.resolve(service.path("build").path("dockerfile").asText())))
                        .as("Dockerfile accessible from application build context").isTrue();
            }
        }
        assertThat(applications).isEqualTo(7);
    }

    @Test
    void infrastructurePortsDefaultToLoopbackAndAllowAnExplicitBindOverride() throws Exception {
        assertInfrastructureBind(compose(Map.of()), "127.0.0.1");
        assertInfrastructureBind(compose(Map.of("INFRA_BIND_ADDRESS", "192.0.2.10")), "192.0.2.10");
    }

    @Test
    void monitoringCanLoadAllRulesAndReachItsAlertmanagerWithoutPrivateFiles() throws Exception {
        JsonNode services = compose(Map.of()).path("services");
        JsonNode prometheus = services.path("prometheus");
        for (String target : new String[] {"/etc/prometheus/prometheus.yml", "/etc/prometheus/alerts.yml",
                "/etc/prometheus/operations-alerts.yml"}) {
            assertReadableBind(prometheus, target);
        }
        assertThat(services.has("alertmanager")).isTrue();
        assertReadableBind(services.path("alertmanager"), "/etc/alertmanager/alertmanager.yml");
        assertThat(services.path("alertmanager").path("ports").get(0).path("host_ip").asText())
                .isEqualTo("127.0.0.1");
    }

    @Test
    void productionProfileAndRotatingAuthKeysPreserveExplicitlyClearedLegacyKey() throws Exception {
        JsonNode services = compose(Map.of("APP_ACTIVE_PROFILES", "production",
                "API_KEY_ENCRYPTION_KEYS", "next=synthetic-key-ring-fixture",
                "API_KEY_ACTIVE_KEY_ID", "next", "API_KEY_ENCRYPTION_KEY", "")).path("services");
        for (JsonNode service : services) {
            if (service.has("build")) {
                assertThat(service.path("environment").path("SPRING_PROFILES_ACTIVE").asText()).isEqualTo("production");
            }
        }
        JsonNode auth = services.path("agent-auth-service").path("environment");
        assertThat(auth.path("API_KEY_ENCRYPTION_KEYS").asText()).isEqualTo("next=synthetic-key-ring-fixture");
        assertThat(auth.path("API_KEY_ACTIVE_KEY_ID").asText()).isEqualTo("next");
        assertThat(auth.path("API_KEY_ENCRYPTION_KEY").asText()).isEmpty();
    }

    private void assertInfrastructureBind(JsonNode model, String address) {
        for (String name : new String[] {"nacos", "sentinel-dashboard", "mysql", "redis", "milvus", "prometheus", "zipkin", "alertmanager"}) {
            for (JsonNode port : model.path("services").path(name).path("ports")) {
                assertThat(port.path("host_ip").asText()).as("host binding of %s", name).isEqualTo(address);
            }
        }
    }

    private void assertReadableBind(JsonNode service, String target) {
        boolean found = false;
        for (JsonNode volume : service.path("volumes")) {
            if (target.equals(volume.path("target").asText())) {
                found = true;
                assertThat(volume.path("read_only").asBoolean()).isTrue();
                assertThat(Files.isRegularFile(Path.of(volume.path("source").asText())))
                        .as("mounted file for %s", target).isTrue();
            }
        }
        assertThat(found).as("mount for %s", target).isTrue();
    }

    private JsonNode compose(Map<String, String> overrides) throws Exception {
        try {
            Process probe = new ProcessBuilder("docker", "compose", "version").redirectErrorStream(true).start();
            assumeTrue(probe.waitFor(15, TimeUnit.SECONDS) && probe.exitValue() == 0, "Docker Compose CLI required; no daemon needed");
        } catch (java.io.IOException unavailable) {
            assumeTrue(false, "Docker Compose CLI unavailable");
        }
        assertThat(Files.isRegularFile(root.resolve("compose.yml"))).as("public deployment entry").isTrue();
        assertThat(Files.isRegularFile(root.resolve(".env.example"))).as("safe environment template").isTrue();
        ProcessBuilder builder = new ProcessBuilder("docker", "compose", "--env-file", root.resolve(".env.example").toString(),
                "-f", root.resolve("compose.yml").toString(), "config", "--format", "json").directory(root.toFile());
        // Ambient operator configuration must not change this synthetic, non-secret contract fixture.
        for (String file : new String[] {"infra/docker-compose.yml", "docker-compose.app.yml"}) {
            var variables = java.util.regex.Pattern.compile("\\$\\{([A-Z0-9_]+)").matcher(Files.readString(root.resolve(file)));
            while (variables.find()) {
                builder.environment().remove(variables.group(1));
            }
        }
        builder.environment().putAll(overrides);
        Path errors = Files.createTempFile("compose-contract-", ".err");
        try {
            Process process = builder.redirectError(errors.toFile()).start();
            byte[] json = process.getInputStream().readAllBytes();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("Compose configuration completed").isTrue();
            assertThat(process.exitValue()).as("Compose config exit status; private environment is never printed").isZero();
            return new ObjectMapper().readTree(json);
        } finally {
            Files.deleteIfExists(errors);
        }
    }
}
