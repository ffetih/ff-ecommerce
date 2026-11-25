package io.github.ffetih.ecommerce.order.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer order. Its status only moves forward:
 * <pre>
 *   PLACED ──confirm──► CONFIRMED ──ship──► SHIPPED ──deliver──► DELIVERED
 *     │                    │
 *     └──────cancel────────┴──► CANCELLED
 * </pre>
 * Events arrive at least once and topics are not ordered with each other, so every step is
 * idempotent: asking for a status the order already has, or has already passed, changes nothing.
 * Only genuinely impossible steps fail, such as shipping a cancelled order or cancelling a shipped one.
 */
public record Order(
        UUID id,
        UUID customerId,
        List<OrderLine> lines,
        Money total,
        String shippingAddress,
        Status status,
        String cancellationReason,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_LINES = 50;

    public enum Status {
        PLACED(0), CONFIRMED(1), SHIPPED(2), DELIVERED(3), CANCELLED(-1);

        private final int progress;

        Status(int progress) {
            this.progress = progress;
        }

        boolean isAtOrBeyond(Status other) {
            return this != CANCELLED && progress >= other.progress;
        }
    }

    public Order {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(customerId, "customerId is required");
        Objects.requireNonNull(total, "total is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (shippingAddress == null || shippingAddress.isBlank()) {
            throw new IllegalArgumentException("Shipping address is required");
        }
        lines = List.copyOf(lines);
    }

    /**
     * Places an order. All lines must share one currency, and every SKU may appear only once;
     * the total is computed here, never accepted from the client.
     */
    public static Order place(UUID customerId, List<OrderLine> lines, String shippingAddress, Instant now) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        if (lines.size() > MAX_LINES) {
            throw new IllegalArgumentException("An order can have at most " + MAX_LINES + " lines");
        }
        var skus = new HashSet<Sku>();
        Money total = null;
        for (OrderLine line : lines) {
            if (!skus.add(line.sku())) {
                throw new IllegalArgumentException("Duplicate SKU in order: " + line.sku());
            }
            total = total == null ? line.lineTotal() : total.plus(line.lineTotal()); // plus() rejects mixed currencies
        }
        return new Order(UUID.randomUUID(), customerId, lines, total, shippingAddress.trim(),
                Status.PLACED, null, now, now);
    }

    /** Stock is reserved. Already confirmed, further along, or cancelled: nothing changes. */
    public Order confirm(Instant now) {
        if (status != Status.PLACED) {
            return this;
        }
        return withStatus(Status.CONFIRMED, null, now);
    }

    public Order ship(Instant now) {
        if (status.isAtOrBeyond(Status.SHIPPED)) {
            return this;
        }
        if (status != Status.CONFIRMED) {
            throw new IllegalOrderTransitionException(id, status, "ship");
        }
        return withStatus(Status.SHIPPED, null, now);
    }

    /** A delivery may be reported before the shipment notice arrives; CONFIRMED -> DELIVERED is allowed. */
    public Order deliver(Instant now) {
        if (status == Status.DELIVERED) {
            return this;
        }
        if (status != Status.CONFIRMED && status != Status.SHIPPED) {
            throw new IllegalOrderTransitionException(id, status, "deliver");
        }
        return withStatus(Status.DELIVERED, null, now);
    }

    public Order cancel(String reason, Instant now) {
        if (status == Status.CANCELLED) {
            return this;
        }
        if (status != Status.PLACED && status != Status.CONFIRMED) {
            throw new IllegalOrderTransitionException(id, status, "cancel");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A cancellation needs a reason");
        }
        return withStatus(Status.CANCELLED, reason.trim(), now);
    }

    private Order withStatus(Status newStatus, String reason, Instant now) {
        return new Order(id, customerId, lines, total, shippingAddress, newStatus, reason, createdAt, now);
    }
}
