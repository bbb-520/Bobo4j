package com.bbb.exercise.agentdemo.contentservice;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContentPersistenceSchemaTest {
    @Test
    void contentMigrationContainsOwnerVersionAndPublishIdempotencyGuards() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__content_baseline.sql"));

        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS bobo_world_item");
        assertThat(migration).contains("source_job_id CHAR(36) NOT NULL");
        assertThat(migration).contains("version INT NOT NULL DEFAULT 1");
        assertThat(migration).contains("uk_bobo_world_owner_job");
        assertThat(migration).contains("idx_bobo_world_cleanup");
    }
}
