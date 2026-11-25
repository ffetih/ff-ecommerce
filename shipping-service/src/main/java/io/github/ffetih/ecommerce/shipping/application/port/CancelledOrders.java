package io.github.ffetih.ecommerce.shipping.application.port;

import java.time.Instant;
import java.util.UUID;

/** Orders shipping agreed to cancel before it had a shipment for them. */
public interface CancelledOrders {

    /** Remembers the order; recording it again changes nothing. */
    void record(UUID orderId, Instant cancelledAt);

    boolean contains(UUID orderId);
}
