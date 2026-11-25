package io.github.ffetih.ecommerce.shipping.application;

import io.github.ffetih.ecommerce.shipping.application.port.CancelledOrders;
import io.github.ffetih.ecommerce.shipping.application.port.ProcessedEvents;
import io.github.ffetih.ecommerce.shipping.application.port.ShipmentEvents;
import io.github.ffetih.ecommerce.shipping.application.port.ShipmentRepository;
import io.github.ffetih.ecommerce.shipping.application.port.Transactions;
import io.github.ffetih.ecommerce.shipping.domain.Shipment;
import io.github.ffetih.ecommerce.shipping.domain.ShipmentLine;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Shipping's part of the order saga (ADR-0007), plus the warehouse operations that drive it. */
public class ShippingService {

    /** Inbox consumer name and Kafka consumer group of the listener for these events. */
    public static final String ORDER_EVENTS_CONSUMER = "shipping-service.order-lifecycle";

    private static final System.Logger log = System.getLogger(ShippingService.class.getName());

    private final ShipmentRepository shipments;
    private final ShipmentEvents events;
    private final ProcessedEvents processedEvents;
    private final CancelledOrders cancelledOrders;
    private final Transactions transactions;
    private final Clock clock;
    private final Supplier<String> trackingNumbers;

    public ShippingService(ShipmentRepository shipments, ShipmentEvents events, ProcessedEvents processedEvents,
                           CancelledOrders cancelledOrders, Transactions transactions, Clock clock) {
        this(shipments, events, processedEvents, cancelledOrders, transactions, clock,
                () -> "TRK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT));
    }

    ShippingService(ShipmentRepository shipments, ShipmentEvents events, ProcessedEvents processedEvents,
                    CancelledOrders cancelledOrders, Transactions transactions, Clock clock,
                    Supplier<String> trackingNumbers) {
        this.shipments = shipments;
        this.events = events;
        this.processedEvents = processedEvents;
        this.cancelledOrders = cancelledOrders;
        this.transactions = transactions;
        this.clock = clock;
        this.trackingNumbers = trackingNumbers;
    }

    /**
     * {@code order.order-confirmed}: prepare a parcel. At most one shipment per order, and none for an
     * order whose cancellation shipping has already accepted: that confirmation is late (typically
     * replayed from a dead-letter topic) and the order's stock is already released.
     */
    public void orderConfirmed(UUID eventId, UUID orderId, UUID customerId, List<ShipmentLine> lines, String address) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(ORDER_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            if (cancelledOrders.contains(orderId)) {
                log.log(System.Logger.Level.WARNING,
                        "Order {0} was confirmed after shipping accepted its cancellation; no shipment created", orderId);
                return null;
            }
            if (shipments.findByOrderId(orderId).isEmpty()) {
                shipments.save(Shipment.create(orderId, customerId, lines, address, clock.instant()));
            }
            return null;
        });
    }

    /**
     * {@code order.cancellation-requested}: decide, as the owner of the point of no return (ADR-0007).
     * <ul>
     *   <li>not dispatched: cancel the shipment and answer {@code shipment-cancelled};</li>
     *   <li>dispatched or delivered: answer {@code cancellation-refused}.</li>
     * </ul>
     * A dispatch of the same shipment at the same moment is settled by the shipment's version: one of
     * the two transactions fails and is repeated (the HTTP dispatch as a 409 to the warehouse, this
     * event by the Kafka error handler), and then sees the other's outcome.
     */
    public void cancellationRequested(UUID eventId, UUID orderId) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(ORDER_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            Optional<Shipment> shipment = shipments.findByOrderId(orderId);
            if (shipment.isEmpty()) {
                // Only possible if order-confirmed never got here (dead-lettered): nothing can leave.
                // Remember the answer, so that confirmation cannot create a shipment if it is replayed.
                log.log(System.Logger.Level.WARNING, "Cancellation requested for order {0} without a shipment", orderId);
                cancelledOrders.record(orderId, clock.instant());
                events.cancelled(orderId, null);
                return null;
            }
            Shipment current = shipment.get();
            if (current.isDispatched()) {
                events.cancellationRefused(current, "Shipment " + current.id() + " has already been dispatched");
                return null;
            }
            Shipment cancelled = current.cancel(clock.instant());
            if (cancelled != current) {
                shipments.save(cancelled);
            }
            events.cancelled(orderId, current.id());
            return null;
        });
    }

    /**
     * {@code order.order-cancelled}: the final cancellation. For a confirmed order, shipping has already
     * cancelled the shipment when it accepted the request, so this changes nothing; an order cancelled
     * before confirmation never had a shipment. A dispatched shipment here would mean the order was
     * cancelled without asking shipping, which the order flow no longer does.
     */
    public void orderCancelled(UUID eventId, UUID orderId) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(ORDER_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            Optional<Shipment> shipment = shipments.findByOrderId(orderId);
            if (shipment.isEmpty()) {
                return null;
            }
            if (shipment.get().isDispatched()) {
                log.log(System.Logger.Level.WARNING,
                        "Order {0} was cancelled after shipment {1} was dispatched", orderId, shipment.get().id());
                return null;
            }
            Shipment cancelled = shipment.get().cancel(clock.instant());
            if (cancelled != shipment.get()) {
                shipments.save(cancelled);
            }
            return null;
        });
    }

    /** The warehouse hands the parcel to the carrier. */
    public Shipment dispatch(UUID shipmentId) {
        return transactions.inTransaction(() -> {
            Shipment current = load(shipmentId);
            Shipment dispatched = current.dispatch(trackingNumbers.get(), clock.instant());
            if (dispatched != current) {
                shipments.save(dispatched);
                events.dispatched(dispatched);
            }
            return dispatched;
        });
    }

    /** The carrier confirms delivery. */
    public Shipment deliver(UUID shipmentId) {
        return transactions.inTransaction(() -> {
            Shipment current = load(shipmentId);
            Shipment delivered = current.deliver(clock.instant());
            if (delivered != current) {
                shipments.save(delivered);
                events.delivered(delivered);
            }
            return delivered;
        });
    }

    public Shipment get(UUID shipmentId) {
        return transactions.inTransaction(() -> load(shipmentId));
    }

    public Shipment forOrder(UUID orderId) {
        return transactions.inTransaction(() -> shipments.findByOrderId(orderId)
                .orElseThrow(() -> new ShipmentNotFoundException("order", orderId)));
    }

    private Shipment load(UUID shipmentId) {
        return shipments.findById(shipmentId).orElseThrow(() -> new ShipmentNotFoundException("id", shipmentId));
    }
}
