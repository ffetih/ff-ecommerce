package io.github.ffetih.ecommerce.customer.support;

import io.github.ffetih.ecommerce.testsupport.Infrastructure;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts PostgreSQL for integration tests; {@link ServiceConnection} points the datasource at it.
 * <p>
 * The container is a static singleton: test classes with different Spring contexts (for example a
 * mock web environment and a real port) share it instead of starting their own. Cached contexts
 * are closed when the JVM exits, so the shared instance stops only after all tests ran.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final PostgreSQLContainer POSTGRES = Infrastructure.postgres();

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }
}
