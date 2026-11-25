package io.github.ffetih.ecommerce.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmRolesTest {

    private final KeycloakRealmRoles roles = new KeycloakRealmRoles();

    @Test
    void realmRolesBecomeRoleAuthorities() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("customer", "admin"))));

        assertThat(names(roles.convert(jwt))).containsExactly("ROLE_customer", "ROLE_admin");
    }

    @Test
    void aTokenWithoutRealmRolesHasNoAuthorities() {
        assertThat(roles.convert(jwt(Map.of("scope", "email")))).isEmpty();
    }

    @Test
    void unexpectedShapesAreIgnoredRatherThanTrusted() {
        assertThat(roles.convert(jwt(Map.of("realm_access", "admin")))).isEmpty();
        assertThat(roles.convert(jwt(Map.of("realm_access", Map.of("roles", "admin"))))).isEmpty();
        assertThat(names(roles.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("customer", 42, " ")))))))
                .containsExactly("ROLE_customer");
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("subject");
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static List<String> names(Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }
}
