package io.github.ffetih.ecommerce.order.adapter.out.messaging;

import io.github.ffetih.ecommerce.events.order.OrderCancelled;
import io.github.ffetih.ecommerce.events.order.OrderConfirmed;
import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.messaging.outbox.Outbox;
import io.github.ffetih.ecommerce.order.application.port.OrderEvents;
import io.github.ffetih.ecommerce.order.domain.Order;
import org.springframework.stereotype.Component;

/** All lifecycle events of an order go to one topic, keyed by order id, so they stay in order. */
@Component
class OutboxOrderEvents implements OrderEvents {

    private final Outbox outbox;

    OutboxOrderEvents(Outbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void orderPlaced(Order order) {
        var event = new OrderPlaced(
                order.id(),
                order.customerId(),
                order.lines().stream()
                        .map(line -> new OrderPlaced.Line(line.sku().value(), line.quantity(), line.unitPrice().amount()))
                        .toList(),
                order.total().amount(),
                order.total().currency().getCurrencyCode(),
                order.shippingAddress());
        append(order, OrderPlaced.TYPE, OrderPlaced.VERSION, event);
    }

    @Override
    public void orderConfirmed(Order order) {
        var event = new OrderConfirmed(
                order.id(),
                order.customerId(),
                order.lines().stream().map(line -> new OrderConfirmed.Line(line.sku().value(), line.quantity())).toList(),
                order.shippingAddress());
        append(order, OrderConfirmed.TYPE, OrderConfirmed.VERSION, event);
    }

    @Override
    public void orderCancelled(Order order) {
        append(order, OrderCancelled.TYPE, OrderCancelled.VERSION,
                new OrderCancelled(order.id(), order.cancellationReason()));
    }

    private void append(Order order, String type, int version, Object event) {
        outbox.append(OrderPlaced.TOPIC, order.id().toString(), type, version, event);
    }
}
