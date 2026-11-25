package io.github.ffetih.ecommerce.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceServerSettingsTest {

    private static final String ISSUER = "http://localhost:8180/realms/ecommerce";

    @Test
    void startsWithAnIssuerAndAnAudience() {
        var environment = new MockEnvironment()
                .withProperty(ResourceServerSecurity.ISSUER_URI_PROPERTY, ISSUER)
                .withProperty(ResourceServerSecurity.AUDIENCES_PROPERTY, "catalog-service");

        assertThatCode(() -> ResourceServerSecurity.requireValidationSettings(environment)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAudiencesWrittenAsAYamlList() {
        var environment = new MockEnvironment()
                .withProperty(ResourceServerSecurity.ISSUER_URI_PROPERTY, ISSUER)
                .withProperty(ResourceServerSecurity.AUDIENCES_PROPERTY + "[0]", "catalog-service");

        assertThatCode(() -> ResourceServerSecurity.requireValidationSettings(environment)).doesNotThrowAnyException();
    }

    @Test
    void refusesToStartWithoutAnAudience() {
        var environment = new MockEnvironment().withProperty(ResourceServerSecurity.ISSUER_URI_PROPERTY, ISSUER);

        assertThatThrownBy(() -> ResourceServerSecurity.requireValidationSettings(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ResourceServerSecurity.AUDIENCES_PROPERTY);
    }

    @Test
    void refusesToStartWithoutAnIssuer() {
        var environment = new MockEnvironment().withProperty(ResourceServerSecurity.AUDIENCES_PROPERTY, "catalog-service");

        assertThatThrownBy(() -> ResourceServerSecurity.requireValidationSettings(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ResourceServerSecurity.ISSUER_URI_PROPERTY);
    }
}
