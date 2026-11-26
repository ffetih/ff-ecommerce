package io.github.ffetih.ecommerce.order.domain;

/** A well-formed return request that the rules refuse: too late, not paid, more than was bought. */
public class ReturnNotAllowedException extends RuntimeException {

    public ReturnNotAllowedException(String message) {
        super(message);
    }
}
