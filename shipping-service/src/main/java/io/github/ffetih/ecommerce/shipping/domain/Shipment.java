package io.github.ffetih.ecommerce.shipping.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The parcel for one confirmed order.
 * <pre>
 *   CREATED ──dispatch──► DISPATCHED ──deliver──► DELIVERED
 *      └──cancel──► CANCELLED
 * </pre>
 * Dispatch is the point of no return of the order saga (ADR-0007): a dispatched shipment can no longer
 * be cancelled. Repeating a step the shipment has already taken changes nothing.
 */
public record Shipment(
        UUID id,
        UUID orderId,
        UUID customerId,
        List<ShipmentLine> lines,
        String shippingAddress,
        Status status,
        String trackingNumber,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status {
        CREATED,
        DISPATCHED,
        DELIVERED,
        CANCELLED
    }

    public Shipment {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(customerId, "customerId is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (shippingAddress == null || shippingAddress.isBlank()) {
            throw new IllegalArgumentException("Shipping address is required");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A shipment needs at least one line");
        }
        lines = List.copyOf(lines);
        if ((status == Status.DISPATCHED || status == Status.DELIVERED) && trackingNumber == null) {
            throw new IllegalStateException("A dispatched shipment has a tracking number");
        }
    }

    public static Shipment create(UUID orderId, UUID customerId, List<ShipmentLine> lines, String address, Instant now) {
        return new Shipment(UUID.randomUUID(), orderId, customerId, lines, address, Status.CREATED, null, now, now);
    }

    public boolean isDispatched() {
        return status == Status.DISPATCHED || status == Status.DELIVERED;
    }

    public Shipment dispatch(String newTrackingNumber, Instant now) {
        return switch (status) {
            case CREATED -> new Shipment(id, orderId, customerId, lines, shippingAddress, Status.DISPATCHED,
                    newTrackingNumber, createdAt, now);
            case DISPATCHED, DELIVERED -> this;
            case CANCELLED -> throw new IllegalShipmentTransitionException(id, status, "dispatch");
        };
    }

    public Shipment deliver(Instant now) {
        return switch (status) {
            case DISPATCHED -> withStatus(Status.DELIVERED, now);
            case DELIVERED -> this;
            case CREATED, CANCELLED -> throw new IllegalShipmentTransitionException(id, status, "deliver");
        };
    }

    /** Only before dispatch; callers check {@link #isDispatched()} for the too-late case. */
    public Shipment cancel(Instant now) {
        return switch (status) {
            case CREATED -> withStatus(Status.CANCELLED, now);
            case CANCELLED -> this;
            case DISPATCHED, DELIVERED -> throw new IllegalShipmentTransitionException(id, status, "cancel");
        };
    }

    private Shipment withStatus(Status newStatus, Instant now) {
        return new Shipment(id, orderId, customerId, lines, shippingAddress, newStatus, trackingNumber, createdAt, now);
    }
}
