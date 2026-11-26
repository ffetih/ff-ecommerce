package io.github.ffetih.ecommerce.order.adapter.out.messaging;

import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.events.order.ReturnReceived;
import io.github.ffetih.ecommerce.messaging.outbox.Outbox;
import io.github.ffetih.ecommerce.order.application.port.ReturnEvents;
import io.github.ffetih.ecommerce.order.domain.Return;
import org.springframework.stereotype.Component;

/** On the order's lifecycle topic, keyed by order id, after everything else that happened to the order. */
@Component
class OutboxReturnEvents implements ReturnEvents {

    private final Outbox outbox;

    OutboxReturnEvents(Outbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void returnReceived(Return received) {
        outbox.append(OrderPlaced.TOPIC, received.orderId().toString(), ReturnReceived.TYPE, ReturnReceived.VERSION,
                new ReturnReceived(received.id(), received.orderId(), received.lines().stream()
                        .map(line -> new ReturnReceived.Line(line.sku().value(), line.quantity()))
                        .toList()));
    }
}
