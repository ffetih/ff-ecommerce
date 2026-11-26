package io.github.ffetih.ecommerce.events.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Command from order-service: hold the order's amount on the customer's card (ADR-0009). Sent once the
 * stock is reserved. payment-service answers {@code payment.payment-authorized} or
 * {@code payment.payment-declined}; a provider that does not answer leaves the outcome open, never
 * declined. Topic {@value #TOPIC}, key: order id.
 *
 * @param cardToken the customer's payment method, as the provider knows it
 */
public record AuthorizePayment(UUID orderId, UUID customerId, BigDecimal amount, String currency, String cardToken) {

    public static final String TOPIC = "payment.commands";
    public static final String TYPE = "payment.authorize-payment";
    public static final int VERSION = 1;
}
