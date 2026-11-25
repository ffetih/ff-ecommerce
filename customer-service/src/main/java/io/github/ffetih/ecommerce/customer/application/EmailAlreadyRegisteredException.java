package io.github.ffetih.ecommerce.customer.application;

import io.github.ffetih.ecommerce.customer.domain.Email;

public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(Email email) {
        super("An account with email " + email + " already exists");
    }
}
