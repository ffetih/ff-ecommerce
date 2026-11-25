package io.github.ffetih.ecommerce.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Order expiry ({@code ecommerce.orders.expiry.*}).
 *
 * @param enabled        whether this instance scans; tests switch it off and call the expiry directly
 * @param placedAfter    a placed order is normally confirmed within seconds
 * @param confirmedAfter how long a confirmed order may wait for dispatch before it is stopped
 * @param scanInterval   pause between two scans
 * @param batchSize      orders handled per scan at most, oldest first
 */
@ConfigurationProperties("ecommerce.orders.expiry")
public record ExpiryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30m") Duration placedAfter,
        @DefaultValue("3d") Duration confirmedAfter,
        @DefaultValue("1m") Duration scanInterval,
        @DefaultValue("100") int batchSize) {
}
