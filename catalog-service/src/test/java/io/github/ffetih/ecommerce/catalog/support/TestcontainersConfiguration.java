package io.github.ffetih.ecommerce.catalog.support;

import io.github.ffetih.ecommerce.testsupport.Infrastructure;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts PostgreSQL for integration tests; {@link ServiceConnection} points the datasource at it,
 * so no connection properties are needed in tests.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return Infrastructure.postgres();
    }
}
