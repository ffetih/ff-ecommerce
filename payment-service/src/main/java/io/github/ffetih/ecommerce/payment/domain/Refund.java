package io.github.ffetih.ecommerce.payment.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Money given back for one return (ADR-0010). A payment can have several, together never more than
 * the captured amount; that limit is counted on the payment by the database, not here.
 * <pre>
 *   REFUNDING ──approved──► REFUNDED
 *       └─────declined────► FAILED
 * </pre>
 * REFUNDING means the provider call has no known outcome yet; as for every call, no answer is not a
 * "no" (ADR-0009).
 */
public record Refund(
        UUID id,
        UUID paymentId,
        UUID orderId,
        UUID returnId,
        Money amount,
        Status status,
        String providerReference,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status {
        REFUNDING, REFUNDED, FAILED
    }

    public Refund {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(returnId, "returnId is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(status, "status is required");
    }

    public static Refund start(Payment payment, UUID returnId, Money amount, Instant now) {
        if (!amount.currency().equals(payment.amount().currency())) {
            throw new IllegalArgumentException("A refund in " + amount.currency() + " for a payment in "
                    + payment.amount().currency());
        }
        return new Refund(UUID.randomUUID(), payment.id(), payment.orderId(), returnId, amount, Status.REFUNDING,
                null, null, now, now);
    }

    /** A refund refused before the provider was asked, for example because it would exceed the capture. */
    public static Refund refused(Payment payment, UUID returnId, Money amount, String reason, Instant now) {
        return start(payment, returnId, amount, now).failed(reason, now);
    }

    public Refund refunded(String reference, Instant now) {
        return switch (status) {
            case REFUNDING -> new Refund(id, paymentId, orderId, returnId, amount, Status.REFUNDED, reference, null,
                    createdAt, now);
            case REFUNDED -> this;
            case FAILED -> throw new IllegalStateException("Refund " + id + " has failed; it cannot be approved");
        };
    }

    public Refund failed(String reason, Instant now) {
        return switch (status) {
            case REFUNDING -> new Refund(id, paymentId, orderId, returnId, amount, Status.FAILED, null, reason,
                    createdAt, now);
            case FAILED -> this;
            case REFUNDED -> throw new IllegalStateException("Refund " + id + " is done; it cannot fail");
        };
    }

    public boolean inFlight() {
        return status == Status.REFUNDING;
    }

    /** The same for every attempt, and different for every return: two returns are two refunds. */
    public String idempotencyKey() {
        return paymentId + ":refund:" + returnId;
    }
}
