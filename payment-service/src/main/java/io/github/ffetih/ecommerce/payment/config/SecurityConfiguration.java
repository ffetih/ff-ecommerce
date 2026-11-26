package io.github.ffetih.ecommerce.payment.config;

import io.github.ffetih.ecommerce.security.ResourceServerSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every endpoint of the payment API, with who may call it. Anything not listed is denied.
 * Payments are driven by order-service's commands (ADR-0009); the HTTP API comes with the domain.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain security(HttpSecurity http, ResourceServerSecurity resourceServer) throws Exception {
        return resourceServer.build(http, allow -> {
        });
    }
}
