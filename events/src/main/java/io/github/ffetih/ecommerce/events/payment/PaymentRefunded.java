package io.github.ffetih.ecommerce.events.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The money for one return is back with the customer, and the ledger has booked the reversal.
 * Topic {@value PaymentAuthorized#TOPIC}, key: order id.
 */
public record PaymentRefunded(UUID paymentId, UUID orderId, UUID returnId, BigDecimal amount, String currency) {

    public static final String TYPE = "payment.payment-refunded";
    public static final int VERSION = 1;
}
