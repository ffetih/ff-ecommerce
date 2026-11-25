package io.github.ffetih.ecommerce.customer.adapter.out.persistence;

import io.github.ffetih.ecommerce.customer.application.CustomerAlreadyRegisteredException;
import io.github.ffetih.ecommerce.customer.application.CustomerNotFoundException;
import io.github.ffetih.ecommerce.customer.application.EmailAlreadyRegisteredException;
import io.github.ffetih.ecommerce.customer.application.port.CustomerRepository;
import io.github.ffetih.ecommerce.customer.domain.Customer;
import io.github.ffetih.ecommerce.customer.domain.Email;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class CustomerPersistenceAdapter implements CustomerRepository {

    private static final String PRIMARY_KEY = "customers_pkey";
    private static final String UNIQUE_EMAIL = "customers_email_unique";

    private final CustomerJpaRepository repository;

    CustomerPersistenceAdapter(CustomerJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Customer> findById(UUID id) {
        return repository.findById(id).map(CustomerEntity::toDomain);
    }

    @Override
    public boolean existsById(UUID id) {
        return repository.existsById(id);
    }

    @Override
    public boolean existsByEmail(Email email) {
        return repository.existsByEmail(email.value());
    }

    /** Flushes at once, so a duplicate surfaces here as an application exception, not at commit. */
    @Override
    public void insert(Customer customer) {
        try {
            repository.saveAndFlush(new CustomerEntity(customer));
        } catch (DataIntegrityViolationException e) {
            String reason = String.valueOf(e.getMostSpecificCause().getMessage());
            if (reason.contains(PRIMARY_KEY)) {
                throw new CustomerAlreadyRegisteredException(customer.id());
            }
            if (reason.contains(UNIQUE_EMAIL)) {
                throw new EmailAlreadyRegisteredException(customer.email());
            }
            throw e;
        }
    }

    @Override
    public void update(Customer customer) {
        CustomerEntity entity = repository.findById(customer.id())
                .orElseThrow(() -> new CustomerNotFoundException(customer.id()));
        entity.update(customer);
    }
}
