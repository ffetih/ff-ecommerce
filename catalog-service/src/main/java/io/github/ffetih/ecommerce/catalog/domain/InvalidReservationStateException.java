package io.github.ffetih.ecommerce.catalog.domain;

import java.util.UUID;

public class InvalidReservationStateException extends DomainException {

    public InvalidReservationStateException(UUID orderId, Reservation.Status current, String action) {
        super("Cannot " + action + " reservation for order " + orderId + " in status " + current);
    }
}
