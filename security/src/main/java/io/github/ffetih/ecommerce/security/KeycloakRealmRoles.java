package io.github.ffetih.ecommerce.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Maps Keycloak realm roles ({@code realm_access.roles}) to Spring authorities: {@code customer}
 * becomes {@code ROLE_customer}, so rules read {@code hasRole("customer")}.
 *
 * <p>Anything unexpected in the claim (missing, wrong type) yields no authorities rather than an
 * error: a token without roles can still be authenticated, it just cannot do much.
 */
public final class KeycloakRealmRoles implements Converter<Jwt, Collection<GrantedAuthority>> {

    public static final String CLAIM = "realm_access";
    public static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        if (!(jwt.getClaims().get(CLAIM) instanceof Map<?, ?> realmAccess)
                || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(role -> !role.isBlank())
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .toList();
    }
}
