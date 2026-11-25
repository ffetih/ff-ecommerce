package io.github.ffetih.ecommerce.order.support;

import io.github.ffetih.ecommerce.testsupport.Infrastructure;
import io.github.ffetih.ecommerce.testsupport.TestTokenIssuer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL, Kafka and a token issuer for integration tests, shared by all test classes (static
 * singletons). The issuer also knows the {@code order-service} client, so the service can get its
 * own tokens for calls to customer-service and catalog-service, as it does from Keycloak.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final PostgreSQLContainer POSTGRES = Infrastructure.postgres();
    static final KafkaContainer KAFKA = Infrastructure.kafka();
    static final TestTokenIssuer TOKENS = TestTokenIssuer.start()
            .registerClient("order-service", "order-service-secret", "catalog-service", "customer-service");

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

    @Bean
    DynamicPropertyRegistrar tokenIssuer() {
        return registry -> {
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TOKENS::issuerUri);
            // No background expiry scans in tests: they would change orders other tests are watching.
            // OrderExpiryIT calls the expiry directly, with a time of its choosing.
            registry.add("ecommerce.orders.expiry.enabled", () -> "false");
        };
    }

    public static TestTokenIssuer tokens() {
        return TOKENS;
    }

    public static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }
}
