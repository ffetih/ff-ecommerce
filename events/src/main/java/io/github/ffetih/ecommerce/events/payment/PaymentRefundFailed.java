package io.github.ffetih.ecommerce.events.payment;

import java.util.UUID;

/**
 * The refund for one return could not be made: the provider refused it, or it would exceed what was
 * captured. Nothing was booked; operations decide. Topic {@value PaymentAuthorized#TOPIC}, key: order id.
 */
public record PaymentRefundFailed(UUID paymentId, UUID orderId, UUID returnId, String reason) {

    public static final String TYPE = "payment.payment-refund-failed";
    public static final int VERSION = 1;
}
