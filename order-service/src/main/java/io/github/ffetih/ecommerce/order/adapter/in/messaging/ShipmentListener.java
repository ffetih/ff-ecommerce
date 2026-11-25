package io.github.ffetih.ecommerce.order.adapter.in.messaging;

import io.github.ffetih.ecommerce.events.shipping.CancellationRefused;
import io.github.ffetih.ecommerce.events.shipping.ShipmentCancelled;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDelivered;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDispatched;
import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import io.github.ffetih.ecommerce.messaging.inbox.EventReader;
import io.github.ffetih.ecommerce.order.application.OrderSagaService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class ShipmentListener {

    private final EventReader reader;
    private final OrderSagaService saga;

    ShipmentListener(EventReader reader, OrderSagaService saga) {
        this.reader = reader;
        this.saga = saga;
    }

    @KafkaListener(topics = ShipmentDispatched.TOPIC, groupId = OrderSagaService.SHIPMENT_EVENTS_CONSUMER)
    public void on(String message) {
        EventEnvelope event = reader.read(message);
        switch (event.eventType()) {
            case ShipmentDispatched.TYPE -> saga.shipmentDispatched(
                    event.eventId(), reader.data(event, ShipmentDispatched.class).orderId());
            case ShipmentDelivered.TYPE -> saga.shipmentDelivered(
                    event.eventId(), reader.data(event, ShipmentDelivered.class).orderId());
            case ShipmentCancelled.TYPE -> saga.shipmentCancelled(
                    event.eventId(), reader.data(event, ShipmentCancelled.class).orderId());
            case CancellationRefused.TYPE -> saga.cancellationRefused(
                    event.eventId(), reader.data(event, CancellationRefused.class).orderId());
            default -> {
                // not relevant for orders
            }
        }
    }
}
