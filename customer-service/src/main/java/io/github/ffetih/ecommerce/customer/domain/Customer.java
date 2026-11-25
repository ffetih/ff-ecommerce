package io.github.ffetih.ecommerce.customer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A customer account. Deactivated customers keep their history but cannot place new orders. */
public record Customer(
        UUID id,
        Email email,
        String fullName,
        String shippingAddress,
        Status status,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status {
        ACTIVE,
        DEACTIVATED
    }

    public Customer {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(email, "email is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        fullName = required(fullName, "fullName", 200);
        shippingAddress = required(shippingAddress, "shippingAddress", 500);
    }

    public static Customer register(Email email, String fullName, String shippingAddress, Instant now) {
        return new Customer(UUID.randomUUID(), email, fullName, shippingAddress, Status.ACTIVE, now, now);
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public Customer changeShippingAddress(String newAddress, Instant now) {
        if (!isActive()) {
            throw new CustomerDeactivatedException(id);
        }
        return new Customer(id, email, fullName, newAddress, status, createdAt, now);
    }

    /** Idempotent: deactivating twice leaves the customer deactivated. */
    public Customer deactivate(Instant now) {
        return isActive() ? new Customer(id, email, fullName, shippingAddress, Status.DEACTIVATED, createdAt, now) : this;
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }
}
