package io.github.ffetih.ecommerce.shipping.config;

import io.github.ffetih.ecommerce.security.ResourceServerSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Every endpoint of the shipping API, with who may call it. Anything not listed is denied. */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain security(HttpSecurity http, ResourceServerSecurity resourceServer) throws Exception {
        // Shipments are created from order events; people only look them up and move them along.
        return resourceServer.build(http, allow -> allow
                .requestMatchers(HttpMethod.GET, "/api/v1/shipments", "/api/v1/shipments/*").hasRole("admin")
                .requestMatchers(HttpMethod.POST, "/api/v1/shipments/*/dispatch", "/api/v1/shipments/*/deliver")
                .hasRole("admin"));
    }
}
