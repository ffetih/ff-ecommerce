package io.github.ffetih.ecommerce.catalog.domain;

import java.util.Objects;

/** A sellable product and its current unit price. The price is the only price an order may use. */
public record Product(Sku sku, String name, Money unitPrice, boolean active) {

    public Product {
        Objects.requireNonNull(sku, "sku is required");
        Objects.requireNonNull(unitPrice, "unitPrice is required");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Product name is required");
        }
        name = name.trim();
    }

    public static Product create(Sku sku, String name, Money unitPrice) {
        return new Product(sku, name, unitPrice, true);
    }

    public Product changePrice(Money newPrice) {
        return new Product(sku, name, newPrice, active);
    }

    public Product deactivate() {
        return new Product(sku, name, unitPrice, false);
    }
}
