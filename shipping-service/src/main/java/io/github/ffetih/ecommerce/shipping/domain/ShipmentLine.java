package io.github.ffetih.ecommerce.shipping.domain;

import java.util.Objects;

/** What goes into the parcel. Shipping does not care about prices. */
public record ShipmentLine(String sku, int quantity) {

    public ShipmentLine {
        Objects.requireNonNull(sku, "sku is required");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive for " + sku);
        }
    }
}
