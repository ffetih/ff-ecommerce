package io.github.ffetih.ecommerce.shipping.adapter.in.messaging;

import io.github.ffetih.ecommerce.events.order.OrderCancellationRequested;
import io.github.ffetih.ecommerce.events.order.OrderCancelled;
import io.github.ffetih.ecommerce.events.order.OrderConfirmed;
import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import io.github.ffetih.ecommerce.messaging.inbox.EventReader;
import io.github.ffetih.ecommerce.shipping.application.ShippingService;
import io.github.ffetih.ecommerce.shipping.domain.ShipmentLine;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class OrderLifecycleListener {

    private final EventReader reader;
    private final ShippingService shipping;

    OrderLifecycleListener(EventReader reader, ShippingService shipping) {
        this.reader = reader;
        this.shipping = shipping;
    }

    @KafkaListener(topics = OrderPlaced.TOPIC, groupId = ShippingService.ORDER_EVENTS_CONSUMER)
    public void on(String message) {
        EventEnvelope event = reader.read(message);
        switch (event.eventType()) {
            case OrderConfirmed.TYPE -> {
                OrderConfirmed confirmed = reader.data(event, OrderConfirmed.class);
                shipping.orderConfirmed(event.eventId(), confirmed.orderId(), confirmed.customerId(),
                        confirmed.lines().stream().map(line -> new ShipmentLine(line.sku(), line.quantity())).toList(),
                        confirmed.shippingAddress());
            }
            case OrderCancellationRequested.TYPE -> shipping.cancellationRequested(
                    event.eventId(), reader.data(event, OrderCancellationRequested.class).orderId());
            case OrderCancelled.TYPE -> shipping.orderCancelled(
                    event.eventId(), reader.data(event, OrderCancelled.class).orderId());
            default -> {
                // order-placed and others: not relevant until the order is confirmed
            }
        }
    }
}
