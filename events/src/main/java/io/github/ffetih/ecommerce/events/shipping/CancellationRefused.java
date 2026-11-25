package io.github.ffetih.ecommerce.events.shipping;

import java.util.UUID;

/**
 * Shipping refused a cancellation request: the parcel had already left. The order goes on to be
 * shipped; {@code shipment-dispatched} for it precedes this event on the topic.
 * Topic {@value ShipmentDispatched#TOPIC}, key: order id.
 */
public record CancellationRefused(UUID shipmentId, UUID orderId, String reason) {

    public static final String TYPE = "shipping.cancellation-refused";
    public static final int VERSION = 1;
}
