package io.github.ffetih.ecommerce.order.domain;

import java.util.Objects;

/** Units of one order line sent back, refunded at the unit price frozen in the order. */
public record ReturnLine(Sku sku, int quantity, Money unitPrice) {

    public ReturnLine {
        Objects.requireNonNull(sku, "sku is required");
        Objects.requireNonNull(unitPrice, "unitPrice is required");
        if (quantity <= 0) {
            throw new IllegalArgumentException("A returned quantity must be positive: " + quantity);
        }
    }

    public Money lineTotal() {
        return unitPrice.times(quantity);
    }
}
