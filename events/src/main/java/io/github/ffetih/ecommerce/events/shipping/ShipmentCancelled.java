package io.github.ffetih.ecommerce.events.shipping;

import java.util.UUID;

/**
 * Shipping accepted a cancellation request: nothing will be dispatched for this order.
 * Topic {@value ShipmentDispatched#TOPIC}, key: order id.
 *
 * @param shipmentId {@code null} if no shipment had been prepared for the order
 */
public record ShipmentCancelled(UUID shipmentId, UUID orderId) {

    public static final String TYPE = "shipping.shipment-cancelled";
    public static final int VERSION = 1;
}
