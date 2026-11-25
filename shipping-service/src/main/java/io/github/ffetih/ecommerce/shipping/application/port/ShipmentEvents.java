package io.github.ffetih.ecommerce.shipping.application.port;

import io.github.ffetih.ecommerce.shipping.domain.Shipment;

import java.util.UUID;

/** Announces shipment progress to the saga. Recorded in the caller's transaction. */
public interface ShipmentEvents {

    void dispatched(Shipment shipment);

    void delivered(Shipment shipment);

    /** Answer to a cancellation request: accepted. {@code shipmentId} is null if none was prepared. */
    void cancelled(UUID orderId, UUID shipmentId);

    /** Answer to a cancellation request: refused, the parcel has left. */
    void cancellationRefused(Shipment shipment, String reason);
}
