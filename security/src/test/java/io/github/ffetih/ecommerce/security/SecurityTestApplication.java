package io.github.ffetih.ecommerce.security;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** A minimal service with one endpoint per kind of rule, secured the way every real service is. */
@SpringBootApplication
class SecurityTestApplication {

    @Bean
    SecurityFilterChain security(HttpSecurity http, ResourceServerSecurity resourceServer) throws Exception {
        return resourceServer.build(http, rules -> rules
                .requestMatchers(HttpMethod.GET, "/api/test/open").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/test/whoami").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/test/admin").hasRole("admin"));
    }

    @RestController
    static class Endpoints {

        @GetMapping("/api/test/open")
        Map<String, Object> open() {
            return Map.of("open", true);
        }

        @GetMapping("/api/test/whoami")
        Map<String, Object> whoami(JwtAuthenticationToken authentication) {
            return Map.of(
                    "subject", authentication.getName(),
                    "authorities", authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList());
        }

        @GetMapping("/api/test/admin")
        Map<String, Object> admin() {
            return Map.of("admin", true);
        }

        /** Exists, but no rule mentions it: deny by default must close it. */
        @GetMapping("/api/test/unlisted")
        Map<String, Object> unlisted() {
            return Map.of("unlisted", true);
        }
    }
}
