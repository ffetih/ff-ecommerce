package io.github.ffetih.ecommerce.order.application;

/** The requester is known but may not do this (as opposed to: the thing does not exist for them). */
public class NotAllowedException extends RuntimeException {

    public NotAllowedException(String message) {
        super(message);
    }
}
