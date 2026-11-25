package io.github.ffetih.ecommerce.order.application;

/**
 * A service we depend on did not answer in time or failed. The request may succeed if retried
 * later, and retrying with the same idempotency key is always safe.
 */
public class DownstreamUnavailableException extends RuntimeException {

    public DownstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
