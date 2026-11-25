package io.github.ffetih.ecommerce.customer.application.port;

import io.github.ffetih.ecommerce.customer.domain.Customer;
import io.github.ffetih.ecommerce.customer.domain.Email;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository {

    Optional<Customer> findById(UUID id);

    boolean existsByEmail(Email email);

    void save(Customer customer);
}
