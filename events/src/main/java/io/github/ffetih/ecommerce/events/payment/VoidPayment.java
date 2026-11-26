package io.github.ffetih.ecommerce.events.payment;

import java.util.UUID;

/**
 * Command from order-service: the order was cancelled before its parcel left, release the hold
 * (ADR-0009). If the authorization's outcome is still open, payment-service remembers the request and
 * voids as soon as it is known. Answer: {@code payment.payment-voided}.
 * Topic {@value AuthorizePayment#TOPIC}, key: order id.
 */
public record VoidPayment(UUID orderId) {

    public static final String TYPE = "payment.void-payment";
    public static final int VERSION = 1;
}
