package io.github.ffetih.ecommerce.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Returns ({@code ecommerce.orders.returns.*}).
 *
 * @param window how long after delivery goods can be returned
 */
@ConfigurationProperties("ecommerce.orders.returns")
public record ReturnProperties(@DefaultValue("14d") Duration window) {
}
