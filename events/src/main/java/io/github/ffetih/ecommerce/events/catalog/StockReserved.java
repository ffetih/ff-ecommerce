package io.github.ffetih.ecommerce.events.catalog;

import java.util.List;
import java.util.UUID;

/** Stock for all lines of an order is held. Topic {@value #TOPIC}, key: order id. */
public record StockReserved(UUID orderId, List<Line> lines) {

    public static final String TOPIC = "catalog.reservations";
    public static final String TYPE = "catalog.stock-reserved";
    public static final int VERSION = 1;

    public record Line(String sku, int quantity) {
    }
}
