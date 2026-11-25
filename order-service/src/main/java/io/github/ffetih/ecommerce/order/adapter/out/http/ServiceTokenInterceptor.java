package io.github.ffetih.ecommerce.order.adapter.out.http;

import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;

import java.io.IOException;

/**
 * Adds this service's own access token to every outgoing call. The token comes from the client
 * credentials grant: order-service authenticates as itself, never forwards the user's token.
 *
 * <ul>
 *   <li>The token is kept and reused until shortly before it expires, so the issuer is not on the
 *       path of every order.</li>
 *   <li>If the called service answers 401, the kept token is no longer accepted there (for example
 *       after a key rotation): it is dropped, a fresh one is fetched, and the call is repeated once.
 *       Without this, every call would fail until the old token expired.</li>
 *   <li>If no token can be obtained (issuer down or too slow), the call fails like an unreachable
 *       dependency, with an {@link IOException}, and callers answer 503.</li>
 * </ul>
 * Only safe for idempotent calls: the request is sent a second time. Today all calls are GETs.
 */
final class ServiceTokenInterceptor implements ClientHttpRequestInterceptor {

    private final OAuth2AuthorizedClientManager clients;
    private final OAuth2AuthorizedClientService store;
    private final String registrationId;

    ServiceTokenInterceptor(OAuth2AuthorizedClientManager clients, OAuth2AuthorizedClientService store,
                            String registrationId) {
        this.clients = clients;
        this.store = store;
        this.registrationId = registrationId;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse response = send(request, body, execution);
        if (response.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) {
            return response;
        }
        response.close();
        store.removeAuthorizedClient(registrationId, registrationId);
        return send(request, body, execution);
    }

    private ClientHttpResponse send(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().setBearerAuth(token());
        return execution.execute(request, body);
    }

    private String token() throws IOException {
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
        return client.getAccessToken().getTokenValue();
    }
}
