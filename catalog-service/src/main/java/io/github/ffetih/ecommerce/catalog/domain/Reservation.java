package io.github.ffetih.ecommerce.catalog.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stock held for all lines of one order, all or nothing. The order id is the identity and the
 * idempotency key: reserving twice for the same order must not hold stock twice.
 * <pre>
 *   RESERVED --commit--> COMMITTED   (shipped)
 *   RESERVED --release-> RELEASED    (cancelled)
 * </pre>
 * Lines are kept sorted by SKU; stock rows are locked in this order to avoid deadlocks.
 */
public record Reservation(UUID orderId, List<Line> lines, Status status, Instant createdAt, Instant updatedAt) {

    public enum Status {
        RESERVED,
        COMMITTED,
        RELEASED
    }

    public record Line(Sku sku, int quantity) {

        public Line {
            Objects.requireNonNull(sku, "sku is required");
            if (quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be positive for " + sku + ": " + quantity);
            }
        }
    }

    public Reservation {
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        lines = sortedWithoutDuplicates(lines);
    }

    public static Reservation place(UUID orderId, List<Line> lines, Instant now) {
        return new Reservation(orderId, lines, Status.RESERVED, now, now);
    }

    /** True if {@code other} asks for exactly these lines, in any order. */
    public boolean hasSameLines(List<Line> other) {
        return lines.equals(sortedWithoutDuplicates(other));
    }

    public Reservation commit(Instant now) {
        return switch (status) {
            case RESERVED -> withStatus(Status.COMMITTED, now);
            case COMMITTED -> this;
            case RELEASED -> throw new InvalidReservationStateException(orderId, status, "commit");
        };
    }

    public Reservation release(Instant now) {
        return switch (status) {
            case RESERVED -> withStatus(Status.RELEASED, now);
            case RELEASED -> this;
            case COMMITTED -> throw new InvalidReservationStateException(orderId, status, "release");
        };
    }

    private Reservation withStatus(Status newStatus, Instant now) {
        return new Reservation(orderId, lines, newStatus, createdAt, now);
    }

    private static List<Line> sortedWithoutDuplicates(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A reservation needs at least one line");
        }
        var seen = new HashSet<Sku>();
        for (Line line : lines) {
            if (!seen.add(line.sku())) {
                throw new IllegalArgumentException("Duplicate SKU in reservation: " + line.sku());
            }
        }
        return lines.stream().sorted(Comparator.comparing(Line::sku)).toList();
    }
}
