package io.github.ffetih.ecommerce.customer.application;

import java.util.UUID;

public class CustomerAlreadyRegisteredException extends RuntimeException {

    public CustomerAlreadyRegisteredException(UUID id) {
        super("Customer " + id + " is already registered");
    }
}
