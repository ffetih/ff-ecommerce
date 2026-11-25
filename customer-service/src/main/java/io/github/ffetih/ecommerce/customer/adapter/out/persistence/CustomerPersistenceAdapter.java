package io.github.ffetih.ecommerce.customer.adapter.out.persistence;

import io.github.ffetih.ecommerce.customer.application.port.CustomerRepository;
import io.github.ffetih.ecommerce.customer.domain.Customer;
import io.github.ffetih.ecommerce.customer.domain.Email;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class CustomerPersistenceAdapter implements CustomerRepository {

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

    @Override
    public void save(Customer customer) {
        CustomerEntity entity = repository.findById(customer.id())
                .map(existing -> {
                    existing.update(customer);
                    return existing;
                })
                .orElseGet(() -> new CustomerEntity(customer));
        repository.save(entity);
    }
}
