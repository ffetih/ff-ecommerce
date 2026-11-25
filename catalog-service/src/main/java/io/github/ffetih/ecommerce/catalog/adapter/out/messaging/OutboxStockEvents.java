package io.github.ffetih.ecommerce.catalog.adapter.out.messaging;

import io.github.ffetih.ecommerce.catalog.application.port.StockEvents;
import io.github.ffetih.ecommerce.catalog.domain.StockItem;
import io.github.ffetih.ecommerce.events.catalog.StockChanged;
import io.github.ffetih.ecommerce.messaging.outbox.Outbox;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class OutboxStockEvents implements StockEvents {

    private final Outbox outbox;

    OutboxStockEvents(Outbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void stockChanged(StockItem item, String reason, UUID reference) {
        var event = new StockChanged(
                item.sku().value(), item.onHand(), item.reserved(), item.available(), reason, reference);
        outbox.append(StockChanged.TOPIC, item.sku().value(), StockChanged.TYPE, StockChanged.VERSION, event);
    }
}
