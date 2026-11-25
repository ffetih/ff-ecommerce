package io.github.ffetih.ecommerce.events.catalog;

import java.util.UUID;

/** Stock for an order could not be held; nothing is reserved. Topic {@value StockReserved#TOPIC}, key: order id. */
public record StockReservationFailed(UUID orderId, String reason) {

    public static final String TYPE = "catalog.stock-reservation-failed";
    public static final int VERSION = 1;
}
