package io.github.ffetih.ecommerce.customer.domain;

import java.util.UUID;

public class CustomerDeactivatedException extends RuntimeException {

    public CustomerDeactivatedException(UUID customerId) {
        super("Customer " + customerId + " is deactivated");
    }
}
