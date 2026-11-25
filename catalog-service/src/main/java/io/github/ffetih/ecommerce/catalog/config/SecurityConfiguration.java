package io.github.ffetih.ecommerce.catalog.config;

import io.github.ffetih.ecommerce.security.ResourceServerSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Every endpoint of the catalog API, with who may call it. Anything not listed is denied. */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain security(HttpSecurity http, ResourceServerSecurity resourceServer) throws Exception {
        return resourceServer.build(http, allow -> allow
                // Browsing the catalog is public; order-service reads prices here as well.
                .requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/products").hasRole("admin")
                .requestMatchers(HttpMethod.PUT, "/api/v1/products/*/price").hasRole("admin")
                .requestMatchers(HttpMethod.POST, "/api/v1/products/*/deactivate").hasRole("admin")
                .requestMatchers(HttpMethod.GET, "/api/v1/stock/*").hasRole("admin")
                .requestMatchers(HttpMethod.POST, "/api/v1/stock/*/receipts").hasRole("admin")
                // Orders reserve stock through events (ADR-0007); the HTTP endpoints are back-office tools.
                .requestMatchers(HttpMethod.GET, "/api/v1/reservations/*").hasRole("admin")
                .requestMatchers(HttpMethod.PUT, "/api/v1/reservations/*").hasRole("admin")
                .requestMatchers(HttpMethod.POST, "/api/v1/reservations/*/commit", "/api/v1/reservations/*/release")
                .hasRole("admin"));
    }
}
