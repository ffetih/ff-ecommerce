package io.github.ffetih.ecommerce.catalog.support;

import io.github.ffetih.ecommerce.testsupport.Infrastructure;
import io.github.ffetih.ecommerce.testsupport.TestTokenIssuer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts PostgreSQL and Kafka for integration tests; {@link ServiceConnection} points the datasource
 * and the Kafka clients at them.
 * <p>
 * The containers are static singletons: test classes with different Spring contexts (for example a
 * mock web environment and a real port) share them instead of starting their own. Cached contexts
 * are closed when the JVM exits, so the shared instances stop only after all tests ran.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final PostgreSQLContainer POSTGRES = Infrastructure.postgres();
    static final KafkaContainer KAFKA = Infrastructure.kafka();
    static final TestTokenIssuer TOKENS = TestTokenIssuer.start();

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return KAFKA;
    }

    /** Points the resource server at the in-process token issuer, which signs the tests' tokens. */
    @Bean
    DynamicPropertyRegistrar tokenIssuer() {
        return registry -> registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TOKENS::issuerUri);
    }

    public static TestTokenIssuer tokens() {
        return TOKENS;
    }

    public static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }
}
