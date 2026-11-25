package io.github.ffetih.ecommerce.order.domain;

import java.util.UUID;

/** The requested step is not possible from the order's current status (e.g. cancelling a shipped order). */
public class IllegalOrderTransitionException extends RuntimeException {

    public IllegalOrderTransitionException(UUID orderId, Order.Status from, String action) {
        super("Cannot " + action + " order " + orderId + " in status " + from);
    }
}
