package io.github.ffetih.ecommerce.shipping.application;

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

    static final String ORDER_EVENTS_CONSUMER = "shipping-service.order-lifecycle";

    private static final System.Logger log = System.getLogger(ShippingService.class.getName());

    private final ShipmentRepository shipments;
    private final ShipmentEvents events;
    private final ProcessedEvents processedEvents;
    private final Transactions transactions;
    private final Clock clock;
    private final Supplier<String> trackingNumbers;

    public ShippingService(ShipmentRepository shipments, ShipmentEvents events, ProcessedEvents processedEvents,
                           Transactions transactions, Clock clock) {
        this(shipments, events, processedEvents, transactions, clock,
                () -> "TRK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT));
    }

    ShippingService(ShipmentRepository shipments, ShipmentEvents events, ProcessedEvents processedEvents,
                    Transactions transactions, Clock clock, Supplier<String> trackingNumbers) {
        this.shipments = shipments;
        this.events = events;
        this.processedEvents = processedEvents;
        this.transactions = transactions;
        this.clock = clock;
        this.trackingNumbers = trackingNumbers;
    }

    /** {@code order.order-confirmed}: prepare a parcel. At most one shipment per order. */
    public void orderConfirmed(UUID eventId, UUID orderId, UUID customerId, List<ShipmentLine> lines, String address) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(ORDER_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            if (shipments.findByOrderId(orderId).isEmpty()) {
                shipments.save(Shipment.create(orderId, customerId, lines, address, clock.instant()));
            }
            return null;
        });
    }

    /**
     * {@code order.order-cancelled}: stop the parcel if it has not left. If it has, this is the
     * cancel-versus-dispatch race described in ADR-0007; the shipment stays dispatched and the
     * conflict surfaces when catalog and order-service refuse the dispatch event.
     */
    public void orderCancelled(UUID eventId, UUID orderId) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(ORDER_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            Optional<Shipment> shipment = shipments.findByOrderId(orderId);
            if (shipment.isEmpty()) {
                return null; // cancelled before confirmation: nothing was prepared
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
