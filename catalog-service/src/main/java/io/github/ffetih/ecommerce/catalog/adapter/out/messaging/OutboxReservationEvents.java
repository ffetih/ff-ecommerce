package io.github.ffetih.ecommerce.catalog.adapter.out.messaging;

import io.github.ffetih.ecommerce.catalog.application.port.ReservationEvents;
import io.github.ffetih.ecommerce.catalog.domain.Reservation;
import io.github.ffetih.ecommerce.events.catalog.StockReservationFailed;
import io.github.ffetih.ecommerce.events.catalog.StockReserved;
import io.github.ffetih.ecommerce.messaging.outbox.Outbox;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class OutboxReservationEvents implements ReservationEvents {

    private final Outbox outbox;

    OutboxReservationEvents(Outbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void reserved(Reservation reservation) {
        var event = new StockReserved(reservation.orderId(), reservation.lines().stream()
                .map(line -> new StockReserved.Line(line.sku().value(), line.quantity()))
                .toList());
        outbox.append(StockReserved.TOPIC, reservation.orderId().toString(),
                StockReserved.TYPE, StockReserved.VERSION, event);
    }

    @Override
    public void reservationFailed(UUID orderId, String reason) {
        outbox.append(StockReserved.TOPIC, orderId.toString(),
                StockReservationFailed.TYPE, StockReservationFailed.VERSION, new StockReservationFailed(orderId, reason));
    }
}
