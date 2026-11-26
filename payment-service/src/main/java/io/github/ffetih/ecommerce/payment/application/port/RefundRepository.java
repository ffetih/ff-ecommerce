package io.github.ffetih.ecommerce.payment.application.port;

import io.github.ffetih.ecommerce.payment.domain.Refund;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository {

    Optional<Refund> findByReturnId(UUID returnId);

    /** Reads the refund and locks its row until the surrounding transaction ends. */
    Optional<Refund> lockById(UUID refundId);

    List<Refund> findByOrderId(UUID orderId);

    /** Refunds whose provider call has had no known outcome since before {@code updatedBefore}, oldest first. */
    List<Refund> findInFlight(Instant updatedBefore, int limit);

    void save(Refund refund);
}
