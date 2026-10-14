package com.bbb.exercise.agentdemo.mediaservice.image;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.net.URI;
import java.util.UUID;
import java.time.Duration;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in, local MySQL 8 migration and concurrency coverage. Supply MEDIA_TEST_MYSQL_URL,
 * MEDIA_TEST_MYSQL_USERNAME and MEDIA_TEST_MYSQL_PASSWORD for a disposable local instance.
 * Each case creates and removes only its own randomly named schema; production URLs are refused.
 */
@EnabledIfEnvironmentVariable(named = "MEDIA_TEST_MYSQL_URL", matches = "jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/.*")
class ImageJobMySqlHardeningTest extends ImageJobHardeningTest {
    @Override ImageJobDatabase createDatabase() { return new MysqlDatabase(); }

    @AfterEach void removeOwnSchema() { ((MysqlDatabase) db).close(); }

    @Test void integrationActuallyExercisesDifferentJvmAndDatabaseClocks() {
        assertThat(Duration.between(db.now(), LocalDateTime.now()).abs().toHours())
                .as("run the integration JVM in Asia/Shanghai against the disposable UTC MySQL server")
                .isGreaterThanOrEqualTo(1);
    }

    static class MysqlDatabase extends ImageJobDatabase {
        private final PreparedDatabase prepared;
        MysqlDatabase() { this(prepare()); }
        private MysqlDatabase(PreparedDatabase prepared) {
            super(migrate(prepared), false);
            this.prepared = prepared;
        }
        void close() { prepared.admin().execute("DROP DATABASE `" + prepared.schema() + "`"); }

        private static PreparedDatabase prepare() {
            String url = System.getenv("MEDIA_TEST_MYSQL_URL");
            if (url == null || !url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/.*")) {
                throw new IllegalArgumentException("a disposable local MySQL instance is required");
            }
            String user = System.getenv("MEDIA_TEST_MYSQL_USERNAME");
            String password = System.getenv("MEDIA_TEST_MYSQL_PASSWORD");
            JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
            String schema = "media_hardening_test_" + UUID.randomUUID().toString().replace("-", "");
            admin.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            URI address = URI.create(url.substring("jdbc:".length()));
            String dedicated = "jdbc:mysql://" + address.getRawAuthority() + "/" + schema
                    + (address.getRawQuery() == null ? "" : "?" + address.getRawQuery());
            return new PreparedDatabase(admin, schema, new DriverManagerDataSource(dedicated, user, password));
        }

        private static JdbcTemplate migrate(PreparedDatabase prepared) {
            try {
                Flyway.configure().dataSource(prepared.datasource()).locations("classpath:db/migration")
                        .table("flyway_schema_history_media").load().migrate();
                return new JdbcTemplate(prepared.datasource());
            } catch (RuntimeException failure) {
                prepared.admin().execute("DROP DATABASE `" + prepared.schema() + "`");
                throw failure;
            }
        }
    }

    record PreparedDatabase(JdbcTemplate admin, String schema, DriverManagerDataSource datasource) {}
}
