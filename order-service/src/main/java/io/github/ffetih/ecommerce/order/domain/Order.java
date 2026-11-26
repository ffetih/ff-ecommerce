package io.github.ffetih.ecommerce.order.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer order. Its status only moves forward, except that a cancellation request ends one way
 * or the other:
 * <pre>
 *   PLACED ──paid─────► CONFIRMED ──ship─────────────────────────► SHIPPED ──deliver──► DELIVERED
 *     │                   │   ▲                                      ▲
 *     │ cancel            │   │ cancellation refused                 │ ship (dispatch won)
 *     │                   ▼   │                                      │
 *     │             CANCELLATION_REQUESTED ──────────────────────────┘
 *     ▼                   │ cancellation accepted
 *   CANCELLED ◄───────────┘
 * </pre>
 * Before confirmation the order cancels on its own: nothing is held for it in shipping yet. Once
 * confirmed, a shipment exists, and only shipping knows whether it has left: the customer's
 * cancellation becomes a request that shipping accepts or refuses (ADR-0007).
 * <p>
 * The order also tracks what it has asked of payment-service (ADR-0009). It is confirmed only when
 * the stock is reserved <em>and</em> the payment is authorized; until then it stays PLACED:
 * <pre>
 *   NOT_REQUESTED ──stock reserved──► AUTHORIZING ──authorized──► AUTHORIZED ──shipped──► CAPTURING ──► CAPTURED
 *                                          │       ──declined───► DECLINED (order cancelled)   └──────► OVERDUE
 *                                          └──── order cancelled ───► VOIDING ──► VOIDED ◄── order cancelled ──┘ (from AUTHORIZED)
 * </pre>
 * The states ending in -ING mean a command is on its way; the service sends it when the order enters
 * that state. Orders placed before payments existed stay NOT_REQUESTED and need no payment.
 * <p>
 * Events arrive at least once, so every step is idempotent: asking for a status the order already
 * has, or has already passed, changes nothing. Only genuinely impossible steps fail, such as
 * shipping a cancelled order or cancelling a shipped one.
 */
