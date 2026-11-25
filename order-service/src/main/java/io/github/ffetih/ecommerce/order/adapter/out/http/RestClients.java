package io.github.ffetih.ecommerce.order.adapter.out.http;

import io.github.ffetih.ecommerce.order.config.ClientProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

final class RestClients {

    private RestClients() {
    }

    /** A client with explicit timeouts: without them, a hanging dependency hangs every order request. */
    static RestClient create(ClientProperties.Endpoint endpoint) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(endpoint.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(endpoint.readTimeout());
        return RestClient.builder()
                .baseUrl(endpoint.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
