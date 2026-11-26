package io.github.ffetih.ecommerce.order.domain;

import java.util.UUID;

public class IllegalReturnTransitionException extends RuntimeException {

    public IllegalReturnTransitionException(UUID returnId, Return.Status from, String action) {
        super("Cannot " + action + " return " + returnId + " in status " + from);
    }
}
