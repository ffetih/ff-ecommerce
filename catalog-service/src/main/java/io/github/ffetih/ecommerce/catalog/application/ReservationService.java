package io.github.ffetih.ecommerce.catalog.application;

import io.github.ffetih.ecommerce.catalog.application.port.ReservationRepository;
import io.github.ffetih.ecommerce.catalog.application.port.StockRepository;
import io.github.ffetih.ecommerce.catalog.application.port.Transactions;
import io.github.ffetih.ecommerce.catalog.domain.Reservation;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.domain.StockItem;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Holds, ships and releases stock for orders.
 * <p>
 * Every operation locks the affected stock rows in SKU order before reading or changing anything,
 * then re-reads the reservation. Two requests for the same order therefore run one after the other,
 * and the second one sees what the first one committed.
 */
public class ReservationService {

    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final Transactions transactions;
    private final Clock clock;

    public ReservationService(StockRepository stock, ReservationRepository reservations,
                              Transactions transactions, Clock clock) {
        this.stock = stock;
        this.reservations = reservations;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Reserves all lines or none. Repeating the call with the same order id and the same lines
     * returns the existing reservation; the same order id with different lines is a conflict.
     */
    public Reservation reserve(UUID orderId, List<Reservation.Line> lines) {
        Reservation requested = Reservation.place(orderId, lines, clock.instant());
        return transactions.inTransaction(() -> {
            Map<Sku, StockItem> locked = stock.lockAll(skusOf(requested));

            Optional<Reservation> existing = reservations.findById(orderId);
            if (existing.isPresent()) {
                if (!existing.get().hasSameLines(requested.lines())) {
                    throw ConflictException.idempotencyKeyReused(orderId);
                }
                return existing.get();
            }

            // Compute every new stock level first: if one line is short, nothing is written.
            List<StockItem> updated = requested.lines().stream()
                    .map(line -> lockedItem(locked, line.sku()).reserve(line.quantity()))
                    .toList();
            updated.forEach(stock::save);
            reservations.save(requested);
            return requested;
        });
    }

    /** The order shipped: reserved units leave the warehouse. */
    public Reservation commit(UUID orderId) {
        return transition(orderId, reservation -> reservation.commit(now()), StockItem::commit);
    }

    /** The order was cancelled: reserved units become available again. */
    public Reservation release(UUID orderId) {
        return transition(orderId, reservation -> reservation.release(now()), StockItem::release);
    }

    public Reservation get(UUID orderId) {
        return transactions.inTransaction(() ->
                reservations.findById(orderId).orElseThrow(() -> NotFoundException.reservation(orderId)));
    }

    private Reservation transition(UUID orderId,
                                   Function<Reservation, Reservation> change,
                                   BiFunction<StockItem, Integer, StockItem> stockChange) {
        return transactions.inTransaction(() -> {
            Reservation current = reservations.lockById(orderId)
                    .orElseThrow(() -> NotFoundException.reservation(orderId));
            Reservation next = change.apply(current);
            if (next == current) {
                return current; // already in the target state: repeating the call changes nothing
            }
            Map<Sku, StockItem> locked = stock.lockAll(skusOf(current));
            for (Reservation.Line line : current.lines()) {
                stock.save(stockChange.apply(lockedItem(locked, line.sku()), line.quantity()));
            }
            reservations.save(next);
            return next;
        });
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
