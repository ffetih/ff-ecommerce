package io.github.ffetih.ecommerce.catalog.domain;

import java.util.Objects;

/**
 * Stock of one SKU.
 * <pre>
 *   on_hand   = units physically in the warehouse
 *   reserved  = units promised to open orders, still in the warehouse
 *   available = on_hand - reserved   (what a new order may take)
 * </pre>
 * Invariant: {@code 0 <= reserved <= on_hand}.
 */
public record StockItem(Sku sku, int onHand, int reserved) {

    public StockItem {
        Objects.requireNonNull(sku, "sku is required");
        if (onHand < 0 || reserved < 0 || reserved > onHand) {
            throw new IllegalStateException(
                    "Stock invariant violated for " + sku + ": onHand=" + onHand + ", reserved=" + reserved);
        }
    }

    public static StockItem empty(Sku sku) {
        return new StockItem(sku, 0, 0);
    }

    public int available() {
        return onHand - reserved;
    }

    /** Goods received into the warehouse. */
    public StockItem receive(int quantity) {
        requirePositive(quantity);
        return new StockItem(sku, onHand + quantity, reserved);
    }

    /** Hold units for an order; they stay on hand but are no longer available. */
    public StockItem reserve(int quantity) {
        requirePositive(quantity);
        if (quantity > available()) {
            throw new InsufficientStockException(sku, quantity, available());
        }
        return new StockItem(sku, onHand, reserved + quantity);
    }

    /** Give held units back, e.g. when an order is cancelled. */
    public StockItem release(int quantity) {
        requirePositive(quantity);
        return new StockItem(sku, onHand, reserved - quantity);
    }

    /** Held units leave the warehouse with the shipment. */
    public StockItem commit(int quantity) {
        requirePositive(quantity);
        return new StockItem(sku, onHand - quantity, reserved - quantity);
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
    }
}
