package io.github.ffetih.ecommerce.customer.application.port;

import io.github.ffetih.ecommerce.customer.domain.Customer;
import io.github.ffetih.ecommerce.customer.domain.Email;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository {

    Optional<Customer> findById(UUID id);

    boolean existsById(UUID id);

    boolean existsByEmail(Email email);

    /**
     * Stores a new customer. Never overwrites: a customer with the same id or email already stored
     * makes it fail with {@code CustomerAlreadyRegisteredException} or {@code EmailAlreadyRegisteredException}.
     */
    void insert(Customer customer);

    /** Stores changes to an existing customer. */
    void update(Customer customer);
}
