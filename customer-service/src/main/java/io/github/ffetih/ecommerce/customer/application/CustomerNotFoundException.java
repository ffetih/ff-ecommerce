package io.github.ffetih.ecommerce.customer.application;

import java.util.UUID;

public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(UUID id) {
        super("Customer not found: " + id);
    }
}
