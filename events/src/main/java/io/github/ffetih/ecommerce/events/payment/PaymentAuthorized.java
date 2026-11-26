package io.github.ffetih.ecommerce.events.payment;

import java.math.BigDecimal;
import java.util.UUID;

/** The amount is held on the customer's card. Topic {@value #TOPIC}, key: order id. */
public record PaymentAuthorized(UUID paymentId, UUID orderId, BigDecimal amount, String currency) {

    public static final String TOPIC = "payment.events";
    public static final String TYPE = "payment.payment-authorized";
    public static final int VERSION = 1;
}
