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
 * Order-service's part of the fulfilment saga: moves the order along as other services report
 * progress, and starts compensation when a customer cancels. Catalog and shipping are choreographed
 * (ADR-0007); payment is commanded from here (ADR-0009):
 * <pre>
 *   stock-reserved      ──► authorize-payment          (order stays PLACED)
 *   payment-authorized  ──► CONFIRMED, order-confirmed (shipping prepares the parcel)
 *   payment-declined    ──► CANCELLED, order-cancelled (catalog releases the stock)
 *   shipment-dispatched ──► SHIPPED, capture-payment
 *   cancelled while money is held ──► void-payment
 * </pre>
 * Each handler runs in one transaction with its inbox entry and the messages it sends (outbox), so a
 * redelivered event changes nothing. Steps the order has already passed are ignored by the state
 * machine. Impossible steps (shipping a cancelled order) throw, roll back, and end up in a dead-letter
 * topic: they mean two services disagree.
 */
public class OrderSagaService {

    /** Inbox consumer name and Kafka consumer group of the listener for these events. */
    public static final String RESERVATION_EVENTS_CONSUMER = "order-service.reservations";
    /** Inbox consumer name and Kafka consumer group of the listener for these events. */
    public static final String SHIPMENT_EVENTS_CONSUMER = "order-service.shipments";
    /** Inbox consumer name and Kafka consumer group of the listener for these events. */
    public static final String PAYMENT_EVENTS_CONSUMER = "order-service.payments";

    private static final System.Logger log = System.getLogger(OrderSagaService.class.getName());

    private final OrderRepository orders;
    private final OrderEvents events;
    private final PaymentSteps payments;
    private final ProcessedEvents processedEvents;
    private final Transactions transactions;
    private final Clock clock;

    public OrderSagaService(OrderRepository orders, OrderEvents events, PaymentSteps payments,
                            ProcessedEvents processedEvents, Transactions transactions, Clock clock) {
        this.orders = orders;
        this.events = events;
        this.payments = payments;
        this.processedEvents = processedEvents;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * {@code catalog.stock-reserved}. Normally asks payment-service to authorize the order's amount;
     * the order is confirmed when the authorization arrives.
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
            store(order, order.stockReserved(clock.instant()));
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
                store(order, cancelled);
                events.orderCancelled(cancelled);
            }
            return null;
        });
    }

    /** {@code payment.payment-authorized}: stock and money are held, the order is confirmed. */
    public void paymentAuthorized(UUID eventId, UUID orderId) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(PAYMENT_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            Order order = load(orderId);
            Order confirmed = order.paymentAuthorized(clock.instant());
            if (store(order, confirmed)) {
                events.orderConfirmed(confirmed);
            }
            return null;
        });
    }

    /** {@code payment.payment-declined}: the order is cancelled, and the announcement releases its stock. */
    public void paymentDeclined(UUID eventId, UUID orderId, String reason) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(PAYMENT_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            Order order = load(orderId);
            Order declined = order.paymentDeclined(reason, clock.instant());
            if (store(order, declined) && order.status() != Order.Status.CANCELLED) {
                events.orderCancelled(declined);
            }
            return null;
        });
    }

    /** {@code payment.payment-captured}. */
    public void paymentCaptured(UUID eventId, UUID orderId) {
        applyStep(PAYMENT_EVENTS_CONSUMER, eventId, orderId, order -> order.paymentCaptured(clock.instant()));
    }

    /**
     * {@code payment.payment-capture-failed}: the parcel has left, so nothing is cancelled. The money is
     * owed, and the order shows it ({@code OVERDUE}) for operations to collect.
     */
    public void paymentCaptureFailed(UUID eventId, UUID orderId, String reason) {
        applyStep(PAYMENT_EVENTS_CONSUMER, eventId, orderId, order -> {
            log.log(System.Logger.Level.WARNING, "Capture failed for shipped order {0}: {1}", orderId, reason);
            return order.paymentCaptureFailed(clock.instant());
        });
    }

    /** {@code payment.payment-voided}: the hold of a cancelled order is released. */
    public void paymentVoided(UUID eventId, UUID orderId) {
        applyStep(PAYMENT_EVENTS_CONSUMER, eventId, orderId, order -> order.paymentVoided(clock.instant()));
    }

    /**
     * The customer cancels. Only the order's customer or the back office may; for anyone else the order
     * does not exist (404).
     * <ul>
     *   <li>PLACED: cancelled at once and announced; catalog releases whatever it reserved, and a
     *       payment authorization, if one was asked for, is voided.</li>
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
                store(order, cancelled);
                events.orderCancelled(cancelled);
                return cancelled;
            }
            Order requested = order.requestCancellation(reason, clock.instant());
            if (store(order, requested)) {
                events.cancellationRequested(requested);
            }
            return requested;
        });
    }

    /**
     * {@code shipping.shipment-cancelled}: shipping accepted the request. Now the order is cancelled:
     * the announcement tells catalog to release the reservation, and the payment's hold is voided.
     */
    public void shipmentCancelled(UUID eventId, UUID orderId) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(SHIPMENT_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            Order order = load(orderId);
            Order cancelled = order.cancellationAccepted(clock.instant());
            if (store(order, cancelled)) {
                events.orderCancelled(cancelled);
            }
            return null;
        });
    }

    /** {@code shipping.cancellation-refused}: the parcel had left; the order goes on (usually already SHIPPED). */
    public void cancellationRefused(UUID eventId, UUID orderId) {
        applyStep(SHIPMENT_EVENTS_CONSUMER, eventId, orderId, order -> order.cancellationRefused(clock.instant()));
    }

    /** {@code shipping.shipment-dispatched}: the goods have left, the money is captured. */
    public void shipmentDispatched(UUID eventId, UUID orderId) {
        applyStep(SHIPMENT_EVENTS_CONSUMER, eventId, orderId, order -> order.ship(clock.instant()));
    }

    /** {@code shipping.shipment-delivered}. */
    public void shipmentDelivered(UUID eventId, UUID orderId) {
        applyStep(SHIPMENT_EVENTS_CONSUMER, eventId, orderId, order -> order.deliver(clock.instant()));
    }

    private void applyStep(String consumer, UUID eventId, UUID orderId, UnaryOperator<Order> step) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(consumer, eventId)) {
                return null;
            }
            Order order = load(orderId);
            store(order, step.apply(order));
            return null;
        });
    }

    /** Stores a change and sends the payment command it calls for; {@code false} if nothing changed. */
    private boolean store(Order before, Order after) {
        if (after == before) {
            return false;
        }
        orders.update(after);
        payments.after(before, after);
        return true;
    }

    private Order load(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
