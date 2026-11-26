package io.github.ffetih.ecommerce.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One money movement in the double-entry ledger: entries in one currency that sum to zero, so money
 * is never created or lost, only moved between accounts. An authorization books nothing: the money
 * is only held on the card. A capture moves it (ADR-0009).
 *
 * @param reference the idempotency key of the provider call this books; booking it again is a no-op
 */
public record LedgerTransaction(UUID id, UUID paymentId, String reference, Kind kind, Currency currency,
                                List<LedgerEntry> entries, Instant bookedAt) {

    public enum Kind {
        CAPTURE
    }

    public LedgerTransaction {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(reference, "reference is required");
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(bookedAt, "bookedAt is required");
        entries = List.copyOf(entries);
        if (entries.size() < 2) {
            throw new IllegalArgumentException("A ledger transaction needs at least two entries");
        }
        BigDecimal sum = entries.stream().map(LedgerEntry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() != 0) {
            throw new IllegalArgumentException("Ledger entries must sum to zero, but sum to " + sum);
        }
    }

    /** The provider took the money: it now owes it to the shop, and the order's amount is revenue. */
    public static LedgerTransaction capture(Payment payment, Instant now) {
        if (payment.status() != Payment.Status.CAPTURED) {
            throw new IllegalPaymentTransitionException(payment.id(), payment.status(), "book the capture of");
        }
        BigDecimal amount = payment.amount().amount();
        return new LedgerTransaction(UUID.randomUUID(), payment.id(), payment.idempotencyKey(Payment.Operation.CAPTURE),
                Kind.CAPTURE, payment.amount().currency(),
                List.of(new LedgerEntry(Account.PROVIDER_RECEIVABLE, amount),
                        new LedgerEntry(Account.SALES, amount.negate())),
                now);
    }
}
