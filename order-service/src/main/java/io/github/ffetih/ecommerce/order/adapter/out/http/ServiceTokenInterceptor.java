package io.github.ffetih.ecommerce.order.adapter.out.http;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

import java.io.IOException;

/**
 * Adds this service's own access token to every outgoing call. The token comes from the client
 * credentials grant: order-service authenticates as itself, never forwards the user's token.
 *
 * <p>The authorized client manager keeps the token and asks the issuer again only shortly before it
 * expires, so the issuer is not on the path of every order. If no token can be obtained, the call
 * fails like an unreachable dependency (an {@link IOException}), and callers answer 503.
 */
final class ServiceTokenInterceptor implements ClientHttpRequestInterceptor {

    private final OAuth2AuthorizedClientManager clients;
    private final String registrationId;

    ServiceTokenInterceptor(OAuth2AuthorizedClientManager clients, String registrationId) {
        this.clients = clients;
        this.registrationId = registrationId;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        OAuth2AuthorizedClient client;
        try {
            client = clients.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                    .principal(registrationId)
                    .build());
        } catch (RuntimeException e) {
            throw new IOException("Could not obtain a service token for " + registrationId, e);
        }
        if (client == null) {
            throw new IOException("No service token for " + registrationId);
        }
        request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
        return execution.execute(request, body);
    }
}
