package io.github.ffetih.ecommerce.events.payment;

import java.util.UUID;

/**
 * The provider refused the capture. The parcel has already left, so nothing is cancelled: the payment
 * is overdue and needs a person (ADR-0009). Topic {@value PaymentAuthorized#TOPIC}, key: order id.
 */
public record PaymentCaptureFailed(UUID paymentId, UUID orderId, String reason) {

    public static final String TYPE = "payment.payment-capture-failed";
    public static final int VERSION = 1;
}
