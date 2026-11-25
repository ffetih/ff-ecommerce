package io.github.ffetih.ecommerce.customer.application;

import io.github.ffetih.ecommerce.customer.application.port.CustomerRepository;
import io.github.ffetih.ecommerce.customer.application.port.Transactions;
import io.github.ffetih.ecommerce.customer.domain.Customer;
import io.github.ffetih.ecommerce.customer.domain.Email;

import java.time.Clock;
import java.util.UUID;

public class CustomerService {

    private final CustomerRepository customers;
    private final Transactions transactions;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, Transactions transactions, Clock clock) {
        this.customers = customers;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * The existence check gives a clear error in the common case; the database unique constraint
     * still decides when two registrations with the same email race each other.
     */
    public Customer register(Email email, String fullName, String shippingAddress) {
        Customer customer = Customer.register(email, fullName, shippingAddress, clock.instant());
        return transactions.inTransaction(() -> {
            if (customers.existsByEmail(email)) {
                throw new EmailAlreadyRegisteredException(email);
            }
            customers.save(customer);
            return customer;
        });
    }

    public Customer get(UUID id) {
        return customers.findById(id).orElseThrow(() -> new CustomerNotFoundException(id));
    }

    public Customer changeShippingAddress(UUID id, String newAddress) {
        return transactions.inTransaction(() -> {
            Customer changed = get(id).changeShippingAddress(newAddress, clock.instant());
            customers.save(changed);
            return changed;
        });
    }

    public Customer deactivate(UUID id) {
        return transactions.inTransaction(() -> {
            Customer deactivated = get(id).deactivate(clock.instant());
            customers.save(deactivated);
            return deactivated;
        });
    }
}
