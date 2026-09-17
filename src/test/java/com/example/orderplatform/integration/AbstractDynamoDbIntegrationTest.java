package com.example.orderplatform.integration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared DynamoDB Local container for integration tests.
 * <p>
 * Uses the official DynamoDB Local image via Testcontainers, so no external
 * Docker Compose setup is required for tests (Docker must be running and
 * reachable by Testcontainers).
 * <p>
 * {@code disabledWithoutDocker = true} skips these tests (rather than failing)
 * when no Docker daemon is reachable, so the unit-test build stays green on
 * machines without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractDynamoDbIntegrationTest {

    protected static final int DYNAMODB_PORT = 8000;

    @Container
    protected static final GenericContainer<?> DYNAMODB = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:latest"))
            .withExposedPorts(DYNAMODB_PORT)
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory");

    protected static String dynamoDbEndpoint() {
        return "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(DYNAMODB_PORT);
    }
}
