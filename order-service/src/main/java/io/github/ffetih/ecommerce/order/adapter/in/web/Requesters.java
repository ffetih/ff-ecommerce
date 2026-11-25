package io.github.ffetih.ecommerce.order.adapter.in.web;

import io.github.ffetih.ecommerce.order.application.Requester;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

/** Translates a validated access token into the application's {@link Requester}. */
final class Requesters {

    private static final String BACK_OFFICE = "ROLE_admin";

    private Requesters() {
    }

    static Requester of(JwtAuthenticationToken caller) {
        boolean backOffice = caller.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(BACK_OFFICE::equals);
        return backOffice ? Requester.backOffice() : Requester.customer(customerId(caller));
    }

    /** The Keycloak user id ({@code sub}) is the customer id. */
    static UUID customerId(JwtAuthenticationToken caller) {
        return UUID.fromString(caller.getName());
    }
}
