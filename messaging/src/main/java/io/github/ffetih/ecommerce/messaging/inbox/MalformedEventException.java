package io.github.ffetih.ecommerce.messaging.inbox;

/**
 * The message cannot be read as an event. Retrying cannot help, so the error handler sends it to the
 * dead-letter topic immediately instead of blocking the partition.
 */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
