package io.github.ffetih.ecommerce.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A service that uses this module but builds no chain with it (so Spring Boot's default chain would
 * let any valid token reach any endpoint), or builds one by hand that skips deny-by-default, must
 * not start. No component scanning here: each application consists of exactly the classes shown.
 */
class FilterChainGuardIT {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class WithoutAChain {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class WithAHandWrittenChain {

        @Bean
        SecurityFilterChain handWritten(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(rules -> rules.anyRequest().authenticated())
                    .oauth2ResourceServer(server -> server.jwt(jwt -> { }))
                    .build();
        }
    }

    @Test
    void refusesToStartWithoutAChainBuiltByTheModule() {
        assertThatThrownBy(() -> start(WithoutAChain.class).close())
                .hasStackTraceContaining("built with ResourceServerSecurity.build");
    }

    @Test
    void refusesToStartWithAHandWrittenChain() {
        assertThatThrownBy(() -> start(WithAHandWrittenChain.class).close())
                .hasStackTraceContaining("built with ResourceServerSecurity.build");
    }

    private static ConfigurableApplicationContext start(Class<?> application) {
        return new SpringApplicationBuilder(application)
                .properties(
                        "server.port=0",
                        ResourceServerSecurity.ISSUER_URI_PROPERTY + "=http://localhost:1/realms/none",
                        ResourceServerSecurity.AUDIENCES_PROPERTY + "=test-service")
                .run();
    }
}
