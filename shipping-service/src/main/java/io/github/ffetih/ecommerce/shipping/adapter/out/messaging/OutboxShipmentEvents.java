package io.github.ffetih.ecommerce.shipping.adapter.out.messaging;

import io.github.ffetih.ecommerce.events.shipping.CancellationRefused;
import io.github.ffetih.ecommerce.events.shipping.ShipmentCancelled;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDelivered;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDispatched;
import io.github.ffetih.ecommerce.messaging.outbox.Outbox;
import io.github.ffetih.ecommerce.shipping.application.port.ShipmentEvents;
import io.github.ffetih.ecommerce.shipping.domain.Shipment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Keyed by order id, not shipment id, so one order's shipping events stay in one partition. */
@Component
class OutboxShipmentEvents implements ShipmentEvents {

    private final Outbox outbox;

    OutboxShipmentEvents(Outbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void dispatched(Shipment shipment) {
        outbox.append(ShipmentDispatched.TOPIC, shipment.orderId().toString(), ShipmentDispatched.TYPE,
                ShipmentDispatched.VERSION,
                new ShipmentDispatched(shipment.id(), shipment.orderId(), shipment.trackingNumber()));
    }

    @Override
    public void delivered(Shipment shipment) {
        outbox.append(ShipmentDispatched.TOPIC, shipment.orderId().toString(), ShipmentDelivered.TYPE,
                ShipmentDelivered.VERSION, new ShipmentDelivered(shipment.id(), shipment.orderId()));
    }

    @Override
    public void cancelled(UUID orderId, UUID shipmentId) {
        outbox.append(ShipmentDispatched.TOPIC, orderId.toString(), ShipmentCancelled.TYPE,
                ShipmentCancelled.VERSION, new ShipmentCancelled(shipmentId, orderId));
    }

    @Override
    public void cancellationRefused(Shipment shipment, String reason) {
        outbox.append(ShipmentDispatched.TOPIC, shipment.orderId().toString(), CancellationRefused.TYPE,
                CancellationRefused.VERSION, new CancellationRefused(shipment.id(), shipment.orderId(), reason));
    }
}
