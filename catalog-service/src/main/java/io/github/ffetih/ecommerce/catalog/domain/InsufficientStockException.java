package io.github.ffetih.ecommerce.catalog.domain;

public class InsufficientStockException extends DomainException {

    private final Sku sku;
    private final int requested;
    private final int available;

    public InsufficientStockException(Sku sku, int requested, int available) {
        super("Insufficient stock for " + sku + ": requested " + requested + ", available " + available);
        this.sku = sku;
        this.requested = requested;
        this.available = available;
    }

    public Sku sku() {
        return sku;
    }

    public int requested() {
        return requested;
    }

    public int available() {
        return available;
    }
}
