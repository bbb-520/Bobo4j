package com.bbb.exercise.agentdemo.common.infrastructure;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Smoke-level contract for the infrastructure boundary used by the services. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RUN_INFRASTRUCTURE_TESTS", matches = "true")
class InfrastructureContainersTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @Container
    /** LocalStack's S3 endpoint gives the test suite an OSS-compatible object store. */
    static final GenericContainer<?> OSS = new GenericContainer<>("localstack/localstack:3.8")
            .withEnv("SERVICES", "s3")
            .withEnv("DEFAULT_REGION", "us-east-1")
            .withExposedPorts(4566);

    @Test
    void infrastructureContainersExposeUsableEndpoints() {
        Assertions.assertTrue(MYSQL.isRunning());
        Assertions.assertTrue(REDIS.isRunning());
        Assertions.assertTrue(OSS.isRunning());
        Assertions.assertNotNull(REDIS.getHost());
        Assertions.assertTrue(REDIS.getMappedPort(6379) > 0);
        Assertions.assertTrue(OSS.getMappedPort(4566) > 0);
    }
}
