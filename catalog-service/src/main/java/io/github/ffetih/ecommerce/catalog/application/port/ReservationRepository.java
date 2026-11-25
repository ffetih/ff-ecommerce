package io.github.ffetih.ecommerce.catalog.application.port;

import io.github.ffetih.ecommerce.catalog.domain.Reservation;

import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository {

    Optional<Reservation> findById(UUID orderId);

    /** Locks the reservation row ({@code SELECT ... FOR UPDATE}). Must be called inside a transaction. */
    Optional<Reservation> lockById(UUID orderId);

    void save(Reservation reservation);
}
