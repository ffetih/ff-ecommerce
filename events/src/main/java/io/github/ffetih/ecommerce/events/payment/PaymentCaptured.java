package io.github.ffetih.ecommerce.events.payment;

import java.math.BigDecimal;
import java.util.UUID;

/** The money has moved, and the ledger has booked it. Topic {@value PaymentAuthorized#TOPIC}, key: order id. */
public record PaymentCaptured(UUID paymentId, UUID orderId, BigDecimal amount, String currency) {

    public static final String TYPE = "payment.payment-captured";
    public static final int VERSION = 1;
}
