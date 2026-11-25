package io.github.ffetih.ecommerce.order.adapter.out.http;

import io.github.ffetih.ecommerce.order.config.ClientProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * The client registration named after this application ({@code spring.application.name}), with the
 * client credentials grant only. Works outside of any HTTP request (no user, no session), which is
 * what service-to-service calls need.
 *
 * <p>The token request has its own timeouts: the downstream timeouts only start once a token is
 * there, so without these a hanging issuer would hang every order request.
 */
@Component
class ServiceTokens {

    private final ClientHttpRequestInterceptor interceptor;

    ServiceTokens(ClientRegistrationRepository registrations, ClientProperties properties,
                  @Value("${spring.application.name}") String registrationId) {
        var store = new InMemoryOAuth2AuthorizedClientService(registrations);
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, store);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(grant -> grant.accessTokenResponseClient(tokenClient(properties)))
                .build());
        this.interceptor = new ServiceTokenInterceptor(manager, store, registrationId);
    }

    ClientHttpRequestInterceptor interceptor() {
        return interceptor;
    }

    /** Spring Security's default token client configuration, plus timeouts. */
    private static RestClientClientCredentialsTokenResponseClient tokenClient(ClientProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.tokenConnectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(properties.tokenReadTimeout());
        RestClient rest = RestClient.builder()
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        var client = new RestClientClientCredentialsTokenResponseClient();
        client.setRestClient(rest);
        return client;
    }
}
