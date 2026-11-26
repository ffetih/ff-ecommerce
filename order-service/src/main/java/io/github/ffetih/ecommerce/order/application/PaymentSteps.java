package io.github.ffetih.ecommerce.order.application;

import io.github.ffetih.ecommerce.order.application.port.PaymentCommands;
import io.github.ffetih.ecommerce.order.domain.Order;

/**
 * Sends the payment command an order change calls for (ADR-0009). The order's own state machine
 * decides when money must be held, taken or released; this only turns its decision into a command,
 * the same way for every path that changes an order: saga events, the customer, expiry.
 */
public final class PaymentSteps {

    private final PaymentCommands payments;

    public PaymentSteps(PaymentCommands payments) {
        this.payments = payments;
    }

    /** Call in the transaction that stores {@code after}, so the command is sent if and only if it is stored. */
    void after(Order before, Order after) {
        if (after.payment() == before.payment()) {
            return;
        }
        switch (after.payment()) {
            case AUTHORIZING -> payments.authorize(after);
            case CAPTURING -> payments.capture(after);
            case VOIDING -> payments.voidPayment(after);
            default -> {
                // an answer from payment-service: nothing to send
            }
        }
    }
}
