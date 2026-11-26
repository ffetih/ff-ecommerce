package io.github.ffetih.ecommerce.payment.application;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID orderId) {
        super("No payment for order " + orderId);
    }
}
