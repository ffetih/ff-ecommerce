package io.github.ffetih.ecommerce.events.payment;

import java.util.UUID;

/** The provider explicitly refused the authorization. Topic {@value PaymentAuthorized#TOPIC}, key: order id. */
public record PaymentDeclined(UUID paymentId, UUID orderId, String reason) {

    public static final String TYPE = "payment.payment-declined";
    public static final int VERSION = 1;
}
