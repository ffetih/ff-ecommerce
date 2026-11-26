package io.github.ffetih.ecommerce.events.payment;

import java.util.UUID;

/**
 * Command from order-service: the parcel has left, take the money (ADR-0009). payment-service answers
 * {@code payment.payment-captured} or {@code payment.payment-capture-failed}.
 * Topic {@value AuthorizePayment#TOPIC}, key: order id.
 */
public record CapturePayment(UUID orderId) {

    public static final String TYPE = "payment.capture-payment";
    public static final int VERSION = 1;
}
