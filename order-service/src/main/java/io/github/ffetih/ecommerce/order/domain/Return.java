package io.github.ffetih.ecommerce.order.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Goods of a delivered order sent back by the customer (ADR-0010).
 * <pre>
 *   REQUESTED ──receive──► RECEIVED ──refunded──► REFUNDED
 *       │                      └────refund failed──► REFUND_FAILED
 *       └──reject──► REJECTED
 * </pre>
 * The money goes back only once the parcel has arrived. The rule "never more than was bought" spans
 * every return of the order, so it is not checked here alone: the database counts the returned units
 * per order line and refuses to count past the ordered quantity (see {@code ReturnService}).
 *
 * @param refundAmount the frozen unit prices of the returned units
 */
public record Return(
        UUID id,
        UUID orderId,
        UUID customerId,
        List<ReturnLine> lines,
        Money refundAmount,
        Status status,
        String reason,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_REASON_LENGTH = 500;

    public enum Status {
        REQUESTED, RECEIVED, REJECTED, REFUNDED, REFUND_FAILED
    }

    /** What the customer asks to send back: a quantity of one SKU. */
    public record Requested(Sku sku, int quantity) {
    }

    public Return {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(customerId, "customerId is required");
        Objects.requireNonNull(refundAmount, "refundAmount is required");
        Objects.requireNonNull(status, "status is required");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A return needs at least one line");
        }
    }

    /**
     * A return of a delivered order, within {@code window} of its delivery. Each line names a SKU of
     * the order and at most the ordered quantity; prices come from the order, never from the request.
     * Only an order whose payment was captured can be refunded; orders from before payments (s08) and
     * orders whose money is overdue have nothing to give back.
     */
    public static Return request(Order order, List<Requested> requested, String reason, Duration window, Instant now) {
        if (order.status() != Order.Status.DELIVERED) {
            throw new IllegalOrderTransitionException(order.id(), order.status(), "return goods of");
        }
        if (order.deliveredAt() == null || now.isAfter(order.deliveredAt().plus(window))) {
            throw new ReturnNotAllowedException("Order " + order.id() + " was delivered more than "
                    + window.toDays() + " days ago; it can no longer be returned");
        }
        if (order.payment() != Order.PaymentStatus.CAPTURED) {
            throw new ReturnNotAllowedException("Order " + order.id() + " has no captured payment to refund ("
                    + order.payment() + ")");
        }
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("A return needs at least one line");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A return needs a reason");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("A reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
        Map<Sku, OrderLine> ordered = order.lines().stream()
                .collect(Collectors.toMap(OrderLine::sku, Function.identity()));
        var seen = new HashSet<Sku>();
        List<ReturnLine> lines = requested.stream().map(line -> {
            OrderLine orderLine = ordered.get(line.sku());
            if (orderLine == null) {
                throw new IllegalArgumentException("Order " + order.id() + " has no line " + line.sku());
            }
            if (!seen.add(line.sku())) {
                throw new IllegalArgumentException("Duplicate SKU in return: " + line.sku());
            }
            if (line.quantity() > orderLine.quantity()) {
                throw new ReturnNotAllowedException("Only " + orderLine.quantity() + " of " + line.sku() + " were ordered");
            }
            return new ReturnLine(line.sku(), line.quantity(), orderLine.unitPrice());
        }).toList();
        Money amount = lines.stream().map(ReturnLine::lineTotal).reduce(Money::plus).orElseThrow();
        return new Return(UUID.randomUUID(), order.id(), order.customerId(), lines, amount, Status.REQUESTED,
                reason.trim(), null, now, now);
    }

    /** The parcel has arrived at the warehouse: the units are stock again, the refund can start. */
    public Return receive(Instant now) {
        return switch (status) {
            case REQUESTED -> with(Status.RECEIVED, rejectionReason, now);
            case RECEIVED, REFUNDED, REFUND_FAILED -> this; // already received: a double click changes nothing
            case REJECTED -> throw new IllegalReturnTransitionException(id, status, "receive");
        };
    }

    /** The back office refuses the return, for example because the parcel never arrived. */
    public Return reject(String reason, Instant now) {
        return switch (status) {
            case REQUESTED -> {
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("A rejection needs a reason");
                }
                yield with(Status.REJECTED, reason.trim(), now);
            }
            case REJECTED -> this;
            default -> throw new IllegalReturnTransitionException(id, status, "reject");
        };
    }

    public Return refunded(Instant now) {
        return switch (status) {
            case RECEIVED -> with(Status.REFUNDED, rejectionReason, now);
            case REFUNDED -> this;
            default -> throw new IllegalReturnTransitionException(id, status, "mark refunded");
        };
    }

    public Return refundFailed(Instant now) {
        return switch (status) {
            case RECEIVED -> with(Status.REFUND_FAILED, rejectionReason, now);
            case REFUND_FAILED -> this;
            default -> throw new IllegalReturnTransitionException(id, status, "mark the refund failed for");
        };
    }

    private Return with(Status next, String rejection, Instant now) {
        return new Return(id, orderId, customerId, lines, refundAmount, next, reason, rejection, createdAt, now);
    }
}
