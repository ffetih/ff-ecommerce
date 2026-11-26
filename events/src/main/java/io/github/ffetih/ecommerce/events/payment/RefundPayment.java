package io.github.ffetih.ecommerce.events.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Command from order-service: a returned parcel has arrived, give the money for it back (ADR-0010).
 * One payment can have several refunds, one per return; together they never exceed the capture.
 * payment-service answers {@code payment.payment-refunded} or {@code payment.payment-refund-failed}.
 * Topic {@value AuthorizePayment#TOPIC}, key: order id.
 */
public record RefundPayment(UUID orderId, UUID returnId, BigDecimal amount, String currency) {

    public static final String TYPE = "payment.refund-payment";
    public static final int VERSION = 1;
}
