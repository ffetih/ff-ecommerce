package io.github.ffetih.ecommerce.events.order;

import java.util.UUID;

/**
 * The order will not be fulfilled. Whoever holds something for it (stock, a shipment) gives it back.
 * Topic {@value OrderPlaced#TOPIC}, key: order id.
 */
public record OrderCancelled(UUID orderId, String reason) {

    public static final String TYPE = "order.order-cancelled";
    public static final int VERSION = 1;
}
