package io.github.ffetih.ecommerce.catalog.application;

import io.github.ffetih.ecommerce.catalog.domain.Sku;

import java.util.UUID;

/** The request contradicts existing state, e.g. an idempotency key reused with a different payload. */
public class ConflictException extends RuntimeException {

    private ConflictException(String message) {
        super(message);
    }

    public static ConflictException productExists(Sku sku) {
        return new ConflictException("Product already exists: " + sku);
    }

    public static ConflictException idempotencyKeyReused(UUID key) {
        return new ConflictException(
                "Key " + key + " was already used for a different request; use a new key for a new request");
    }
}
