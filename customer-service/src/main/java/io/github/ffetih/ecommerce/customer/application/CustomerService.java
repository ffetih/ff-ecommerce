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
     * Creates the profile of the person behind an access token. The checks give a clear error in the
     * common case; when two registrations race past them, the insert (never an upsert) meets the
     * primary key or the unique email, and the loser gets the same 409.
     */
    public Customer register(UUID id, Email email, String fullName, String shippingAddress) {
        Customer customer = Customer.register(id, email, fullName, shippingAddress, clock.instant());
        return transactions.inTransaction(() -> {
            if (customers.existsById(id)) {
                throw new CustomerAlreadyRegisteredException(id);
            }
            if (customers.existsByEmail(email)) {
                throw new EmailAlreadyRegisteredException(email);
            }
            customers.insert(customer);
            return customer;
        });
    }

    public Customer get(UUID id) {
        return customers.findById(id).orElseThrow(() -> new CustomerNotFoundException(id));
    }

    public Customer changeShippingAddress(UUID id, String newAddress) {
        return transactions.inTransaction(() -> {
            Customer changed = get(id).changeShippingAddress(newAddress, clock.instant());
            customers.update(changed);
            return changed;
        });
    }

    public Customer deactivate(UUID id) {
        return transactions.inTransaction(() -> {
            Customer deactivated = get(id).deactivate(clock.instant());
            customers.update(deactivated);
            return deactivated;
        });
    }
}
