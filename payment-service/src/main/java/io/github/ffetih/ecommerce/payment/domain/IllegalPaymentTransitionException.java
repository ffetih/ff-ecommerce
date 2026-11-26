package io.github.ffetih.ecommerce.payment.domain;

import java.util.UUID;

public class IllegalPaymentTransitionException extends RuntimeException {

    public IllegalPaymentTransitionException(UUID paymentId, Payment.Status from, String action) {
        super("Cannot " + action + " payment " + paymentId + " in status " + from);
    }
}
