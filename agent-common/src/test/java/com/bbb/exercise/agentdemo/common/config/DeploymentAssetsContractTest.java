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
                "scripts/smoke-test.ps1",
                "docs/superpowers/specs/2026-09-30-strangler-migration-design.md"}) {
            assertThat(Files.isRegularFile(root.resolve(relative)))
                    .as("deployment asset %s", relative).isTrue();
        }
    }
}
