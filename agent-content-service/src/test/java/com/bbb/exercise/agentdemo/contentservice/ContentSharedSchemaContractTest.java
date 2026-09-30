package com.bbb.exercise.agentdemo.contentservice;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContentSharedSchemaContractTest {
    @Test
    void contentOwnsOnlyContentTablesAndPublishSnapshots() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__content_baseline.sql"))
                + Files.readString(Path.of("src/main/resources/db/migration/V2__publish_prompt_snapshot.sql"));

        assertThat(migration).doesNotContain("CREATE TABLE IF NOT EXISTS image_asset");
        assertThat(migration).doesNotContain("CREATE TABLE IF NOT EXISTS image_job");
        assertThat(migration).contains("source_prompt");
        assertThat(migration).doesNotContain("CREATE TABLE IF NOT EXISTS app_user");
        assertThat(migration).doesNotContain("CREATE TABLE IF NOT EXISTS auth_session");
    }
}
