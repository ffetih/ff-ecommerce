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
 * is only held on the card. A capture moves it (ADR-0009); a refund moves it back, as a new
 * transaction with the capture's accounts reversed, never by changing the capture (ADR-0010).
 *
 * @param reference the idempotency key of the provider call this books; booking it again is a no-op
 */
public record LedgerTransaction(UUID id, UUID paymentId, String reference, Kind kind, Currency currency,
                                List<LedgerEntry> entries, Instant bookedAt) {

    public enum Kind {
        CAPTURE, REFUND
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

    /** The money for a return went back: sales shrink, and the provider owes the shop that much less. */
    public static LedgerTransaction refund(Refund refund, Instant now) {
        if (refund.status() != Refund.Status.REFUNDED) {
            throw new IllegalStateException("Only a completed refund is booked; refund " + refund.id() + " is "
                    + refund.status());
        }
        BigDecimal amount = refund.amount().amount();
        return new LedgerTransaction(UUID.randomUUID(), refund.paymentId(), refund.idempotencyKey(), Kind.REFUND,
                refund.amount().currency(),
                List.of(new LedgerEntry(Account.SALES, amount),
                        new LedgerEntry(Account.PROVIDER_RECEIVABLE, amount.negate())),
                now);
    }
}
