package io.github.ffetih.ecommerce.catalog.adapter.in.messaging;

import io.github.ffetih.ecommerce.catalog.application.ReservationService;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDispatched;
import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import io.github.ffetih.ecommerce.messaging.inbox.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** When a parcel leaves the warehouse, its reserved units leave the stock. */
@Component
class ShipmentListener {

    private final EventReader reader;
    private final ReservationService reservations;

    ShipmentListener(EventReader reader, ReservationService reservations) {
        this.reader = reader;
        this.reservations = reservations;
    }

    @KafkaListener(topics = ShipmentDispatched.TOPIC, groupId = ReservationService.SHIPMENT_EVENTS_CONSUMER)
    public void on(String message) {
        EventEnvelope event = reader.read(message);
        if (ShipmentDispatched.TYPE.equals(event.eventType())) {
            ShipmentDispatched dispatched = reader.data(event, ShipmentDispatched.class);
            reservations.commitForOrder(event.eventId(), dispatched.orderId());
        }
    }
}
