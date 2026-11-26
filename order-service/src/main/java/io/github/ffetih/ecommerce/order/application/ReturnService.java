package io.github.ffetih.ecommerce.order.application;

import io.github.ffetih.ecommerce.order.application.port.OrderRepository;
import io.github.ffetih.ecommerce.order.application.port.PaymentCommands;
import io.github.ffetih.ecommerce.order.application.port.ProcessedEvents;
import io.github.ffetih.ecommerce.order.application.port.ReturnEvents;
import io.github.ffetih.ecommerce.order.application.port.ReturnRepository;
import io.github.ffetih.ecommerce.order.application.port.ReturnedUnits;
import io.github.ffetih.ecommerce.order.application.port.Transactions;
import io.github.ffetih.ecommerce.order.domain.Order;
import io.github.ffetih.ecommerce.order.domain.OrderLine;
import io.github.ffetih.ecommerce.order.domain.Return;
import io.github.ffetih.ecommerce.order.domain.ReturnLine;
import io.github.ffetih.ecommerce.order.domain.ReturnNotAllowedException;
import io.github.ffetih.ecommerce.order.domain.Sku;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Returns of delivered orders (ADR-0010).
 * <p>
 * "Never more than was bought" spans every return of an order, and two requests can arrive at the
 * same moment. The rule is therefore kept by the database: each requested line adds its units to a
 * per-line counter in one conditional statement ({@link ReturnedUnits#take}), in the transaction
 * that stores the return. If any line would exceed the ordered quantity, the whole request rolls back.
 * <p>
 * Receiving the parcel announces {@code return-received} (catalog restocks) and sends
 * {@code refund-payment}, in the same transaction as the change; payment-service's answer moves the
 * return to REFUNDED or REFUND_FAILED. A second "received" changes nothing and sends nothing.
 */
public class ReturnService {

    /** Results of a request; {@code replayed} is true when an earlier identical request is answered again. */
    public record Placement(Return value, boolean replayed) {
    }

    /** The returns of one order, and how many units of each line they hold. */
    public record OrderReturns(List<Return> returns, Map<Sku, Integer> returnedUnits) {
    }

    /** @param window how long after delivery goods can be returned */
    public record Settings(Duration window) {
    }

    private static final System.Logger log = System.getLogger(ReturnService.class.getName());

    private final OrderRepository orders;
    private final ReturnRepository returns;
    private final ReturnedUnits returnedUnits;
    private final ReturnEvents events;
    private final PaymentCommands payments;
    private final ProcessedEvents processedEvents;
    private final Transactions transactions;
    private final Clock clock;
    private final Settings settings;

    public ReturnService(OrderRepository orders, ReturnRepository returns, ReturnedUnits returnedUnits,
                         ReturnEvents events, PaymentCommands payments, ProcessedEvents processedEvents,
                         Transactions transactions, Clock clock, Settings settings) {
        this.orders = orders;
        this.returns = returns;
        this.returnedUnits = returnedUnits;
        this.events = events;
        this.payments = payments;
        this.processedEvents = processedEvents;
        this.transactions = transactions;
        this.clock = clock;
        this.settings = settings;
    }

    /**
     * The customer (or the back office for them) asks to send goods back, at most once per
     * (order, idempotency key). Someone else's order answers as unknown.
     */
    public Placement request(UUID orderId, List<Return.Requested> lines, String reason, Requester requester,
                             UUID idempotencyKey) {
        String fingerprint = fingerprint(lines, reason);
        try {
            return transactions.inTransaction(() -> {
                Order order = orders.findById(orderId)
                        .filter(found -> requester.mayActFor(found.customerId()))
                        .orElseThrow(() -> new OrderNotFoundException(orderId));
                Optional<Placement> earlier = replay(orderId, idempotencyKey, fingerprint);
                if (earlier.isPresent()) {
                    return earlier.get();
                }
                Return requested = Return.request(order, lines, reason, settings.window(), clock.instant());
                // In SKU order: two returns locking the same counters lock them in the same order, never crosswise.
                for (ReturnLine line : requested.lines().stream().sorted(Comparator.comparing(ReturnLine::sku)).toList()) {
                    int ordered = orderedQuantity(order, line.sku());
                    if (!returnedUnits.take(orderId, line.sku(), ordered, line.quantity())) {
                        // Rolls back the lines counted before this one, too.
                        throw new ReturnNotAllowedException("Not that many units of " + line.sku()
                                + " can still be returned: " + ordered + " ordered, some already returned");
                    }
                }
                returns.insert(requested, idempotencyKey, fingerprint);
                return new Placement(requested, false);
            });
        } catch (DuplicateIdempotencyKeyException lostTheRace) {
            return transactions.inTransaction(() -> replay(orderId, idempotencyKey, fingerprint))
                    .orElseThrow(() -> new IllegalStateException("Duplicate key reported but no return found", lostTheRace));
        }
    }

    public OrderReturns forOrder(UUID orderId, Requester requester) {
        return transactions.inTransaction(() -> {
            orders.findById(orderId)
                    .filter(order -> requester.mayActFor(order.customerId()))
                    .orElseThrow(() -> new OrderNotFoundException(orderId));
            return new OrderReturns(returns.findByOrderId(orderId), returnedUnits.of(orderId));
        });
    }

    public Return get(UUID returnId, Requester requester) {
        return transactions.inTransaction(() -> returns.findById(returnId)
                .filter(found -> requester.mayActFor(found.customerId()))
                .orElseThrow(() -> new ReturnNotFoundException(returnId)));
    }

    /**
     * The back office confirms the parcel has arrived: its units are stock again, and the refund starts.
     * Repeating it changes nothing and sends nothing; two clicks at the same moment are settled by the
     * return's version, so only one of them sends.
     */
    public Return receive(UUID returnId) {
        return transactions.inTransaction(() -> {
            Return current = load(returnId);
            Return received = current.receive(clock.instant());
            if (received != current) {
                returns.update(received);
                events.returnReceived(received);
                payments.refund(received);
            }
            return received;
        });
    }

    /** {@code payment.payment-refunded}: the money for the return is back with the customer. */
    public void refunded(UUID eventId, UUID returnId) {
        applyPaymentAnswer(eventId, returnId, value -> value.refunded(clock.instant()));
    }

    /** {@code payment.payment-refund-failed}: the money could not be given back; operations decide. */
    public void refundFailed(UUID eventId, UUID returnId, String reason) {
        applyPaymentAnswer(eventId, returnId, value -> {
            log.log(System.Logger.Level.WARNING, "Refund failed for return {0}: {1}", returnId, reason);
            return value.refundFailed(clock.instant());
        });
    }

    private void applyPaymentAnswer(UUID eventId, UUID returnId, UnaryOperator<Return> step) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(OrderSagaService.PAYMENT_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            Return current = load(returnId);
            Return next = step.apply(current);
            if (next != current) {
                returns.update(next);
            }
            return null;
        });
    }

    /** The back office refuses the return; its units can be returned again in another request. */
    public Return reject(UUID returnId, String reason) {
        return transactions.inTransaction(() -> {
            Return current = load(returnId);
            Return rejected = current.reject(reason, clock.instant());
            if (rejected != current) {
                returns.update(rejected);
                rejected.lines().forEach(line -> returnedUnits.giveBack(rejected.orderId(), line.sku(), line.quantity()));
            }
            return rejected;
        });
    }

    private Optional<Placement> replay(UUID orderId, UUID idempotencyKey, String fingerprint) {
        return returns.findByIdempotencyKey(orderId, idempotencyKey).map(earlier -> {
            if (!earlier.requestHash().equals(fingerprint)) {
                throw new IdempotencyKeyReusedException(idempotencyKey);
            }
            return new Placement(earlier.value(), true);
        });
    }

    private Return load(UUID returnId) {
        return returns.findById(returnId).orElseThrow(() -> new ReturnNotFoundException(returnId));
    }

    private static int orderedQuantity(Order order, Sku sku) {
        return order.lines().stream().filter(line -> line.sku().equals(sku)).mapToInt(OrderLine::quantity)
                .findFirst().orElseThrow();
    }

    /** SHA-256 of the request's meaning; line order does not matter. */
    private static String fingerprint(List<Return.Requested> lines, String reason) {
        StringBuilder canonical = new StringBuilder().append(reason == null ? "" : reason.trim()).append('|');
        if (lines != null) {
            lines.stream()
                    .sorted(Comparator.comparing(Return.Requested::sku))
                    .forEach(line -> canonical.append(line.sku()).append(':').append(line.quantity()).append(';'));
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
