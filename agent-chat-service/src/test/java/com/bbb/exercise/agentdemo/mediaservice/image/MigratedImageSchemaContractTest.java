package com.bbb.exercise.agentdemo.mediaservice.image;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MigratedImageSchemaContractTest {
    @Test
    void chatDoesNotBootstrapMediaOwnedTables() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__chat_baseline.sql"));

        assertThat(migration).doesNotContain("CREATE TABLE IF NOT EXISTS image_asset");
        assertThat(migration).doesNotContain("CREATE TABLE IF NOT EXISTS image_job");
        assertThat(migration).doesNotContain("CREATE TABLE IF NOT EXISTS app_user");
        assertThat(migration).doesNotContain("CREATE TABLE IF NOT EXISTS auth_session");
    }
}
