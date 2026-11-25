package io.github.ffetih.ecommerce.security;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.util.List;

/**
 * Builds a service's security filter chain as a stateless OAuth2 resource server.
 *
 * <p>Every service lists only what it <em>allows</em>; this class adds the rest:
 * <ul>
 *   <li>tokens are validated by Spring Security against the configured issuer (signature from the
 *       issuer's published keys, {@code iss}, {@code exp}/{@code nbf}) and audience ({@code aud});</li>
 *   <li>{@code realm_access.roles} become authorities ({@code hasRole("admin")});</li>
 *   <li>anything without a rule is denied ({@code anyRequest().denyAll()} is always last, so a
 *       forgotten endpoint is closed, not open);</li>
 *   <li>401 and 403 are problem responses with an RFC 6750 {@code WWW-Authenticate} header.</li>
 * </ul>
 *
 * <pre>{@code
 * @Bean
 * SecurityFilterChain security(HttpSecurity http, ResourceServerSecurity resourceServer) throws Exception {
 *     return resourceServer.build(http, rules -> rules
 *             .requestMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()
 *             .requestMatchers("/api/v1/stock/**").hasRole("admin"));
 * }
 * }</pre>
 */
public final class ResourceServerSecurity {

    public static final String ISSUER_URI_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    public static final String AUDIENCES_PROPERTY = "spring.security.oauth2.resourceserver.jwt.audiences";

    /** Open on every service: liveness and readiness probes, and the container's error page. */
    private static final String[] ALWAYS_OPEN = {"/actuator/health/**", "/error"};

    private final JwtAuthenticationConverter authenticationConverter;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final AccessDeniedHandler accessDeniedHandler;

    ResourceServerSecurity(JwtAuthenticationConverter authenticationConverter,
                           AuthenticationEntryPoint authenticationEntryPoint,
                           AccessDeniedHandler accessDeniedHandler) {
        this.authenticationConverter = authenticationConverter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    public SecurityFilterChain build(
            HttpSecurity http,
            Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> allowed)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter)))
                .authorizeHttpRequests(rules -> {
                    rules.requestMatchers(ALWAYS_OPEN).permitAll();
                    allowed.customize(rules);
                    rules.anyRequest().denyAll();
                });
        return http.build();
    }

    /**
     * Fails startup when token validation would be incomplete. Without an issuer there is no way to
     * verify signatures; without an audience a service would accept any token of the realm, including
     * one minted for a different service.
     */
    static void requireValidationSettings(Environment environment) {
        String issuer = environment.getProperty(ISSUER_URI_PROPERTY);
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException(
                    "Set " + ISSUER_URI_PROPERTY + " to the token issuer (for example the Keycloak realm URL)");
        }
        List<String> audiences = Binder.get(environment)
                .bind(AUDIENCES_PROPERTY, Bindable.listOf(String.class))
                .orElse(List.of())
                .stream()
                .filter(audience -> !audience.isBlank())
                .toList();
        if (audiences.isEmpty()) {
            throw new IllegalStateException(
                    "Set " + AUDIENCES_PROPERTY + " to this service's name, so tokens issued for other services are rejected");
        }
    }
}
