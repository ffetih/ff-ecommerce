package io.github.ffetih.ecommerce.order.adapter.in.messaging;

import io.github.ffetih.ecommerce.events.catalog.StockReservationFailed;
import io.github.ffetih.ecommerce.events.catalog.StockReserved;
import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import io.github.ffetih.ecommerce.messaging.inbox.EventReader;
import io.github.ffetih.ecommerce.order.application.OrderSagaService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class ReservationListener {

    private final EventReader reader;
    private final OrderSagaService saga;

    ReservationListener(EventReader reader, OrderSagaService saga) {
        this.reader = reader;
        this.saga = saga;
    }

    @KafkaListener(topics = StockReserved.TOPIC)
    public void on(String message) {
        EventEnvelope event = reader.read(message);
        switch (event.eventType()) {
            case StockReserved.TYPE -> saga.stockReserved(
                    event.eventId(), reader.data(event, StockReserved.class).orderId());
            case StockReservationFailed.TYPE -> {
                StockReservationFailed failed = reader.data(event, StockReservationFailed.class);
                saga.reservationFailed(event.eventId(), failed.orderId(), failed.reason());
            }
            default -> {
                // not relevant for orders
            }
        }
    }
}
