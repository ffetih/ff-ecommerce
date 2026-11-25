package io.github.ffetih.ecommerce.order.application;

import io.github.ffetih.ecommerce.order.application.port.OrderEvents;
import io.github.ffetih.ecommerce.order.application.port.OrderRepository;
import io.github.ffetih.ecommerce.order.application.port.Transactions;
import io.github.ffetih.ecommerce.order.domain.Order;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stops orders that wait too long, through the same gate as a customer's cancellation (ADR-0007):
 * <ul>
 *   <li>PLACED for longer than {@code placedAfter}: cancelled at once; catalog releases whatever it
 *       reserved when it reads {@code order-cancelled}.</li>
 *   <li>CONFIRMED for longer than {@code confirmedAfter} without being dispatched: cancellation is
 *       requested, and shipping decides, exactly as for a customer. If the parcel left meanwhile,
 *       the order ships.</li>
 * </ul>
 * Catalog and shipping never expire anything on their own: that would put a second decider back
 * into the saga. Orders waiting for shipping's answer (CANCELLATION_REQUESTED) are not expired again;
 * the answer is already on its way, or stuck in a dead-letter topic, which the dead-letter metric shows.
 * <p>
 * Each order is handled in its own transaction, after locking its row with SKIP LOCKED: two scanners
 * do not work on the same order, and one failing order does not stop the others. Time comes in as a
 * parameter, so tests move it instead of waiting.
 */
public class OrderExpiry {

    public record Settings(Duration placedAfter, Duration confirmedAfter, int batchSize) {
    }

    static final String NOT_CONFIRMED_IN_TIME = "Not confirmed in time";
    static final String NOT_DISPATCHED_IN_TIME = "Not dispatched in time";

    private static final System.Logger log = System.getLogger(OrderExpiry.class.getName());

    private final OrderRepository orders;
    private final OrderEvents events;
    private final Transactions transactions;
    private final Settings settings;

    public OrderExpiry(OrderRepository orders, OrderEvents events, Transactions transactions, Settings settings) {
        this.orders = orders;
        this.events = events;
        this.transactions = transactions;
        this.settings = settings;
    }

    /** Expires at most one batch of overdue orders, oldest first; returns how many it changed. */
    public int expireStale(Instant now) {
        Instant placedBefore = now.minus(settings.placedAfter());
        Instant confirmedBefore = now.minus(settings.confirmedAfter());
        List<UUID> candidates = transactions.inTransaction(() ->
                orders.findStale(placedBefore, confirmedBefore, settings.batchSize()));
        int expired = 0;
        for (UUID orderId : candidates) {
            try {
                if (expire(orderId, placedBefore, confirmedBefore, now)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                // Typically a concurrent change (customer cancel, saga event) that won the version check.
                // The next scan sees the order's new state.
                log.log(System.Logger.Level.WARNING, "Could not expire order {0}: {1}", orderId, e.toString());
            }
        }
        return expired;
    }

    private boolean expire(UUID orderId, Instant placedBefore, Instant confirmedBefore, Instant now) {
        return transactions.inTransaction(() -> {
            Optional<Order> locked = orders.lockIfFree(orderId);
            if (locked.isEmpty()) {
                return false; // another scanner is on it
            }
            // Checked again under the lock: the order may have moved on since the scan.
            Order order = locked.get();
            if (order.status() == Order.Status.PLACED && order.updatedAt().isBefore(placedBefore)) {
                Order cancelled = order.cancel(NOT_CONFIRMED_IN_TIME, now);
                orders.update(cancelled);
                events.orderCancelled(cancelled);
                return true;
            }
            if (order.status() == Order.Status.CONFIRMED && order.updatedAt().isBefore(confirmedBefore)) {
                Order requested = order.requestCancellation(NOT_DISPATCHED_IN_TIME, now);
                orders.update(requested);
                events.cancellationRequested(requested);
                return true;
            }
            return false;
        });
    }
}
