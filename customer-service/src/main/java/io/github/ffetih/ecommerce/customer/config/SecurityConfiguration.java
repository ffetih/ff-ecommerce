package io.github.ffetih.ecommerce.customer.config;

import io.github.ffetih.ecommerce.security.ResourceServerSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Every endpoint of the customer API, with who may call it. Anything not listed is denied. */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain security(HttpSecurity http, ResourceServerSecurity resourceServer) throws Exception {
        return resourceServer.build(http, allow -> allow
                // The caller's own profile; "/me" rules come first because "/*" would match them too.
                .requestMatchers(HttpMethod.POST, "/api/v1/customers/me").hasRole("customer")
                .requestMatchers(HttpMethod.GET, "/api/v1/customers/me").hasRole("customer")
                .requestMatchers(HttpMethod.PUT, "/api/v1/customers/me/shipping-address").hasRole("customer")
                // Anyone's profile: the back office, and services such as order-service.
                .requestMatchers(HttpMethod.GET, "/api/v1/customers/*").hasAnyRole("admin", "service")
                .requestMatchers(HttpMethod.POST, "/api/v1/customers/*/deactivate").hasRole("admin"));
    }
}
