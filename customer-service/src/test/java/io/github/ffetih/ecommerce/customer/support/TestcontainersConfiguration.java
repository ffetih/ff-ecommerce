package io.github.ffetih.ecommerce.customer.support;

import io.github.ffetih.ecommerce.testsupport.Infrastructure;
import io.github.ffetih.ecommerce.testsupport.TestTokenIssuer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts PostgreSQL for integration tests ({@link ServiceConnection} points the datasource at it)
 * and an in-process token issuer that signs the tests' access tokens.
 * <p>
 * Both are static singletons: test classes with different Spring contexts share them instead of
 * starting their own. Cached contexts are closed when the JVM exits, after all tests ran.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final PostgreSQLContainer POSTGRES = Infrastructure.postgres();
    static final TestTokenIssuer TOKENS = TestTokenIssuer.start();

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    @Bean
    DynamicPropertyRegistrar tokenIssuer() {
        return registry -> registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TOKENS::issuerUri);
    }

    public static TestTokenIssuer tokens() {
        return TOKENS;
    }
}
