package io.github.ffetih.ecommerce.events.shipping;

import java.util.UUID;

/** The customer received the parcel. Topic {@value ShipmentDispatched#TOPIC}, key: order id. */
public record ShipmentDelivered(UUID shipmentId, UUID orderId) {

    public static final String TYPE = "shipping.shipment-delivered";
    public static final int VERSION = 1;
}
