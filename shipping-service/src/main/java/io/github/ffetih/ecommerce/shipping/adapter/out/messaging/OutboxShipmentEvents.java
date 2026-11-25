package io.github.ffetih.ecommerce.shipping.adapter.out.messaging;

import io.github.ffetih.ecommerce.events.shipping.ShipmentDelivered;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDispatched;
import io.github.ffetih.ecommerce.messaging.outbox.Outbox;
import io.github.ffetih.ecommerce.shipping.application.port.ShipmentEvents;
import io.github.ffetih.ecommerce.shipping.domain.Shipment;
import org.springframework.stereotype.Component;

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
}
