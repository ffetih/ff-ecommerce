package io.github.ffetih.ecommerce.shipping.adapter.out.messaging;

import io.github.ffetih.ecommerce.messaging.inbox.Inbox;
import io.github.ffetih.ecommerce.shipping.application.port.ProcessedEvents;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class InboxProcessedEvents implements ProcessedEvents {

    private final Inbox inbox;

    InboxProcessedEvents(Inbox inbox) {
        this.inbox = inbox;
    }

    @Override
    public boolean firstDelivery(String consumer, UUID eventId) {
        return inbox.markProcessed(consumer, eventId);
    }
}
