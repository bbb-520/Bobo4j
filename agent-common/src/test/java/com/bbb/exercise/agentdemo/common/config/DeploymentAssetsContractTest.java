package com.bbb.exercise.agentdemo.common.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Deployment artifacts are part of the platform contract, not optional local files. */
class DeploymentAssetsContractTest {
    @Test
    void deploymentAssetsRemainPresentAtRepositoryRoot() {
        Path root = Path.of("..").toAbsolutePath().normalize();
        for (String relative : new String[]{
                ".env.example",
                "Dockerfile.service",
                "docker-compose.app.yml",
                "infra/docker-compose.yml",
                "scripts/build-images.ps1",
                "scripts/import-nacos.ps1",
                "scripts/smoke-test.ps1"}) {
            assertThat(Files.isRegularFile(root.resolve(relative)))
                    .as("deployment asset %s", relative).isTrue();
        }
    }

    @Test
    void composeWiresContentToMediaAndProvidesOssSettings() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String compose = Files.readString(root.resolve("docker-compose.app.yml"));
        assertThat(compose).contains("MEDIA_SERVICE_URL: http://agent-media-service:18082");
        assertThat(compose).contains("OSS_ENABLED: ${OSS_ENABLED:-false}");
        assertThat(compose).contains("ALIYUN_OSS_BUCKET: ${ALIYUN_OSS_BUCKET:-}");
        assertThat(compose).contains("ALIYUN_OSS_ACCESS_KEY_ID: ${ALIYUN_OSS_ACCESS_KEY_ID:-}");
        assertThat(compose).contains("ALIYUN_OSS_ACCESS_KEY_SECRET: ${ALIYUN_OSS_ACCESS_KEY_SECRET:-}");
    }

    @Test
    void historicalMigrationDocumentsAreNotRequiredDeploymentAssets() {
        Path root = Path.of("..").toAbsolutePath().normalize();
        for (String relative : new String[]{
                "docs/superpowers/specs/2026-09-30-strangler-migration-design.md",
                "docs/superpowers/specs/2026-10-01-hard-cutover-design.md",
                "docs/superpowers/plans/2026-09-30-strangler-migration-execution.md",
                "docs/superpowers/plans/2026-10-01-current-state-refactor-plan.md",
                "docs/superpowers/plans/2026-10-01-hard-cutover-plan.md",
                "docs/2026-10-01-codex-hardening-prompt.md",
                "docs/2026-10-01-gateway-auth-boundary.md",
                "docs/2026-10-01-phase-a0-report.md",
                "docs/2026-10-01-phase-a1-report.md",
                "docs/2026-10-01-phase-a2-report.md",
                "docs/2026-10-01-phase-b1-report.md",
                "docs/2026-10-01-phase0-inventory.md",
                "docs/2026-10-01-workspace-state-review.md"}) {
            assertThat(Files.exists(root.resolve(relative)))
                    .as("historical migration document %s", relative).isFalse();
        }
    }
}
