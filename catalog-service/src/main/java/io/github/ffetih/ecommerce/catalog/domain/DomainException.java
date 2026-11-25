package io.github.ffetih.ecommerce.catalog.domain;

/** Base type for business rule violations; adapters map these to client errors, not server errors. */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
