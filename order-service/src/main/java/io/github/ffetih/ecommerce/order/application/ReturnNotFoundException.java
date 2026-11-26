package io.github.ffetih.ecommerce.order.application;

import java.util.UUID;

public class ReturnNotFoundException extends RuntimeException {

    public ReturnNotFoundException(UUID returnId) {
        super("Return " + returnId + " not found");
    }
}
