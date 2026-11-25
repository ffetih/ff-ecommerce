package io.github.ffetih.ecommerce.events.shipping;

import java.util.UUID;

/** The parcel left the warehouse. Topic {@value #TOPIC}, key: order id (so one order's shipping events stay ordered). */
public record ShipmentDispatched(UUID shipmentId, UUID orderId, String trackingNumber) {

    public static final String TOPIC = "shipping.shipments";
    public static final String TYPE = "shipping.shipment-dispatched";
    public static final int VERSION = 1;
}
