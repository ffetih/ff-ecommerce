package io.github.ffetih.ecommerce.events.payment;

import java.util.UUID;

/** The hold was released; no money moved. Topic {@value PaymentAuthorized#TOPIC}, key: order id. */
public record PaymentVoided(UUID paymentId, UUID orderId) {

    public static final String TYPE = "payment.payment-voided";
    public static final int VERSION = 1;
}
