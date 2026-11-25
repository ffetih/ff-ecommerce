package io.github.ffetih.ecommerce.customer.adapter.out.persistence;

import io.github.ffetih.ecommerce.customer.application.CustomerAlreadyRegisteredException;
import io.github.ffetih.ecommerce.customer.application.EmailAlreadyRegisteredException;
import io.github.ffetih.ecommerce.customer.application.port.CustomerRepository;
import io.github.ffetih.ecommerce.customer.application.port.Transactions;
import io.github.ffetih.ecommerce.customer.domain.Customer;
import io.github.ffetih.ecommerce.customer.domain.Email;
import io.github.ffetih.ecommerce.customer.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inserting never overwrites. This is what makes concurrent registrations safe: when two requests get
 * past the existence checks together, the second insert must hit the primary key, not turn into an
 * update of the first customer. Deterministic, unlike the race itself (see CustomerApiIT).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CustomerPersistenceIT {

    @Autowired
    CustomerRepository customers;

    @Autowired
    Transactions transactions;

    @Test
    void aSecondInsertWithTheSameIdFailsAndLeavesTheFirstCustomerAsItWas() {
        UUID id = UUID.randomUUID();
        insert(customer(id, "first-" + id + "@example.com", "First Name"));

        assertThatThrownBy(() -> insert(customer(id, "second-" + id + "@example.com", "Second Name")))
                .isInstanceOf(CustomerAlreadyRegisteredException.class);
        assertThat(transactions.inTransaction(() -> customers.findById(id)).orElseThrow().fullName())
                .isEqualTo("First Name");
    }

    @Test
    void aSecondInsertWithTheSameEmailFails() {
        String email = "shared-" + UUID.randomUUID() + "@example.com";
        insert(customer(UUID.randomUUID(), email, "First"));

        assertThatThrownBy(() -> insert(customer(UUID.randomUUID(), email, "Second")))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    private void insert(Customer customer) {
        transactions.inTransaction(() -> {
            customers.insert(customer);
            return null;
        });
    }

    private static Customer customer(UUID id, String email, String name) {
        return Customer.register(id, Email.of(email), name, "Somewhere 1", Instant.now());
    }
}
