package io.github.ffetih.ecommerce.catalog.adapter.in.messaging;

import io.github.ffetih.ecommerce.catalog.application.ReservationService;
import io.github.ffetih.ecommerce.catalog.domain.Reservation;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.events.order.OrderCancelled;
import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import io.github.ffetih.ecommerce.messaging.inbox.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Catalog's part of the order saga on {@code order.lifecycle}. Event types catalog does not care
 * about (for example {@code order.order-confirmed}) are skipped, so producers can add types freely.
 */
@Component
class OrderLifecycleListener {

    private final EventReader reader;
    private final ReservationService reservations;

    OrderLifecycleListener(EventReader reader, ReservationService reservations) {
        this.reader = reader;
        this.reservations = reservations;
    }

    @KafkaListener(topics = OrderPlaced.TOPIC)
    public void on(String message) {
        EventEnvelope event = reader.read(message);
        switch (event.eventType()) {
            case OrderPlaced.TYPE -> {
                OrderPlaced placed = reader.data(event, OrderPlaced.class);
                reservations.reserveForOrder(event.eventId(), placed.orderId(), placed.lines().stream()
                        .map(line -> new Reservation.Line(Sku.of(line.sku()), line.quantity()))
                        .toList());
            }
            case OrderCancelled.TYPE -> {
                OrderCancelled cancelled = reader.data(event, OrderCancelled.class);
                reservations.releaseForOrder(event.eventId(), cancelled.orderId());
            }
            default -> {
                // not relevant for catalog
            }
        }
    }
}
