package io.github.ffetih.ecommerce.order.domain;

import java.util.Objects;

/** One product in an order, with the unit price copied from the catalog when the order was placed. */
public record OrderLine(Sku sku, int quantity, Money unitPrice) {

    public static final int MAX_QUANTITY = 1_000;

    public OrderLine {
        Objects.requireNonNull(sku, "sku is required");
        Objects.requireNonNull(unitPrice, "unitPrice is required");
        if (quantity <= 0 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException(
                    "Quantity for " + sku + " must be between 1 and " + MAX_QUANTITY + ": " + quantity);
        }
    }

    public Money lineTotal() {
        return unitPrice.times(quantity);
    }
}
