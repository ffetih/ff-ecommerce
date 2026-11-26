package io.github.ffetih.ecommerce.payment.application.port;

import io.github.ffetih.ecommerce.payment.domain.Payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {

    Optional<Payment> findByOrderId(UUID orderId);

    /**
     * Reads the payment and locks its row until the surrounding transaction ends, so a command and a
     * provider answer for the same payment are applied one after the other, never interleaved.
     */
    Optional<Payment> lockByOrderId(UUID orderId);

    /** Payments whose provider call has had no known outcome since before {@code updatedBefore}, oldest first. */
    List<Payment> findInFlight(Instant updatedBefore, int limit);

    void save(Payment payment);
}
