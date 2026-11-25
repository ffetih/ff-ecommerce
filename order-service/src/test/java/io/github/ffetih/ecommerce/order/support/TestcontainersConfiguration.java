package io.github.ffetih.ecommerce.order.support;

import io.github.ffetih.ecommerce.testsupport.Infrastructure;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL and Kafka for integration tests, shared by all test classes (static singletons). */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final PostgreSQLContainer POSTGRES = Infrastructure.postgres();
    static final KafkaContainer KAFKA = Infrastructure.kafka();

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

    public static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }
}