public record Order(
        UUID id,
        UUID customerId,
        List<OrderLine> lines,
        Money total,
        String shippingAddress,
        Status status,
        String cancellationReason,
        String cardToken,
        PaymentStatus payment,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_LINES = 50;

    /** Same limit as customer-service and the {@code orders.shipping_address} column. */
    public static final int MAX_ADDRESS_LENGTH = 500;

    public enum Status {
        PLACED(0), CONFIRMED(1), CANCELLATION_REQUESTED(1), SHIPPED(2), DELIVERED(3), CANCELLED(-1);

        private final int progress;

        Status(int progress) {
            this.progress = progress;
        }

        boolean isAtOrBeyond(Status other) {
            return this != CANCELLED && progress >= other.progress;
        }
    }

    /** What order-service has asked of payment-service, and what it was told. */
    public enum PaymentStatus {
        NOT_REQUESTED, AUTHORIZING, AUTHORIZED, DECLINED, VOIDING, VOIDED, CAPTURING, CAPTURED,
        /** The parcel left but the capture failed: the customer owes the money, operations collect it. */
        OVERDUE
    }

    public Order {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(customerId, "customerId is required");
        Objects.requireNonNull(total, "total is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(payment, "payment is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (shippingAddress == null || shippingAddress.isBlank()) {
            throw new IllegalArgumentException("Shipping address is required");
        }
        if (shippingAddress.length() > MAX_ADDRESS_LENGTH) {
            throw new IllegalArgumentException("Shipping address must be at most " + MAX_ADDRESS_LENGTH + " characters");
        }
        lines = List.copyOf(lines);
    }

    /**
     * Places an order. All lines must share one currency, and every SKU may appear only once;
     * the total is computed here, never accepted from the client.
     */
    public static Order place(UUID customerId, List<OrderLine> lines, String shippingAddress, String cardToken,
                              Instant now) {
        if (cardToken == null || cardToken.isBlank()) {
            throw new IllegalArgumentException("A payment method (card token) is required");
        }
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
                Status.PLACED, null, cardToken.trim(), PaymentStatus.NOT_REQUESTED, now, now);
    }

    /** Stock is reserved: ask for the payment. The order stays PLACED until the payment is authorized. */
    public Order stockReserved(Instant now) {
        if (status != Status.PLACED || payment != PaymentStatus.NOT_REQUESTED) {
            return this;
        }
        return with(status, cancellationReason, PaymentStatus.AUTHORIZING, now);
    }

    /**
     * The payment is authorized: stock and money are both held, the order is confirmed. If the order
     * was cancelled meanwhile, its void is already on the way and nothing changes here.
     */
    public Order paymentAuthorized(Instant now) {
        if (status == Status.PLACED && payment == PaymentStatus.AUTHORIZING) {
            return with(Status.CONFIRMED, null, PaymentStatus.AUTHORIZED, now);
        }
        if (status == Status.PLACED) {
            throw new IllegalOrderTransitionException(id, status, "authorize the payment of");
        }
        return this;
    }

    /** The card was declined: nothing is charged, the order is cancelled and its stock released. */
    public Order paymentDeclined(String reason, Instant now) {
        if (status == Status.PLACED && payment == PaymentStatus.AUTHORIZING) {
            return with(Status.CANCELLED, "Payment declined: " + requiredReason(reason), PaymentStatus.DECLINED, now);
        }
        if (status == Status.CANCELLED && payment == PaymentStatus.VOIDING) {
            return with(status, cancellationReason, PaymentStatus.DECLINED, now); // nothing was held to void
        }
        if (payment == PaymentStatus.DECLINED) {
            return this;
        }
        throw new IllegalOrderTransitionException(id, status, "decline the payment of");
    }

    public Order paymentVoided(Instant now) {
        return switch (payment) {
            case VOIDING -> with(status, cancellationReason, PaymentStatus.VOIDED, now);
            case VOIDED, DECLINED -> this;
            default -> throw new IllegalOrderTransitionException(id, status, "void the payment of");
        };
    }

    public Order paymentCaptured(Instant now) {
        return switch (payment) {
            case CAPTURING -> with(status, cancellationReason, PaymentStatus.CAPTURED, now);
            case CAPTURED -> this;
            default -> throw new IllegalOrderTransitionException(id, status, "capture the payment of");
        };
    }

    /** The parcel has left, so the order goes on; the money is owed (ADR-0009). */
    public Order paymentCaptureFailed(Instant now) {
        return switch (payment) {
            case CAPTURING -> with(status, cancellationReason, PaymentStatus.OVERDUE, now);
            case OVERDUE -> this;
            default -> throw new IllegalOrderTransitionException(id, status, "fail the capture of");
        };
    }

    /** The parcel has left. Also while a cancellation is pending: then the dispatch won the race. */
    public Order ship(Instant now) {
        if (status.isAtOrBeyond(Status.SHIPPED)) {
            return this;
        }
        if (status != Status.CONFIRMED && status != Status.CANCELLATION_REQUESTED) {
            throw new IllegalOrderTransitionException(id, status, "ship");
        }
        // Money moves when the goods leave.
        return with(Status.SHIPPED, null, payment == PaymentStatus.AUTHORIZED ? PaymentStatus.CAPTURING : payment, now);
    }

    /**
     * Only a shipped order can be delivered. Dispatch and delivery arrive on the same topic with the
     * order id as key, so delivery never overtakes dispatch in normal operation. If it seems to, the
     * dispatch was dead-lettered: accepting the delivery would mark the order delivered while the
     * catalog never committed its stock. Refusing sends this event to the dead-letter topic too, next
     * to the dispatch it depends on.
     */
    public Order deliver(Instant now) {
        if (status == Status.DELIVERED) {
            return this;
        }
        if (status != Status.SHIPPED) {
            throw new IllegalOrderTransitionException(id, status, "deliver");
        }
        return withStatus(Status.DELIVERED, null, now);
    }

    /**
     * Cancels an order that is not confirmed yet: no shipment exists, so the order decides alone.
     * A confirmed order cannot be cancelled this way; see {@link #requestCancellation}.
     */
    public Order cancel(String reason, Instant now) {
        if (status == Status.CANCELLED) {
            return this;
        }
        if (status != Status.PLACED) {
            throw new IllegalOrderTransitionException(id, status, "cancel");
        }
        return with(Status.CANCELLED, requiredReason(reason), releaseHold(), now);
    }

    /** The customer wants to cancel a confirmed order; shipping will accept or refuse. */
    public Order requestCancellation(String reason, Instant now) {
        if (status == Status.CANCELLATION_REQUESTED || status == Status.CANCELLED) {
            return this;
        }
        if (status != Status.CONFIRMED) {
            throw new IllegalOrderTransitionException(id, status, "request cancellation");
        }
        return withStatus(Status.CANCELLATION_REQUESTED, requiredReason(reason), now);
    }

    /** Shipping stopped the shipment: the order is cancelled, with the reason the customer gave. */
    public Order cancellationAccepted(Instant now) {
        if (status == Status.CANCELLED) {
            return this;
        }
        if (status != Status.CANCELLATION_REQUESTED) {
            throw new IllegalOrderTransitionException(id, status, "accept cancellation");
        }
        return with(Status.CANCELLED, cancellationReason, releaseHold(), now);
    }

    /**
     * Shipping could not stop the shipment. Normally the order is already SHIPPED by then (the
     * dispatch precedes the refusal on the topic) and nothing changes; otherwise it is confirmed again.
     */
    public Order cancellationRefused(Instant now) {
        if (status == Status.CONFIRMED || status.isAtOrBeyond(Status.SHIPPED)) {
            return this;
        }
        if (status != Status.CANCELLATION_REQUESTED) {
            throw new IllegalOrderTransitionException(id, status, "refuse cancellation");
        }
        return withStatus(Status.CONFIRMED, null, now);
    }

    private static String requiredReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A cancellation needs a reason");
        }
        return reason.trim();
    }

    /** A cancelled order holds no money: an authorization, granted or still pending, is voided. */
    private PaymentStatus releaseHold() {
        return payment == PaymentStatus.AUTHORIZING || payment == PaymentStatus.AUTHORIZED ? PaymentStatus.VOIDING : payment;
    }

    private Order withStatus(Status newStatus, String reason, Instant now) {
        return with(newStatus, reason, payment, now);
    }

    private Order with(Status newStatus, String reason, PaymentStatus newPayment, Instant now) {
        return new Order(id, customerId, lines, total, shippingAddress, newStatus, reason, cardToken, newPayment,
                createdAt, now);
    }
}
