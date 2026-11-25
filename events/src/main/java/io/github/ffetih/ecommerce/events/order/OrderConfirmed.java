package io.github.ffetih.ecommerce.events.order;

import java.util.List;
import java.util.UUID;

/** Stock is held for the order; it can be shipped. Topic {@value OrderPlaced#TOPIC}, key: order id. */
public record OrderConfirmed(UUID orderId, UUID customerId, List<Line> lines, String shippingAddress) {

    public static final String TYPE = "order.order-confirmed";
    public static final int VERSION = 1;

    public record Line(String sku, int quantity) {
    }
}
