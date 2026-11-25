package io.github.ffetih.ecommerce.order.adapter.out.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * The client registration named after this application ({@code spring.application.name}), with the
 * client credentials grant only. Works outside of any HTTP request (no user, no session), which is
 * what service-to-service calls need.
 */
@Component
class ServiceTokens {

    private final ClientHttpRequestInterceptor interceptor;

    ServiceTokens(ClientRegistrationRepository registrations, @Value("${spring.application.name}") String registrationId) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                registrations, new InMemoryOAuth2AuthorizedClientService(registrations));
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        this.interceptor = new ServiceTokenInterceptor(manager, registrationId);
    }

    ClientHttpRequestInterceptor interceptor() {
        return interceptor;
    }
}
