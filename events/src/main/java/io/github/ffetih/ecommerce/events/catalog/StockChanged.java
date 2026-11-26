package io.github.ffetih.ecommerce.events.catalog;

import java.util.UUID;

/**
 * The stock level of one SKU changed. Published on topic {@value #TOPIC} with the SKU as record key,
 * so all changes of one SKU arrive in the order they were committed.
 *
 * @param sku       the product
 * @param onHand    units in the warehouse after the change
 * @param reserved  units held for open orders after the change
 * @param available units a new order can take after the change
 * @param reason    what changed it: {@code RECEIVED}, {@code RESERVED}, {@code RELEASED}, {@code COMMITTED} or
 *                  {@code RETURNED}
 * @param reference the goods receipt, order or return that caused the change
 */
public record StockChanged(String sku, int onHand, int reserved, int available, String reason, UUID reference) {

    public static final String TOPIC = "catalog.stock";
    public static final String TYPE = "catalog.stock-changed";
    public static final int VERSION = 1;

    public static final String RECEIVED = "RECEIVED";
    public static final String RESERVED = "RESERVED";
    public static final String RELEASED = "RELEASED";
    public static final String COMMITTED = "COMMITTED";
    /** Units of a received return are in the warehouse again (ADR-0010). */
    public static final String RETURNED = "RETURNED";
}
