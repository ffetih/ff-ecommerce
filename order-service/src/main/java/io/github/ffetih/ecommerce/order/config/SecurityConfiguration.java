package io.github.ffetih.ecommerce.order.config;

import io.github.ffetih.ecommerce.security.ResourceServerSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every endpoint of the order API, with who may call it. Anything not listed is denied. These rules
 * decide who may call an endpoint at all; whose order it is gets decided in the application
 * ({@code Requester}), because only there is the order known.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain security(HttpSecurity http, ResourceServerSecurity resourceServer) throws Exception {
        return resourceServer.build(http, allow -> allow
                .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasRole("customer")
                .requestMatchers(HttpMethod.GET, "/api/v1/orders", "/api/v1/orders/*").hasAnyRole("customer", "admin")
                .requestMatchers(HttpMethod.POST, "/api/v1/orders/*/cancel").hasAnyRole("customer", "admin"));
    }
}
