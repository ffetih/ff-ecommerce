package io.github.ffetih.ecommerce.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Where the services this one calls live, and how long we wait for them ({@code ecommerce.clients.*}).
 *
 * @param tokenConnectTimeout how long to wait for a connection to the token issuer
 * @param tokenReadTimeout    how long to wait for a service token; the token is reused, so this is
 *                            paid rarely, but a hanging issuer must not hang order requests
 */
@ConfigurationProperties("ecommerce.clients")
public record ClientProperties(
        Endpoint customer,
        Endpoint catalog,
        @DefaultValue("2s") Duration tokenConnectTimeout,
        @DefaultValue("3s") Duration tokenReadTimeout) {

    /**
     * @param connectTimeout how long to wait for a TCP connection
     * @param readTimeout    how long to wait for the response; an order request waits on both calls,
     *                       so the sum stays well under what a client is willing to wait
     */
    public record Endpoint(
            URI baseUrl,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("3s") Duration readTimeout) {
    }
}
