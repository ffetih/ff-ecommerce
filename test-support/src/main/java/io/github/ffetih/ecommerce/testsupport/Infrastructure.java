package io.github.ffetih.ecommerce.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Single source of truth for the container images used in integration tests.
 * Versions match compose.yaml, so tests run against the same infrastructure as local development.
 */
public final class Infrastructure {

    public static final String POSTGRES_IMAGE = "postgres:17-alpine";
    public static final String KAFKA_IMAGE = "apache/kafka:4.1.0";
    public static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.8.2";

    private Infrastructure() {
    }

    public static PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }

    public static KafkaContainer kafka() {
        return new KafkaContainer(KAFKA_IMAGE);
    }

    /**
     * Keycloak in dev mode, importing the realm files in {@code realmDirectory} on start, as in
     * compose.yaml. HTTP on container port 8080; health on the management port 9000.
     */
    public static GenericContainer<?> keycloak(Path realmDirectory) {
        return new GenericContainer<>(DockerImageName.parse(KEYCLOAK_IMAGE))
                .withCommand("start-dev", "--import-realm")
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "kcadmin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "kcadmin")
                .withEnv("KC_HEALTH_ENABLED", "true")
                .withEnv("JAVA_OPTS_KC_HEAP", "-Xms256m -Xmx512m")
                .withCopyFileToContainer(MountableFile.forHostPath(realmDirectory), "/opt/keycloak/data/import/")
                .withExposedPorts(8080, 9000)
                .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));
    }
}
