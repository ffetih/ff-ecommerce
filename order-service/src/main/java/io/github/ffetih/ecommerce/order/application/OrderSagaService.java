package io.github.ffetih.ecommerce.order.application;

import io.github.ffetih.ecommerce.order.application.port.OrderEvents;
import io.github.ffetih.ecommerce.order.application.port.OrderRepository;
import io.github.ffetih.ecommerce.order.application.port.ProcessedEvents;
import io.github.ffetih.ecommerce.order.application.port.Transactions;
import io.github.ffetih.ecommerce.order.domain.Order;

import java.time.Clock;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Order-service's part of the fulfilment saga (ADR-0007): moves the order along as other services
 * report progress, and starts compensation when a customer cancels.
 * <p>
 * Each handler runs in one transaction with its inbox entry, so a redelivered event changes nothing.
 * Steps the order has already passed are ignored by the state machine. Impossible steps (shipping a
 * cancelled order) throw, roll back, and end up in a dead-letter topic: they mean two services disagree.
 */
public class OrderSagaService {

    /** Inbox consumer name and Kafka consumer group of the listener for these events. */
    public static final String RESERVATION_EVENTS_CONSUMER = "order-service.reservations";
    /** Inbox consumer name and Kafka consumer group of the listener for these events. */
    public static final String SHIPMENT_EVENTS_CONSUMER = "order-service.shipments";

    private final OrderRepository orders;
    private final OrderEvents events;
    private final ProcessedEvents processedEvents;
    private final Transactions transactions;
    private final Clock clock;

    public OrderSagaService(OrderRepository orders, OrderEvents events, ProcessedEvents processedEvents,
                            Transactions transactions, Clock clock) {
        this.orders = orders;
        this.events = events;
        this.processedEvents = processedEvents;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * {@code catalog.stock-reserved}. Normally confirms the order.
     * <p>
     * If the order was cancelled meanwhile, it stays cancelled and announces the cancellation again.
     * Usually catalog has already released the stock (it reads order-placed and order-cancelled from one
     * partition, in that order), and the repeated event is a harmless no-op there. It matters when the
     * reservation happened <em>after</em> the release, for example when a dead-lettered order-placed
     * event is replayed later: without this, that stock would stay held for an order nobody will ship.
     */
    public void stockReserved(UUID eventId, UUID orderId) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(RESERVATION_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            Order order = load(orderId);
            if (order.status() == Order.Status.CANCELLED) {
                events.orderCancelled(order);
                return null;
            }
            Order confirmed = order.confirm(clock.instant());
            if (confirmed != order) {
                orders.update(confirmed);
                events.orderConfirmed(confirmed);
            }
            return null;
        });
    }

    /** {@code catalog.stock-reservation-failed}: nothing is held, the order cannot be fulfilled. */
    public void reservationFailed(UUID eventId, UUID orderId, String reason) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(RESERVATION_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            Order order = load(orderId);
            if (order.status() == Order.Status.PLACED) {
                Order cancelled = order.cancel("Stock unavailable: " + reason, clock.instant());
                orders.update(cancelled);
                events.orderCancelled(cancelled);
            }
            return null;
        });
    }

    /**
     * The customer cancels. Only the order's customer or the back office may; for anyone else the order
     * does not exist (404).
     * <ul>
     *   <li>PLACED: cancelled at once and announced; catalog releases whatever it reserved.</li>
     *   <li>CONFIRMED: a shipment exists, and only shipping knows whether it has left. The order
     *       becomes CANCELLATION_REQUESTED and asks; shipping answers with
     *       {@link #shipmentCancelled} or {@link #cancellationRefused}.</li>
     *   <li>Already requested or cancelled: nothing changes. Shipped or delivered: refused (409).</li>
     * </ul>
     */
    public Order cancelByCustomer(UUID orderId, String reason, Requester requester) {
        return transactions.inTransaction(() -> {
            Order order = load(orderId);
            if (!requester.mayActFor(order.customerId())) {
                throw new OrderNotFoundException(orderId);
            }
            if (order.status() == Order.Status.PLACED) {
                Order cancelled = order.cancel(reason, clock.instant());
                orders.update(cancelled);
                events.orderCancelled(cancelled);
                return cancelled;
            }
            Order requested = order.requestCancellation(reason, clock.instant());
            if (requested != order) {
                orders.update(requested);
                events.cancellationRequested(requested);
            }
            return requested;
        });
    }

    /**
     * {@code shipping.shipment-cancelled}: shipping accepted the request. Now the order is cancelled,
     * and the announcement tells catalog to release the reservation.
     */
    public void shipmentCancelled(UUID eventId, UUID orderId) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(SHIPMENT_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            Order order = load(orderId);
            Order cancelled = order.cancellationAccepted(clock.instant());
            if (cancelled != order) {
                orders.update(cancelled);
                events.orderCancelled(cancelled);
            }
            return null;
        });
    }

    /** {@code shipping.cancellation-refused}: the parcel had left; the order goes on (usually already SHIPPED). */
    public void cancellationRefused(UUID eventId, UUID orderId) {
        applyShipmentStep(eventId, orderId, order -> order.cancellationRefused(clock.instant()));
    }

    /** {@code shipping.shipment-dispatched}. */
    public void shipmentDispatched(UUID eventId, UUID orderId) {
        applyShipmentStep(eventId, orderId, order -> order.ship(clock.instant()));
    }

    /** {@code shipping.shipment-delivered}. */
    public void shipmentDelivered(UUID eventId, UUID orderId) {
        applyShipmentStep(eventId, orderId, order -> order.deliver(clock.instant()));
    }

    private void applyShipmentStep(UUID eventId, UUID orderId, UnaryOperator<Order> step) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(SHIPMENT_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            Order order = load(orderId);
            Order next = step.apply(order);
            if (next != order) {
                orders.update(next);
            }
            return null;
        });
    }

    private Order load(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
