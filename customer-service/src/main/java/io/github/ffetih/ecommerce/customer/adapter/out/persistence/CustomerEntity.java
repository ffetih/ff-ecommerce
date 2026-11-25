package io.github.ffetih.ecommerce.customer.adapter.out.persistence;

import io.github.ffetih.ecommerce.customer.domain.Customer;
import io.github.ffetih.ecommerce.customer.domain.Email;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Customer row. Unlike stock, customers are rarely edited concurrently, so optimistic locking fits:
 * two simultaneous edits of the same customer make the second one fail instead of silently winning.
 */
@Entity
@Table(name = "customers")
class CustomerEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(nullable = false, length = 200)
    private String fullName;

    @Column(nullable = false, length = 500)
    private String shippingAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Customer.Status status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CustomerEntity() {
    }

    CustomerEntity(Customer customer) {
        this.id = customer.id();
        this.email = customer.email().value();
        this.createdAt = customer.createdAt();
        update(customer);
    }

    void update(Customer customer) {
        this.fullName = customer.fullName();
        this.shippingAddress = customer.shippingAddress();
        this.status = customer.status();
        this.updatedAt = customer.updatedAt();
    }

    Customer toDomain() {
        return new Customer(id, Email.of(email), fullName, shippingAddress, status, createdAt, updatedAt);
    }
}
