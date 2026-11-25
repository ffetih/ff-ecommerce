package io.github.ffetih.ecommerce.catalog.application;

import io.github.ffetih.ecommerce.catalog.application.port.ProcessedEvents;
import io.github.ffetih.ecommerce.catalog.application.port.ReservationEvents;
import io.github.ffetih.ecommerce.catalog.application.port.ReservationRepository;
import io.github.ffetih.ecommerce.catalog.application.port.StockEvents;
import io.github.ffetih.ecommerce.catalog.application.port.StockRepository;
import io.github.ffetih.ecommerce.catalog.application.port.Transactions;
import io.github.ffetih.ecommerce.catalog.domain.InsufficientStockException;
import io.github.ffetih.ecommerce.catalog.domain.Reservation;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.domain.StockItem;
import io.github.ffetih.ecommerce.events.catalog.StockChanged;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * Holds, ships and releases stock for orders, called either over HTTP or by order-saga events (ADR-0007).
 * <p>
 * Every operation locks the affected stock rows in SKU order before reading or changing anything,
 * then re-reads the reservation, so two requests for the same order run one after the other. Each
 * stock change is announced with a {@code catalog.stock-changed} event in the same transaction.
 * <p>
 * Saga handlers turn business failures (not enough stock, unknown product) into a failure event
 * <em>inside</em> their transaction instead of throwing out of it, so the failure event, the inbox
 * entry and nothing else are committed together. Only technical errors leave the transaction.
 */
public class ReservationService {

    /** Inbox consumer name and Kafka consumer group of the listener for these events. */
    public static final String ORDER_EVENTS_CONSUMER = "catalog-service.order-lifecycle";
    /** Inbox consumer name and Kafka consumer group of the listener for these events. */
    public static final String SHIPMENT_EVENTS_CONSUMER = "catalog-service.shipments";

    private static final System.Logger log = System.getLogger(ReservationService.class.getName());

    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final StockEvents stockEvents;
    private final ReservationEvents reservationEvents;
    private final ProcessedEvents processedEvents;
    private final Transactions transactions;
    private final Clock clock;

    public ReservationService(StockRepository stock, ReservationRepository reservations, StockEvents stockEvents,
                              ReservationEvents reservationEvents, ProcessedEvents processedEvents,
                              Transactions transactions, Clock clock) {
        this.stock = stock;
        this.reservations = reservations;
        this.stockEvents = stockEvents;
        this.reservationEvents = reservationEvents;
        this.processedEvents = processedEvents;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Outcome of trying to reserve, as a value. Computing it never writes anything when it fails. */
    sealed interface Attempt {

        record Reserved(Reservation reservation) implements Attempt {
        }

        record AlreadyReserved(Reservation reservation) implements Attempt {
        }

        record Refused(RuntimeException reason) implements Attempt {
        }
    }

    // --- HTTP use cases ----------------------------------------------------------------------------

    /**
     * Reserves all lines or none. Repeating the call with the same order id and the same lines
     * returns the existing reservation; the same order id with different lines is a conflict.
     */
    public Reservation reserve(UUID orderId, List<Reservation.Line> lines) {
        Reservation requested = Reservation.place(orderId, lines, now());
        return transactions.inTransaction(() -> switch (attemptReservation(requested)) {
            case Attempt.Reserved reserved -> reserved.reservation();
            case Attempt.AlreadyReserved existing -> existing.reservation();
            case Attempt.Refused refused -> throw refused.reason();
        });
    }

    /** The order shipped: reserved units leave the warehouse. */
    public Reservation commit(UUID orderId) {
        return transactions.inTransaction(() -> applyTransition(lockedReservation(orderId),
                reservation -> reservation.commit(now()), StockItem::commit, StockChanged.COMMITTED));
    }

    /** The order was cancelled: reserved units become available again. */
    public Reservation release(UUID orderId) {
        return transactions.inTransaction(() -> applyTransition(lockedReservation(orderId),
                reservation -> reservation.release(now()), StockItem::release, StockChanged.RELEASED));
    }

    public Reservation get(UUID orderId) {
        return transactions.inTransaction(() ->
                reservations.findById(orderId).orElseThrow(() -> NotFoundException.reservation(orderId)));
    }

    // --- saga event handlers (ADR-0007) ------------------------------------------------------------

    /** {@code order.order-placed}: try to hold the stock and announce the outcome either way. */
    public void reserveForOrder(UUID eventId, UUID orderId, List<Reservation.Line> lines) {
        Reservation requested = Reservation.place(orderId, lines, now());
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(ORDER_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            switch (attemptReservation(requested)) {
                case Attempt.Reserved reserved -> reservationEvents.reserved(reserved.reservation());
                case Attempt.AlreadyReserved existing -> reservationEvents.reserved(existing.reservation());
                case Attempt.Refused refused -> reservationEvents.reservationFailed(orderId, refused.reason().getMessage());
            }
            return null;
        });
    }

