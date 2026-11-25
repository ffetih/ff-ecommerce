package io.github.ffetih.ecommerce.order.application;

/** Signals that a concurrent request with the same idempotency key was stored first. */
public class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException(Throwable cause) {
        super("Another request with the same idempotency key was stored first", cause);
    }
}
