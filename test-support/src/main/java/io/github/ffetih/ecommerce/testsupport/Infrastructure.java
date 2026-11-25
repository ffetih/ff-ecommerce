package io.github.ffetih.ecommerce.testsupport;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Single source of truth for the container images used in integration tests.
 * Versions match compose.yaml, so tests run against the same infrastructure as local development.
 */
public final class Infrastructure {

    public static final String POSTGRES_IMAGE = "postgres:17-alpine";
    public static final String KAFKA_IMAGE = "apache/kafka:4.1.0";

    private Infrastructure() {
    }

    public static PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }

    public static KafkaContainer kafka() {
        return new KafkaContainer(KAFKA_IMAGE);
    }
}
