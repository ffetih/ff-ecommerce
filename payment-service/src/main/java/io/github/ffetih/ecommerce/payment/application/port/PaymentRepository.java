package io.github.ffetih.ecommerce.payment.application.port;

import io.github.ffetih.ecommerce.payment.domain.Payment;

import java.math.BigDecimal;
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

    /**
     * Counts {@code amount} more as refunded, in one conditional statement: only for a captured payment,
     * and only if all its refunds together stay within the captured amount. Call inside the transaction
     * that stores the refund.
     *
     * @return {@code false}, changing nothing, if the payment is not captured or the limit would be exceeded
     */
    boolean reserveRefund(UUID paymentId, BigDecimal amount);

    /** A declined refund's amount can be refunded again. */
    void releaseRefund(UUID paymentId, BigDecimal amount);

    /** Refunded or being refunded, as counted by {@link #reserveRefund}. */
    BigDecimal refundedAmount(UUID paymentId);
}
