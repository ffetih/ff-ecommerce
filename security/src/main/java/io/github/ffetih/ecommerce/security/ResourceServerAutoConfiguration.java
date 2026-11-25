package io.github.ffetih.ecommerce.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import tools.jackson.databind.json.JsonMapper;

/** Registers {@link ResourceServerSecurity} for every servlet service that depends on this module. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ResourceServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoles());
        // The principal name stays the default "sub" claim: the stable Keycloak user id.
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean
    ResourceServerSecurity resourceServerSecurity(Environment environment,
                                                  JwtAuthenticationConverter authenticationConverter,
                                                  ObjectProvider<JsonMapper> json) {
        ResourceServerSecurity.requireValidationSettings(environment);
        var problems = new ProblemResponses(json.getIfAvailable(() -> JsonMapper.builder().build()));
        return new ResourceServerSecurity(authenticationConverter,
                problems.authenticationEntryPoint(), problems.accessDeniedHandler());
    }
}
