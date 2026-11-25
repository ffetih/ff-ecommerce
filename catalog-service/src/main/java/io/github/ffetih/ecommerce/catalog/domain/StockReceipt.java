package io.github.ffetih.ecommerce.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A goods receipt. The caller's reference id makes a retried receipt a no-op instead of double stock. */
public record StockReceipt(UUID referenceId, Sku sku, int quantity, Instant receivedAt) {

    public StockReceipt {
        Objects.requireNonNull(referenceId, "referenceId is required");
        Objects.requireNonNull(sku, "sku is required");
        Objects.requireNonNull(receivedAt, "receivedAt is required");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
    }

    public boolean isSameRequestAs(Sku otherSku, int otherQuantity) {
        return sku.equals(otherSku) && quantity == otherQuantity;
    }
}
