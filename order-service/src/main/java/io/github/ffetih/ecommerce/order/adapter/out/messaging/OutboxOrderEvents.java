package io.github.ffetih.ecommerce.order.adapter.out.messaging;

import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.messaging.outbox.Outbox;
import io.github.ffetih.ecommerce.order.application.port.OrderEvents;
import io.github.ffetih.ecommerce.order.domain.Order;
import org.springframework.stereotype.Component;

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
        outbox.append(OrderPlaced.TOPIC, order.id().toString(), OrderPlaced.TYPE, OrderPlaced.VERSION, event);
    }
}
