package io.github.ffetih.ecommerce.catalog.application.port;

import io.github.ffetih.ecommerce.catalog.domain.Reservation;

import java.util.UUID;

/** Tells the order saga whether stock could be held. Recorded in the caller's transaction. */
public interface ReservationEvents {

    void reserved(Reservation reservation);

    void reservationFailed(UUID orderId, String reason);
}
