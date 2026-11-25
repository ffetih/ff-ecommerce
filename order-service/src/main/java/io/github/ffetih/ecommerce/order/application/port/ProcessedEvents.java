package io.github.ffetih.ecommerce.order.application.port;

import java.util.UUID;

/** Consumer idempotency (inbox, ADR-0005). Must be called inside the transaction that applies the event. */
public interface ProcessedEvents {

    /** Records the event for this consumer; {@code false} if it was processed before. */
    boolean firstDelivery(String consumer, UUID eventId);
}