    /**
     * {@code order.order-cancelled}: give the stock back. Nothing to do if no reservation exists (the
     * reservation failed) or it was already released. A committed reservation cannot be released: the
     * goods have left, which order-service prevents by refusing to cancel shipped orders.
     */
    public void releaseForOrder(UUID eventId, UUID orderId) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(ORDER_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            Optional<Reservation> current = reservations.lockById(orderId);
            if (current.isEmpty()) {
                return null;
            }
            if (current.get().status() == Reservation.Status.COMMITTED) {
                log.log(System.Logger.Level.WARNING,
                        "Order {0} was cancelled after its stock was committed; nothing released", orderId);
                return null;
            }
            return applyTransition(current.get(),
                    reservation -> reservation.release(now()), StockItem::release, StockChanged.RELEASED);
        });
    }

    /**
     * {@code shipping.shipment-dispatched}: the goods left. A missing or released reservation here means
     * the services disagree; the exception rolls back and the event ends up in the dead-letter topic for
     * a person to look at, rather than being guessed away.
     */
    public void commitForOrder(UUID eventId, UUID orderId) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(SHIPMENT_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            return applyTransition(lockedReservation(orderId),
                    reservation -> reservation.commit(now()), StockItem::commit, StockChanged.COMMITTED);
        });
    }

    // --- shared steps; callers provide the transaction ----------------------------------------------

    private Attempt attemptReservation(Reservation requested) {
        UUID orderId = requested.orderId();
        Map<Sku, StockItem> locked = stock.lockAll(skusOf(requested));

        Optional<Reservation> existing = reservations.findById(orderId);
        if (existing.isPresent()) {
            return existing.get().hasSameLines(requested.lines())
                    ? new Attempt.AlreadyReserved(existing.get())
                    : new Attempt.Refused(ConflictException.idempotencyKeyReused(orderId));
        }

        // Compute every new stock level before writing anything: a short line refuses the whole order.
        List<StockItem> updated;
        try {
            updated = requested.lines().stream()
                    .map(line -> lockedItem(locked, line.sku()).reserve(line.quantity()))
                    .toList();
        } catch (InsufficientStockException | NotFoundException refused) {
            return new Attempt.Refused(refused);
        }
        for (StockItem item : updated) {
            stock.save(item);
            stockEvents.stockChanged(item, StockChanged.RESERVED, orderId);
        }
        reservations.save(requested);
        return new Attempt.Reserved(requested);
    }

    private Reservation applyTransition(Reservation current, UnaryOperator<Reservation> change,
                                        BiFunction<StockItem, Integer, StockItem> stockChange, String reason) {
        Reservation next = change.apply(current);
        if (next == current) {
            return current; // already in the target state: repeating the call changes nothing
        }
        Map<Sku, StockItem> locked = stock.lockAll(skusOf(current));
        for (Reservation.Line line : current.lines()) {
            StockItem changed = stockChange.apply(lockedItem(locked, line.sku()), line.quantity());
            stock.save(changed);
            stockEvents.stockChanged(changed, reason, current.orderId());
        }
        reservations.save(next);
        return next;
    }

    private Reservation lockedReservation(UUID orderId) {
        return reservations.lockById(orderId).orElseThrow(() -> NotFoundException.reservation(orderId));
    }

    private static StockItem lockedItem(Map<Sku, StockItem> locked, Sku sku) {
        StockItem item = locked.get(sku);
        if (item == null) {
            throw NotFoundException.product(sku);
        }
        return item;
    }

    private static List<Sku> skusOf(Reservation reservation) {
        return reservation.lines().stream().map(Reservation.Line::sku).toList();
    }

    private Instant now() {
        return clock.instant();
    }
}
