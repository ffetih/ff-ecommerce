package io.github.ffetih.ecommerce.order.application;

import java.util.UUID;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(UUID key) {
        super("Idempotency key " + key + " was already used for a different request");
    }
}
