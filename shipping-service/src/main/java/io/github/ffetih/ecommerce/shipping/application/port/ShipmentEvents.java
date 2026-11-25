package io.github.ffetih.ecommerce.shipping.application.port;

import io.github.ffetih.ecommerce.shipping.domain.Shipment;

/** Announces shipment progress to the saga. Recorded in the caller's transaction. */
public interface ShipmentEvents {

    void dispatched(Shipment shipment);

    void delivered(Shipment shipment);
}
